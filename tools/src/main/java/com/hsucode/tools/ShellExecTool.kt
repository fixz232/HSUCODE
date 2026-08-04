package com.hsucode.tools

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes a shell command.
 *
 * Always runs as the app uid. Root execution is intentionally isolated in [SuExecTool]
 * so a normal shell request cannot silently cross the Android sandbox boundary.
 *
 * Safety: 30s hard timeout.
 * Output truncated to 4000 chars (stdout) / 2000 chars (stderr).
 */
class ShellExecTool : Tool {

    override val name = "shell_exec"
    override val description = "Execute a shell command as the app user. " +
            "Returns stdout on success (exitCode=0). On failure returns exitCode + stderr. " +
            "Use for commands like ls, cat, pwd, grep, id."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "The shell command to execute")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_STDOUT = 4000
        private const val MAX_STDERR = 2000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val command = params["command"] ?: return@withContext ToolResult.Error("缺少 command 参数")

        // Keep application-private runtime data out of shell commands even without root.
        SelfProtect.refuseCommand(command)?.let { return@withContext ToolResult.Error(it) }

        return@withContext executeViaSh(command)
    }

    /** Execute via sh -c as the application uid. */
    private suspend fun executeViaSh(command: String): ToolResult {
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .directory(java.io.File(WorkspaceContext.workspaceRoot))
                .start()

            return@executeViaSh withTimeout(TIMEOUT_SECONDS * 1000) {
                coroutineScope {
                    val stdoutRead = async(Dispatchers.IO) {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }
                    val stderrRead = async(Dispatchers.IO) {
                        process.errorStream.bufferedReader().use { it.readText() }
                    }
                    val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    val stdout = stdoutRead.await()
                    val stderr = stderrRead.await()

                    if (exitCode == 0) {
                        ToolResult.Success(stdout.trim().let {
                            if (it.length > MAX_STDOUT) it.take(MAX_STDOUT / 2) +
                                "\n[...已截断 ${it.length - MAX_STDOUT} 字符...]\n" +
                                it.takeLast(MAX_STDOUT / 2)
                            else it
                        }.ifBlank { "(no output)" })
                    } else {
                        ToolResult.Error(
                            message = "命令退出码 $exitCode",
                            exitCode = exitCode,
                            stderr = stderr.trim().let {
                                if (it.length > MAX_STDERR) it.take(MAX_STDERR / 2) +
                                    "\n[...已截断...]\n" + it.takeLast(MAX_STDERR / 2)
                                else it
                            }.ifBlank { "(无 stderr 输出)" }
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            killProcessGroup(process)
            return@executeViaSh ToolResult.Error("命令超时 (${TIMEOUT_SECONDS}s)，已强杀进程组")
        } catch (e: Exception) {
            killProcessGroup(process)
            return@executeViaSh ToolResult.Error("执行异常: ${e.message}")
        }
    }

    /** Kill process group to prevent orphan child processes. */
    private fun killProcessGroup(process: Process?) {
        if (process == null) return
        try {
            val pid = getPid(process)
            if (pid > 0) {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid"))
                    .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            process.destroyForcibly()
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }

    private fun getPid(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
