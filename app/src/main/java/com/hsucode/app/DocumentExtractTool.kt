package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Extracts readable text from workspace documents without loading binary files into chat context. */
class DocumentExtractTool : Tool {
    override val name = "document_extract"
    override val description = "从工作区中的 DOCX、PPTX、HTML、Markdown 或文本文件提取可读内容。PDF 需要已配置 pdftotext 转换器。"
    override val parametersSchema = org.json.JSONObject().apply {
        put("type", "object")
        put("properties", org.json.JSONObject().apply {
            put("path", org.json.JSONObject().apply { put("type", "string"); put("description", "工作区内文件路径") })
            put("max_chars", org.json.JSONObject().apply { put("type", "integer"); put("description", "最大返回字符数，默认 24000，最高 100000") })
        })
        put("required", org.json.JSONArray().put("path"))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val root = WorkspaceFileOps.root(WorkspaceContext.workspaceRoot).getOrThrow()
            val path = params["path"].orEmpty()
            val file = WorkspaceFileOps.resolve(root, path).getOrThrow()
            val limit = params["max_chars"]?.toIntOrNull()?.coerceIn(500, 100_000) ?: 24_000
            val extracted = DocumentTextExtractor.extract(file, limit)
            val tail = if (extracted.truncated) "\n\n[内容已截取；可调大 max_chars 或分段处理]" else ""
            ToolResult.Success("已提取 ${file.name}：\n${extracted.text}$tail")
        }.getOrElse { ToolResult.Error("文档提取失败：${it.message ?: "未知错误"}") }
    }
}

data class ExtractedDocument(val text: String, val truncated: Boolean)

object DocumentTextExtractor {
    private const val MAX_SOURCE_BYTES = 24L * 1024L * 1024L

    fun extract(file: File, maxChars: Int): ExtractedDocument {
        require(file.isFile) { "文件不存在" }
        require(file.length() <= MAX_SOURCE_BYTES) { "文件超过 24 MB，暂不支持直接提取" }
        return when (file.extension.lowercase()) {
            "docx" -> ExtractedDocument(extractDocx(file), false).limit(maxChars)
            "pptx" -> ExtractedDocument(extractPptx(file), false).limit(maxChars)
            "html", "htm" -> ExtractedDocument(stripHtml(file.readText(Charsets.UTF_8)), false).limit(maxChars)
            "md", "markdown", "txt", "csv", "tsv", "json", "xml", "yaml", "yml", "log" ->
                ExtractedDocument(file.readText(Charsets.UTF_8), false).limit(maxChars)
            "pdf" -> error("PDF 需使用 document_convert 通过已配置的 pdftotext 转为文本；Android 原生预览不提供可靠文本提取")
            else -> error("暂不支持提取 .${file.extension} 文件")
        }
    }

    private fun extractDocx(file: File): String = ZipFile(file).use { zip ->
        val xml = readEntry(zip, "word/document.xml")
        val document = parseXml(xml)
        document.getElementsByTagNameNS("*", "p").let { paragraphs ->
            buildString {
                repeat(paragraphs.length) { index ->
                    val paragraph = paragraphs.item(index) as? Element ?: return@repeat
                    val text = paragraph.getElementsByTagNameNS("*", "t").let { nodes ->
                        buildString { repeat(nodes.length) { append(nodes.item(it).textContent.orEmpty()) } }
                    }
                    if (text.isNotBlank()) appendLine(text)
                }
            }.trim()
        }
    }

    private fun extractPptx(file: File): String = ZipFile(file).use { zip ->
        val slideNames = zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
        require(slideNames.isNotEmpty()) { "PPTX 中没有可读取的投影片" }
        slideNames.mapIndexed { index, name ->
            val document = parseXml(readEntry(zip, name))
            val text = document.getElementsByTagNameNS("*", "t").let { nodes ->
                buildString { repeat(nodes.length) { append(nodes.item(it).textContent.orEmpty()).append(' ') } }.trim()
            }
            "第 ${index + 1} 页\n$text"
        }.joinToString("\n\n")
    }

    private fun readEntry(zip: ZipFile, name: String): ByteArray {
        val entry = requireNotNull(zip.getEntry(name)) { "文档结构不完整：缺少 $name" }
        require(entry.size <= 8L * 1024L * 1024L || entry.size < 0) { "文档部件过大" }
        return zip.getInputStream(entry).use { input ->
            input.readBytes().also { require(it.size <= 8 * 1024 * 1024) { "文档部件过大" } }
        }
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().let { factory ->
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
    }

    private fun stripHtml(source: String): String = source
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t]+\n"), "\n")
        .trim()

    private fun ExtractedDocument.limit(maxChars: Int): ExtractedDocument =
        if (text.length <= maxChars) this else ExtractedDocument(text.take(maxChars), true)
}
