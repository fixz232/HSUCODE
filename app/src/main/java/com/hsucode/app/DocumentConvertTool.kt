package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Converts workspace documents through native exporters first, then existing Linux tools when available. */
class DocumentConvertTool : Tool {
    override val name = "document_convert"
    override val description = "转换工作区文档：Markdown/文本可直接转 PPTX、DOCX、PDF、HTML；DOCX/PPTX 可提取为 Markdown。其它 Office/PDF 转换需要已准备好的 Linux Pandoc、LibreOffice 或 pdftotext。不会自动安装软件。"
    override val parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("input_path", JSONObject().apply { put("type", "string") })
            put("output_format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("pptx", "docx", "pdf", "html", "md", "txt"))) })
            put("title", JSONObject().apply { put("type", "string"); put("description", "可选导出标题") })
        })
        put("required", JSONArray(listOf("input_path", "output_format")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val root = WorkspaceFileOps.root(WorkspaceContext.workspaceRoot).getOrThrow()
            val source = WorkspaceFileOps.resolve(root, params["input_path"].orEmpty()).getOrThrow()
            require(source.isFile) { "输入文件不存在" }
            val targetFormat = params["output_format"].orEmpty().lowercase()
            val title = params["title"].orEmpty().ifBlank { source.nameWithoutExtension }
            val result = convert(root, source, targetFormat, title)
            val publication = WorkspaceContext.publishChatFileResult(result, mimeForExtension(result.extension))
            val published = publication?.location?.let { "；已同步到手机 $it" }.orEmpty()
            ToolResult.Success("已转换：${result.absolutePath}$published")
        }.getOrElse { ToolResult.Error("文档转换失败：${it.message ?: "未知错误"}") }
    }

    private suspend fun convert(root: File, source: File, outputFormat: String, title: String): File {
        val input = source.extension.lowercase()
        if (input in setOf("md", "markdown", "txt") && outputFormat in setOf("pptx", "docx", "pdf", "html")) {
            val format = when (outputFormat) {
                "pptx" -> DocumentExport.Format.PPTX
                "docx" -> DocumentExport.Format.DOCX
                "pdf" -> DocumentExport.Format.PDF
                else -> DocumentExport.Format.HTML
            }
            return DocumentExport.create(root, format, title, source.readText(Charsets.UTF_8), fileName = source.nameWithoutExtension).file
        }
        if (input in setOf("docx", "pptx") && outputFormat in setOf("md", "txt")) {
            val output = WorkspaceFileOps.uniqueChild(File(root, "exports").apply { mkdirs() }, "${source.nameWithoutExtension}.$outputFormat")
            output.writeText(DocumentTextExtractor.extract(source, 100_000).text, Charsets.UTF_8)
            return output
        }
        if (input == "pdf" && outputFormat in setOf("md", "txt")) {
            return runExternal(root, source, "pdftotext", outputFormat)
        }
        if (outputFormat == "pdf" && input in setOf("docx", "pptx", "odt", "odp")) {
            return runExternal(root, source, "libreoffice", outputFormat)
        }
        if (WorkspaceRuntime.hasLinux()) return runExternal(root, source, "pandoc", outputFormat)
        error("此转换需要 Linux 工作区和 Pandoc/LibreOffice；请先在 Linux 与工作区完成环境配置")
    }

    private suspend fun runExternal(root: File, source: File, command: String, outputFormat: String): File {
        require(WorkspaceRuntime.hasLinux()) { "此转换需要已启动的 Linux 工作区" }
        val exports = File(root, "exports").apply { require(mkdirs() || isDirectory) { "无法创建 exports 目录" } }
        val output = WorkspaceFileOps.uniqueChild(exports, "${source.nameWithoutExtension}.$outputFormat")
        val sourceArg = shellQuote(source.absolutePath)
        val outputArg = shellQuote(output.absolutePath)
        val shell = when (command) {
            "pdftotext" -> "command -v pdftotext >/dev/null 2>&1 && pdftotext $sourceArg $outputArg"
            "libreoffice" -> "command -v libreoffice >/dev/null 2>&1 && libreoffice --headless --convert-to pdf --outdir ${shellQuote(exports.absolutePath)} $sourceArg"
            else -> "command -v pandoc >/dev/null 2>&1 && pandoc $sourceArg -o $outputArg"
        }
        val lines = mutableListOf<String>()
        val result = WorkspaceRuntime.runStreaming(shell) { line -> if (lines.size < 12) lines += line }
        val libreOfficeOutput = File(exports, "${source.nameWithoutExtension}.pdf")
        if (command == "libreoffice" && !output.isFile && libreOfficeOutput.isFile) {
            require(libreOfficeOutput.renameTo(output) || output.isFile) { "无法整理 LibreOffice 输出文件" }
        }
        require(result.success && output.isFile && output.length() > 0L) {
            val detail = lines.joinToString(" ").take(300)
            if (command == "libreoffice") "LibreOffice 未成功完成转换${if (detail.isBlank()) "" else "：$detail"}" else "$command 不可用或转换失败${if (detail.isBlank()) "" else "：$detail"}"
        }
        return output
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\\"'\\\"'") + "'"
}

private fun mimeForExtension(extension: String): String = when (extension.lowercase()) {
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pdf" -> "application/pdf"
    "html", "htm" -> "text/html"
    "md" -> "text/markdown"
    else -> "text/plain"
}
