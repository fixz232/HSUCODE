package com.hsucode.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal MCP (Model Context Protocol) client using JSON-RPC over HTTP.
 *
 * Implements:
 * - initialize (handshake)
 * - tools/list (discover available tools)
 * - tools/call (invoke a tool)
 *
 * Transport: HTTP POST (not SSE for tool calls; SSE optional for streaming responses).
 * Each call is a JSON-RPC 2.0 request sent via OkHttp.
 */
class McpClient(
    private val okHttpClient: OkHttpClient,
    private val serverUrl: String,
    private val authHeader: String = ""
) : McpTransport {
    companion object {
        private const val TAG = "McpClient"
        private const val JSONRPC_VERSION = "2.0"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CALL_TIMEOUT_MS = 30_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private var nextId = 1
    private var initialized = false

    /** gap-23 Streamable-HTTP 持久会话:initialize 返回的 Mcp-Session-Id,后续请求回传。 */
    @Volatile
    private var mcpSessionId: String? = null

    @Volatile
    var lastContentType: String = ""
        private set

    /** True after the server advertises the persistent Streamable HTTP session header. */
    val isStreamableHttp: Boolean get() = !mcpSessionId.isNullOrBlank()

    /**
     * Initialize connection with the MCP server (handshake).
     * Sends initialize + initialized notification per MCP spec.
     * @return server info (name, version) or throws on failure.
     */
    override suspend fun initialize(): McpServerInfo = withContext(Dispatchers.IO) {
        withTimeout(CONNECT_TIMEOUT_MS) {
            // Step 1: initialize request
            val initPayload = JSONObject().apply {
                put("jsonrpc", JSONRPC_VERSION)
                put("id", nextId++)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "HSUCODE")
                        put("version", "0.1.0")
                    })
                })
            }

            val initResponse = sendRequest(initPayload)
            val error = initResponse.optJSONObject("error")
            if (error != null) {
                throw McpException("MCP initialize failed: ${error.optString("message")}")
            }

            val result = initResponse.optJSONObject("result")
            val serverInfo = result?.optJSONObject("serverInfo")
            val info = McpServerInfo(
                name = serverInfo?.optString("name") ?: "unknown",
                version = serverInfo?.optString("version") ?: "unknown"
            )

            // Step 2: send initialized notification (no id, no response expected)
            val notifPayload = JSONObject().apply {
                put("jsonrpc", JSONRPC_VERSION)
                put("method", "notifications/initialized")
                put("params", JSONObject())
            }
            sendNotification(notifPayload)

            initialized = true
            Log.i(TAG, "MCP initialized with server: ${info.name} v${info.version}")
            info
        }
    }

    /**
     * List all tools available on the MCP server.
     * @return list of McpToolInfo, or empty list on failure.
     */
    override suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        ensureInitialized()

        withTimeout(CALL_TIMEOUT_MS) {
            val payload = JSONObject().apply {
                put("jsonrpc", JSONRPC_VERSION)
                put("id", nextId++)
                put("method", "tools/list")
                put("params", JSONObject())
            }

            val response = sendRequest(payload)
            val error = response.optJSONObject("error")
            if (error != null) {
                throw McpException("MCP tools/list failed: ${error.optString("message")}")
            }

            val result = response.optJSONObject("result")
            val toolsArray = result?.optJSONArray("tools") ?: JSONArray()
            val tools = mutableListOf<McpToolInfo>()

            for (i in 0 until toolsArray.length()) {
                val toolObj = toolsArray.getJSONObject(i)
                tools.add(McpToolInfo(
                    name = toolObj.optString("name", ""),
                    description = toolObj.optString("description", ""),
                    inputSchema = toolObj.optJSONObject("inputSchema") ?: JSONObject()
                ))
            }

            Log.i(TAG, "MCP tools/list: ${tools.size} tools from $serverUrl")
            tools
        }
    }

    /**
     * Call a tool on the MCP server.
     * @param toolName the tool name
     * @param arguments the arguments as a JSONObject
     * @return the tool result content string
     */
    override suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        ensureInitialized()

        withTimeout(CALL_TIMEOUT_MS) {
            val payload = JSONObject().apply {
                put("jsonrpc", JSONRPC_VERSION)
                put("id", nextId++)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }

            val response = sendRequest(payload)
            val error = response.optJSONObject("error")
            if (error != null) {
                throw McpException("MCP tools/call ($toolName) failed: ${error.optString("message")}")
            }

            val result = response.optJSONObject("result")
            val contentArray = result?.optJSONArray("content") ?: JSONArray()
            buildString {
                for (i in 0 until contentArray.length()) {
                    val item = contentArray.getJSONObject(i)
                    val type = item.optString("type", "")
                    when (type) {
                        "text" -> append(item.optString("text", ""))
                        "image" -> append("[image: ${item.optString("mimeType", "")}]")
                        else -> append("[$type]")
                    }
                    if (i < contentArray.length() - 1) append("\n")
                }
            }.ifEmpty { "(empty response)" }
        }
    }

    fun disconnect() {
        initialized = false
    }

    /** Reads an MCP SSE response incrementally for servers that stream progress/events. */
    suspend fun streamRequest(payload: JSONObject, onEvent: (JSONObject) -> Unit): Result<JSONObject?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(serverUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .apply {
                    if (authHeader.isNotBlank()) header("Authorization", authHeader)
                    mcpSessionId?.let { header("Mcp-Session-Id", it) }
                }
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw McpException("MCP HTTP ${response.code}")
                response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { mcpSessionId = it }
                lastContentType = response.header("Content-Type").orEmpty()
                val source = response.body?.source() ?: throw McpException("MCP: empty stream")
                var last: JSONObject? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = line.trim().removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    last = event
                    onEvent(event)
                }
                last
            }
        }
    }

    override fun close() = disconnect()

    // ---- internals ----

    private fun ensureInitialized() {
        if (!initialized) throw McpException("MCP client not initialized (call initialize() first)")
    }

    private fun sendRequest(payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (authHeader.isNotBlank()) header("Authorization", authHeader)
                mcpSessionId?.let { header("Mcp-Session-Id", it) } // gap-23 回传会话 ID
            }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            // gap-23:捕获服务端下发的会话 ID(streamable-http 持久会话)。
            response.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) mcpSessionId = it }
            lastContentType = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful) throw McpException("MCP HTTP ${response.code}")
            val responseBody = response.body?.string() ?: throw McpException("MCP: empty response body")

            // MCP servers may return SSE or plain JSON. The response must be closed
            // here so repeated tool calls do not exhaust OkHttp's connection pool.
            return parseResponse(responseBody)
        }
    }

    /** Send a notification (fire-and-forget, no response expected). */
    private fun sendNotification(payload: JSONObject) {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .apply {
                if (authHeader.isNotBlank()) header("Authorization", authHeader)
                mcpSessionId?.let { header("Mcp-Session-Id", it) } // gap-23
            }
            .build()

        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Notification send failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Parse response body which may be plain JSON or SSE format.
     * SSE lines come as: "data: {json}\n"
     */
    private fun parseResponse(body: String): JSONObject {
        // Try plain JSON first
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }

        // Try SSE format. Progress events can precede the JSON-RPC result, so prefer
        // the event carrying `result`/`error` and only fall back to the last event.
        var lastEvent: JSONObject? = null
        for (line in trimmed.lines()) {
            val dataLine = line.trim()
            if (dataLine.startsWith("data:")) {
                val jsonStr = dataLine.removePrefix("data:").trim()
                if (jsonStr.startsWith("{")) {
                    val event = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
                    lastEvent = event
                    if (event.has("result") || event.has("error")) return event
                }
            }
        }

        lastEvent?.let { return it }

        throw McpException("MCP: cannot parse response: ${body.take(200)}")
    }
}

/** Info about the MCP server returned from initialize. */
data class McpServerInfo(
    val name: String,
    val version: String
)

/** Info about a tool on the MCP server. */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/** MCP-specific exception. */
class McpException(message: String) : Exception(message)
