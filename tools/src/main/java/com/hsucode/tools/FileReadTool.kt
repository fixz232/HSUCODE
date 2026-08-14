package com.hsucode.tools

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads a file by path with optional line range.
 *
 * Path is resolved against the active conversation workspace.
 * Relative paths (starting without /) are treated as relative to workspace root.
 * Absolute paths must be within the workspace subtree (no traversal escape).
 */
class FileReadTool : Tool {

    override val name = "file_read"
    override val description = "Read a file by path. Optionally specify startLine/endLine (1-based) for partial read. " +
            "Paths are relative to the active conversation workspace unless absolute and within that workspace."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path (relative to workspace or absolute within workspace)")
            })
            put("startLine", JSONObject().apply {
                put("type", "integer")
                put("description", "Start line number (1-based), default 1")
            })
            put("endLine", JSONObject().apply {
                put("type", "integer")
                put("description", "End line number (inclusive), omit to read to end")
            })
        })
        put("required", JSONArray().apply { put("path") })
    }

    companion object {
        private const val MAX_OUTPUT = 4000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("路径不在工作区内: $path")

        val file = File(safePath)
        if (!file.exists()) return@withContext ToolResult.Error("文件不存在: $path")
        if (!file.isFile) return@withContext ToolResult.Error("不是文件: $path")
        if (!file.canRead()) return@withContext ToolResult.Error("无读取权限: $path")

        try {
            val startLine = params["startLine"]?.toIntOrNull() ?: 1
            val endLine = params["endLine"]?.toIntOrNull()
            if (startLine < 1) return@withContext ToolResult.Error("startLine 必须从 1 开始")
            if (endLine != null && endLine < startLine) {
                return@withContext ToolResult.Error("endLine 不能小于 startLine")
            }

            // Do not materialize every line: generated logs can be hundreds of MB.
            val output = StringBuilder()
            var lineNumber = 0
            var truncated = false
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (lineNumber < startLine) continue
                    if (endLine != null && lineNumber > endLine) break
                    val separator = if (output.isEmpty()) 0 else 1
                    if (output.length + separator + line.length > MAX_OUTPUT) {
                        truncated = true
                        break
                    }
                    if (separator != 0) output.append('\n')
                    output.append(line)
                }
            }
            if (lineNumber < startLine) {
                return@withContext ToolResult.Error("起始行 $startLine 超出文件总行数 $lineNumber")
            }
            if (truncated) output.append("\n[...输出已截断，请缩小行范围...]")
            ToolResult.Success(output.toString().ifEmpty { "(空行)" })
        } catch (e: Exception) {
            ToolResult.Error("读取异常: ${e.message}")
        }
    }
}
