package com.hsucode.tools

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Creates or overwrites a file within the workspace.
 *
 * Path must resolve within the active conversation workspace.
 * Parent directories are created automatically.
 */
class FileWriteTool : Tool {

    override val name = "file_write"
    override val description = "Create or overwrite a file in the active conversation workspace. " +
            "Use a relative path; do not use a fixed legacy directory. Parent directories are created automatically. " +
            "The result is also copied to the phone's Download/HSUCODE/date folder."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path to write (relative to the current workspace, or an absolute Android path)")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "Content to write into the file")
            })
        })
        put("required", JSONArray().apply { put("path"); put("content") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val content = params["content"] ?: return@withContext ToolResult.Error("缺少 content 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("无法解析路径: $path")
        // 不让 AI 改 App 自己的运行时数据 —— 动了 databases/ 下次启动就打不开库,
        // 用户的会话、身份卡、供应商配置、记忆全没。见 SelfProtect。
        SelfProtect.refuse(safePath)?.let { return@withContext ToolResult.Error(it) }

        try {
            val file = File(safePath)
            // Reject if path points to an existing directory
            if (file.exists() && file.isDirectory) {
                return@withContext ToolResult.Error("路径是目录，不能作为文件写入: $path")
            }
            SafeWorkspaceFiles.writeTextAtomically(file, content)
            val published = WorkspaceContext.publishChatFile(file)
            val destination = published?.let { "；已同步到手机 $it" }
                ?: "；下载目录同步失败，工作区副本已保留"
            ToolResult.Success("已写入 ${file.length()} 字节 → $path$destination")
        } catch (e: Exception) {
            ToolResult.Error("写入异常: ${e.message}")
        }
    }
}
