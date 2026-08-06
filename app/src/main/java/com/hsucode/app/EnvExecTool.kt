package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * `env_exec` —— 让 AI 在工作区内执行命令并实时镜像到内置终端。
 * 已安装时优先使用无需 Root 的 PRoot Ubuntu；否则使用应用私有目录中的 Android Shell 工作区。
 */
class EnvExecTool(private val terminal: TerminalState) : Tool {
    override val name = "env_exec"
    override val description =
        "在 HSUCODE 工作区执行一条 shell 命令并返回输出。免 Root Ubuntu 已部署时可使用 apt/node/python 等；" +
        "否则运行在 Android Shell 工作区。命令与输出会实时显示在内置终端里。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要在 HSUCODE 工作区执行的 shell 命令")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    override fun isAvailable(): Boolean = UserWorkspaceShell.isReady()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val cmd = params["command"]?.trim().orEmpty()
        if (cmd.isEmpty()) return ToolResult.Error("缺少 command 参数")
        // When the real Termux PTY is alive, use it directly so the command, cwd,
        // environment variables and interactive state are exactly what the user sees.
        val ptyResult = TermuxPtySessionManager.execute(cmd)
        val res = ptyResult ?: terminal.runForAgent(cmd)
        val text = res.stdout.trim().ifBlank { res.stderr.trim() }.ifBlank { "(无输出)" }
        return if (res.exitCode == 0) ToolResult.Success(text)
        else ToolResult.Error("退出码 ${res.exitCode}\n$text", res.exitCode, res.stderr.ifBlank { null })
    }
}
