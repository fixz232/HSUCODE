package com.hsucode.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * gap-22 本地 stdio MCP 传输(对标 grok 的 `[mcp_servers.x] command/args/env`、Operit 的本地 MCP)。
 *
 * 用 ProcessBuilder 拉起本地 MCP 服务器进程(可选经 root),在其 stdin/stdout 上按 MCP stdio 传输
 * 规范收发**换行分隔的 JSON-RPC**。设备上需具备可执行的运行时(如 Termux 的 node/npx/uvx,或 root 可执行的二进制),
 * 由用户在服务器配置里提供 command/args/env —— 与 grok 桌面端模型一致。
 */
class McpStdioClient(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val runAsRoot: Boolean = false,
    /** Optional launcher for an isolated runtime such as HSUCODE's PRoot Ubuntu. */
    private val processFactory: (() -> Process)? = null
) : McpTransport {

    companion object {
        private const val TAG = "McpStdio"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CALL_TIMEOUT_MS = 30_000L
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var stderrDrainer: Thread? = null
    private var nextId = 1
    private val lock = Any()
    private val stderrLog = StringBuilder()

    private fun ensureProcess() {
        if (process != null) return
        val full = if (runAsRoot) {
            // su -c "command args...";参数做基本单引号转义。
            val line = (listOf(command) + args).joinToString(" ") { shellQuote(it) }
            listOf("su", "-c", line)
        } else {
            listOf(command) + args
        }
        val p = processFactory?.invoke() ?: ProcessBuilder(full).apply {
            val e = environment()
            for ((k, v) in env) e[k] = v
            redirectErrorStream(false)
        }.start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
        stderrDrainer = Thread({
            runCatching {
                // Drain continuously to prevent a full stderr pipe from blocking the server.
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrLog) {
                            stderrLog.appendLine(line)
                            if (stderrLog.length > 4_000) stderrLog.delete(0, stderrLog.length - 4_000)
                        }
                    }
                }
            }
        }, "mcp-stderr-drain").apply { isDaemon = true; start() }
        Log.i(TAG, "spawned MCP stdio server: ${full.joinToString(" ")}")
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 发送一个带 id 的 JSON-RPC 请求,读到匹配 id 的响应(跳过通知/日志行)。 */
    private fun rpc(payload: JSONObject): JSONObject = synchronized(lock) {
        ensureProcess()
        val w = writer ?: throw McpException("stdio MCP: no stdin")
        val r = reader ?: throw McpException("stdio MCP: no stdout")
        val id = payload.opt("id")?.toString()
        w.write(payload.toString()); w.write("\n"); w.flush()
        var guard = 0
        while (guard++ < 10000) {
            val line = r.readLine() ?: throw McpException("stdio MCP: server closed stream${recentStderrSuffix()}")
            val t = line.trim()
            if (t.isEmpty() || !t.startsWith("{")) continue
            val obj = try { JSONObject(t) } catch (_: Exception) { continue }
            if (id == null) return obj
            if (obj.opt("id")?.toString() == id) return obj
            // 其它 id / 通知:跳过继续读。
        }
        throw McpException("stdio MCP: no matching response")
    }

    private fun notify(payload: JSONObject) = synchronized(lock) {
        ensureProcess()
        writer?.apply { write(payload.toString()); write("\n"); flush() }
        Unit
    }

    private suspend fun rpcWithTimeout(payload: JSONObject, timeoutMs: Long): JSONObject = try {
        withTimeout(timeoutMs) { runInterruptible(Dispatchers.IO) { rpc(payload) } }
    } catch (_: TimeoutCancellationException) {
        close()
        throw McpException("stdio MCP: request timed out after ${timeoutMs / 1000}s")
    }

    override suspend fun initialize(): McpServerInfo = withContext(Dispatchers.IO) {
        val init = JSONObject()
            .put("jsonrpc", "2.0").put("id", nextId++).put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "HSUCODE").put("version", "0.1.0")))
        val resp = rpcWithTimeout(init, CONNECT_TIMEOUT_MS)
        resp.optJSONObject("error")?.let { throw McpException("stdio initialize failed: ${it.optString("message")}") }
        notify(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").put("params", JSONObject()))
        val si = resp.optJSONObject("result")?.optJSONObject("serverInfo")
        McpServerInfo(si?.optString("name") ?: "stdio", si?.optString("version") ?: "unknown")
    }

    override suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val resp = rpcWithTimeout(JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "tools/list").put("params", JSONObject()), CALL_TIMEOUT_MS)
        resp.optJSONObject("error")?.let { throw McpException("stdio tools/list failed: ${it.optString("message")}") }
        val arr = resp.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            McpToolInfo(
                name = t.optString("name", ""),
                description = t.optString("description", ""),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
            )
        }
    }

    override suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val resp = rpcWithTimeout(JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "tools/call")
            .put("params", JSONObject().put("name", toolName).put("arguments", arguments)), CALL_TIMEOUT_MS)
        resp.optJSONObject("error")?.let { throw McpException("stdio tools/call ($toolName) failed: ${it.optString("message")}") }
        val content = resp.optJSONObject("result")?.optJSONArray("content") ?: JSONArray()
        buildString {
            for (i in 0 until content.length()) {
                val item = content.getJSONObject(i)
                when (item.optString("type")) {
                    "text" -> append(item.optString("text", ""))
                    "image" -> append("[image: ${item.optString("mimeType", "")}]")
                    else -> append("[${item.optString("type")}]")
                }
                if (i < content.length() - 1) append("\n")
            }
        }.ifEmpty { "(empty response)" }
    }

    override fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        try { stderrDrainer?.interrupt() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        stderrDrainer = null
    }

    fun recentStderr(): String = synchronized(stderrLog) { stderrLog.toString() }

    private fun recentStderrSuffix(): String = recentStderr().trim().takeIf { it.isNotBlank() }
        ?.let { "\n最近 stderr:\n${it.takeLast(1200)}" }.orEmpty()
}
