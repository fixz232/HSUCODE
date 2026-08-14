package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import com.hsucode.tools.SelfProtect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicit system-shell tool backed by Shizuku. */
class ShizukuExecTool(private val enabled: () -> Boolean) : Tool {
    override val name = "shizuku_exec"
    override val description = "Execute a command through the user-authorized Shizuku shell. " +
        "Requires Settings > Shizuku enhanced channel and explicit Shizuku permission."
    override val parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply { put("type", "string"); put("description", "Shell command") })
        })
        put("required", JSONArray().apply { put("command") })
    }

    override fun isAvailable(): Boolean = enabled()

    override fun unavailableReason(): String = "请先在设置中开启 Shizuku 增强通道"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext ToolResult.Error("请先在设置中开启 Shizuku 增强通道")
        val command = params["command"]?.trim().orEmpty()
        if (command.isBlank()) return@withContext ToolResult.Error("缺少 command 参数")
        SelfProtect.refuseCommand(command)?.let { return@withContext ToolResult.Error(it) }
        runCatching { ShizukuManager.execute(command) }.fold(
            onSuccess = { result ->
                if (result.exitCode == 0) ToolResult.Success(result.stdout.ifBlank { "(no output)" })
                else ToolResult.Error("Shizuku 退出码 ${result.exitCode}", result.exitCode, result.stderr.ifBlank { result.stdout })
            },
            onFailure = { ToolResult.Error("Shizuku 执行失败：${it.message ?: "未知错误"}") }
        )
    }
}
