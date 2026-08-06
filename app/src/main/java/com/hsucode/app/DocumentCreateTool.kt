package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Creates portable PPTX, DOCX, PDF or HTML deliverables directly in the workspace. */
class DocumentCreateTool : Tool {
    override val name = "document_create"
    override val description =
        "把内容整理成可交付文件并保存到工作区 exports 目录。format 可选 pptx、docx、pdf、html；" +
            "PPTX 用换行 + --- 分隔幻灯片，也可传 slides 数组。返回生成文件路径。"

    override val parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("format", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("pptx", "docx", "pdf", "html")))
                put("description", "导出格式")
            })
            put("title", JSONObject().apply { put("type", "string"); put("description", "文档或演示标题") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "正文；PPTX 可用 --- 分隔幻灯片") })
            put("slides", JSONObject().apply { put("type", "array"); put("items", JSONObject().apply { put("type", "string") }); put("description", "可选的 PPTX 幻灯片正文数组") })
        })
        put("required", JSONArray(listOf("format", "title", "content")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val format = when (params["format"]?.trim()?.lowercase()) {
                "pptx" -> DocumentExport.Format.PPTX
                "docx" -> DocumentExport.Format.DOCX
                "pdf" -> DocumentExport.Format.PDF
                "html" -> DocumentExport.Format.HTML
                else -> error("format 必须是 pptx、docx、pdf 或 html")
            }
            val title = params["title"]?.trim().orEmpty()
            val content = params["content"].orEmpty()
            val slides = parseSlides(params["slides"])
            val root = WorkspaceFileOps.root(WorkspaceContext.workspaceRoot).getOrThrow()
            val created = DocumentExport.create(root, format, title, content, slides)
            ToolResult.Success("已生成 ${created.format.name.lowercase()}：${created.file.absolutePath}")
        }.getOrElse { ToolResult.Error("文档生成失败：${it.message ?: "未知错误"}") }
    }

    private fun parseSlides(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) { repeat(array.length()) { add(array.optString(it)) } }.filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }
}
