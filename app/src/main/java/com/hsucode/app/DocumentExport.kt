package com.hsucode.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Dependency-free document deliverables for the workspace and Agent tools. */
object DocumentExport {
    enum class Format { PPTX, DOCX, PDF, HTML }

    data class CreatedFile(val file: File, val format: Format)

    fun create(
        root: File,
        format: Format,
        title: String,
        content: String,
        slides: List<String> = emptyList(),
        fileName: String? = null
    ): CreatedFile = create(
        root = root,
        format = format,
        project = DocumentProject.fromMarkdown(title, content, slides, fileName ?: title),
        fileName = fileName
    )

    /** Exports a structured project. The legacy string API above remains for existing tools. */
    fun create(
        root: File,
        format: Format,
        project: DocumentProject,
        fileName: String? = null
    ): CreatedFile {
        project.validate()
        val exportDir = File(root, "exports").apply { require(mkdirs() || isDirectory) { "无法创建 exports 目录" } }
        val safeTitle = safeFileName(fileName?.takeIf { it.isNotBlank() } ?: project.fileName)
            .replace(Regex("\\.(pptx|docx|pdf|html)$", RegexOption.IGNORE_CASE), "")
            .ifBlank { "document" }
        val extension = when (format) {
            Format.PPTX -> ".pptx"
            Format.DOCX -> ".docx"
            Format.PDF -> ".pdf"
            Format.HTML -> ".html"
        }
        val target = WorkspaceFileOps.uniqueChild(exportDir, safeTitle + extension)
        val temporary = File(exportDir, ".${safeTitle}-${UUID.randomUUID()}.partial")
        try {
            val content = project.sourceMarkdown.ifBlank { projectMarkdown(project) }
            when (format) {
                Format.PPTX -> writePptx(temporary, project, root)
                Format.DOCX -> writeDocx(temporary, project.title.trim(), content, project.author)
                Format.PDF -> writePdf(temporary, project, root)
                Format.HTML -> writeHtml(temporary, project, root)
            }
            verify(temporary, format)
            if (format == Format.PPTX) verifyPptxQuality(temporary, project)
            commit(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return CreatedFile(target, format)
    }

    private fun projectMarkdown(project: DocumentProject): String = project.effectiveSlides().joinToString("\n\n---\n\n") { slide ->
        listOf(slide.title, slide.body).filter(String::isNotBlank).joinToString("\n\n")
    }

    fun splitSlides(content: String): List<String> = content
        // Treat a divider at the beginning/end as a divider too. The previous
        // expression required a newline before `---`, leaving a literal `---`
        // slide when content started with an empty section.
        .split(Regex("(?m)^[ \\t]*---+[ \\t]*(?:\\r?\\n|$)"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty { listOf(content.trim()) }

    private fun writeHtml(target: File, project: DocumentProject, root: File) {
        val theme = project.theme
        val backgroundImage = htmlAsset(root, project.brief.backgroundPath)
        val logoImage = htmlAsset(root, project.brief.logoPath)
        val slides = project.presentationSlides().joinToString("\n") { slide -> htmlSlide(project, slide, root, logoImage) }
        target.writeText(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${xmlEscape(project.title)}</title><style>" +
                "*{box-sizing:border-box}body{margin:0;padding:28px;background:#eef2ef;font:16px/1.55 sans-serif;color:#${theme.foreground}}" +
                ".deck{display:grid;gap:24px;max-width:1200px;margin:auto}.slide{position:relative;overflow:hidden;aspect-ratio:${project.aspectRatio.width}/${project.aspectRatio.height};padding:clamp(24px,5vw,72px);background:#${theme.background};color:#${theme.foreground};box-shadow:0 8px 28px #0002}.slide.block:before{content:'';position:absolute;top:0;left:0;right:0;height:12px;background:#${theme.accent}}.slide.duotone{background:linear-gradient(135deg,#${theme.background},#${theme.muted})}.slide.bg{background-image:linear-gradient(#${theme.background}e8,#${theme.background}e8),url('$backgroundImage');background-size:cover;background-position:center}.logo{position:absolute;top:24px;right:28px;width:48px;height:48px;object-fit:contain}.title{position:relative;margin:0 0 28px;font-size:clamp(26px,4.2vw,54px);line-height:1.16}.body{position:relative;white-space:pre-line;font-size:clamp(14px,1.7vw,22px);max-width:100%}.two{display:grid;grid-template-columns:1fr 1fr;gap:7%;height:70%}.media{position:absolute;right:7%;bottom:12%;width:40%;max-height:58%;object-fit:cover}.with-media .body{max-width:48%}.footer{position:absolute;right:7%;bottom:5%;font-size:13px;color:#${theme.accent}}.cover,.closing,.section{text-align:center;display:flex;flex-direction:column;justify-content:center}.cover .title,.closing .title,.section .title{font-size:clamp(36px,6vw,76px)}.table{width:100%;border-collapse:collapse;font-size:clamp(12px,1.4vw,18px)}.table th,.table td{border:1px solid #${theme.muted};padding:9px;text-align:left}.table th{background:#${theme.accent};color:#${theme.background}}@media print{body{padding:0;background:#fff}.deck{gap:0}.slide{box-shadow:none;break-after:page;page-break-after:always;width:100vw}}</style>" +
                "<body><main class=\"deck\">$slides</main></body></html>",
            Charsets.UTF_8
        )
    }

    private fun htmlSlide(project: DocumentProject, slide: SlideSpec, root: File, logo: String): String {
        val className = buildList {
            add("slide")
            add(project.template.backgroundStyle.name.lowercase())
            if (project.brief.backgroundPath.isNotBlank()) add("bg")
            when (slide.layout) {
                SlideLayout.COVER -> add("cover")
                SlideLayout.CLOSING -> add("closing")
                SlideLayout.SECTION -> add("section")
                else -> Unit
            }
            if (slide.layout in setOf(SlideLayout.IMAGE_TEXT, SlideLayout.DATA) && (slide.imagePath.isNotBlank() || slide.chart != null)) add("with-media")
        }.joinToString(" ")
        val contentAsset = when {
            slide.imagePath.isNotBlank() -> htmlAsset(root, slide.imagePath)
            slide.chart != null -> "data:image/png;base64," + Base64.getEncoder().encodeToString(chartPng(slide.chart, slide.chartType, project.theme))
            else -> ""
        }
        val body = when (slide.layout) {
            SlideLayout.TABLE -> htmlTable(slide.body)
            SlideLayout.TWO_COLUMN, SlideLayout.COMPARISON -> {
                val columns = slide.body.split(Regex("\\n[ \\t]*---+[ \\t]*\\n"), limit = 2)
                "<div class=\"two\"><div class=\"body\">${htmlInline(columns.getOrElse(0) { slide.body }).replace("\n", "<br>")}</div><div class=\"body\">${htmlInline(columns.getOrElse(1) { "" }).replace("\n", "<br>")}</div></div>"
            }
            else -> "<div class=\"body\">${htmlInline(slide.body).replace("\n", "<br>")}</div>"
        }
        val media = contentAsset.takeIf { it.isNotBlank() }?.let { "<img class=\"media\" src=\"$it\" alt=\"${xmlEscape(slide.title)}\">" }.orEmpty()
        val logoTag = logo.takeIf { it.isNotBlank() }?.let { "<img class=\"logo\" src=\"$it\" alt=\"品牌标识\">" }.orEmpty()
        val footer = if (slide.layout in setOf(SlideLayout.COVER, SlideLayout.CLOSING)) "" else "<div class=\"footer\">${xmlEscape(project.brief.presenter.ifBlank { "HSUCODE" })} · ${xmlEscape(slide.title)}</div>"
        val titleTag = if (slide.layout in setOf(SlideLayout.COVER, SlideLayout.CLOSING, SlideLayout.SECTION)) "h1" else "h2"
        return "<section class=\"$className\">$logoTag<$titleTag class=\"title\">${htmlInline(slide.title)}</$titleTag>$body$media$footer</section>"
    }

    private fun htmlTable(body: String): String {
        val rows = PptDeckComposer.parseTable(body)
        if (rows.isEmpty()) return "<div class=\"body\">${htmlInline(body).replace("\n", "<br>")}</div>"
        return buildString {
            append("<table class=\"table\">")
            rows.forEachIndexed { index, row ->
                append(if (index == 0) "<thead><tr>" else "<tr>")
                row.forEach { cell -> append(if (index == 0) "<th>${htmlInline(cell)}</th>" else "<td>${htmlInline(cell)}</td>") }
                append(if (index == 0) "</tr></thead><tbody>" else "</tr>")
            }
            if (rows.size > 1) append("</tbody>")
            append("</table>")
        }
    }

    private fun htmlAsset(root: File, path: String): String {
        if (path.isBlank()) return ""
        return runCatching {
            val file = WorkspaceFileOps.resolve(root, path).getOrThrow()
            val extension = file.extension.lowercase()
            require(file.isFile && extension in setOf("png", "jpg", "jpeg") && file.length() <= 12L * 1024L * 1024L)
            val mime = if (extension == "png") "image/png" else "image/jpeg"
            "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }.getOrDefault("")
    }

    private fun writePdf(target: File, project: DocumentProject, root: File) {
        val document = PdfDocument()
        try {
            val slides = project.presentationSlides()
            val width = 842
            val height = (width.toDouble() * project.aspectRatio.height / project.aspectRatio.width).toInt().coerceAtLeast(474)
            slides.forEachIndexed { index, slide ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                drawPdfSlide(page.canvas, width, height, project, slide, root, index + 1, slides.size)
                document.finishPage(page)
            }
            FileOutputStream(target).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun drawPdfSlide(canvas: Canvas, width: Int, height: Int, project: DocumentProject, slide: SlideSpec, root: File, index: Int, total: Int) {
        val theme = project.theme
        canvas.drawColor(Color.parseColor("#${theme.background}"))
        val background = loadPdfBitmap(root, project.brief.backgroundPath)
        background?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), null); it.recycle() }
        val shade = Paint().apply { color = Color.parseColor("#${theme.background}"); alpha = if (background != null) 225 else 255 }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        val accent = Color.parseColor("#${theme.accent}")
        val foreground = Color.parseColor("#${theme.foreground}")
        if (project.template.backgroundStyle == BackgroundStyle.BLOCK) canvas.drawRect(0f, 0f, width.toFloat(), 10f, Paint().apply { color = accent })
        if (project.template.backgroundStyle == BackgroundStyle.DUOTONE) canvas.drawRect(0f, (height * .92f), width.toFloat(), height.toFloat(), Paint().apply { color = accent })
        val margin = 48f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = if (slide.layout in setOf(SlideLayout.COVER, SlideLayout.CLOSING, SlideLayout.SECTION)) 42f else 28f; typeface = Typeface.create(project.template.headingTypeface, Typeface.BOLD) }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = 18f; typeface = Typeface.create(project.template.bodyTypeface, Typeface.NORMAL) }
        val titleY = if (slide.layout in setOf(SlideLayout.COVER, SlideLayout.CLOSING, SlideLayout.SECTION)) height * .38f else margin + 30f
        wrap(slide.title, titlePaint, width - margin * 2).take(3).forEachIndexed { line, text -> canvas.drawText(text, margin, titleY + line * (titlePaint.textSize + 8f), titlePaint) }
        val hasMedia = slide.imagePath.isNotBlank() || slide.chart != null
        val bodyWidth = if (hasMedia && slide.layout in setOf(SlideLayout.IMAGE_TEXT, SlideLayout.DATA)) width * .44f else width - margin * 2
        val bodyStart = if (slide.layout in setOf(SlideLayout.COVER, SlideLayout.CLOSING, SlideLayout.SECTION)) titleY + titlePaint.textSize * 2.2f else margin + 92f
        if (slide.layout == SlideLayout.TABLE) {
            drawPdfTable(canvas, slide.body, margin, bodyStart, width - margin * 2, height - bodyStart - 48f, bodyPaint, theme)
        } else {
            val text = when (slide.layout) {
                SlideLayout.QUOTE -> "“${slide.body.ifBlank { slide.title }}”"
                else -> slide.body
            }
            wrap(text, bodyPaint, bodyWidth).take(16).forEachIndexed { line, value -> canvas.drawText(value, margin, bodyStart + line * 26f, bodyPaint) }
        }
        val contentImage = when {
            slide.imagePath.isNotBlank() -> loadPdfBitmap(root, slide.imagePath)
            slide.chart != null -> chartPng(slide.chart, slide.chartType, theme).let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> null
        }
        contentImage?.let {
            val rect = Rect((width * .55f).toInt(), (height * .28f).toInt(), width - margin.toInt(), (height * .82f).toInt())
            canvas.drawBitmap(it, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
            it.recycle()
        }
        loadPdfBitmap(root, project.brief.logoPath)?.let {
            canvas.drawBitmap(it, null, Rect(width - 88, 28, width - 40, 76), Paint(Paint.ANTI_ALIAS_FLAG))
            it.recycle()
        }
        if (index != 1 && index != total) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 12f }.also { footer ->
                canvas.drawText("${project.brief.presenter.ifBlank { "HSUCODE" }} · $index / $total", width - 180f, height - 24f, footer)
            }
        }
    }

    private fun loadPdfBitmap(root: File, path: String): Bitmap? = runCatching {
        if (path.isBlank()) return null
        val file = WorkspaceFileOps.resolve(root, path).getOrThrow()
        require(file.isFile && file.extension.lowercase() in setOf("png", "jpg", "jpeg") && file.length() <= 12L * 1024L * 1024L)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (sample < 64 && bounds.outWidth / sample * (bounds.outHeight / sample) > 2_000_000) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun drawPdfTable(canvas: Canvas, body: String, left: Float, top: Float, width: Float, height: Float, textPaint: Paint, theme: DocumentTheme) {
        val rows = PptDeckComposer.parseTable(body).take(7).ifEmpty { listOf(listOf("项目", "内容"), listOf("示例", "请填写表格数据")) }
        val columns = rows.maxOf { it.size }.coerceAtMost(4)
        val cellWidth = width / columns
        val cellHeight = (height / rows.size).coerceAtMost(48f)
        rows.forEachIndexed { rowIndex, row ->
            repeat(columns) { col ->
                val x = left + col * cellWidth
                val y = top + rowIndex * cellHeight
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(if (rowIndex == 0) "#${theme.accent}" else "#${theme.muted}") }
                canvas.drawRect(x, y, x + cellWidth - 1f, y + cellHeight - 1f, paint)
                val labelPaint = Paint(textPaint).apply { color = Color.parseColor(if (rowIndex == 0) "#${theme.background}" else "#${theme.foreground}"); textSize = 13f }
                wrap(row.getOrElse(col) { "" }, labelPaint, cellWidth - 12f).take(2).forEachIndexed { line, text -> canvas.drawText(text, x + 6f, y + 18f + line * 14f, labelPaint) }
            }
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var count = paint.breakText(text.substring(start), true, maxWidth, null)
            if (count <= 0) count = 1
            var end = (start + count).coerceAtMost(text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end - 1)
                if (lastSpace >= start) end = lastSpace + 1
            }
            lines += text.substring(start, end).trimEnd()
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return lines
    }

    private fun writeDocx(target: File, title: String, content: String, author: String) {
        val paragraphs = buildString {
            append(paragraph(title, "Title"))
            parseMarkdownBlocks(content).forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> append(paragraph(block.content, "Heading${block.level.coerceAtMost(3)}"))
                    is MarkdownBlock.TextSpan -> append(paragraph(block.content))
                    is MarkdownBlock.ListItem -> append(paragraph(block.content, "List", prefix = "${"  ".repeat(block.indent)}${block.marker} "))
                    is MarkdownBlock.Quote -> append(paragraph(block.content, "Quote"))
                    is MarkdownBlock.CodeBlock -> append(paragraph(block.content, "Code", parseInline = false))
                    is MarkdownBlock.Divider -> append(paragraph("", "Divider", parseInline = false))
                    is MarkdownBlock.Table -> append(wordTable(block))
                }
            }
        }
        zip(target, mapOf(
            "[Content_Types].xml" to contentTypes("word/document.xml"),
            "_rels/.rels" to rootRels("word/document.xml"),
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$paragraphs<w:sectPr><w:headerReference w:type="default" r:id="rId2"/><w:footerReference w:type="default" r:id="rId3"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""",
            "word/header1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:rPr><w:color w:val="4F5F59"/><w:sz w:val="18"/></w:rPr><w:t>${xmlEscape(author.ifBlank { "HSUCODE" })}</w:t></w:r></w:p></w:hdr>""",
            "word/footer1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:color w:val="4F5F59"/><w:sz w:val="18"/></w:rPr><w:t>第 </w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple><w:r><w:rPr><w:color w:val="4F5F59"/><w:sz w:val="18"/></w:rPr><w:t> 页</w:t></w:r></w:p></w:ftr>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="22"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="Heading 1"/><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="Heading 2"/><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="Heading 3"/><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="List"><w:name w:val="List"/><w:pPr><w:spacing w:after="80"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:pPr><w:ind w:left="480"/><w:spacing w:after="120"/></w:pPr><w:rPr><w:i/><w:color w:val="4F5F59"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:pPr><w:shd w:val="clear" w:fill="F1F4F2"/></w:pPr><w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/><w:sz w:val="18"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Divider"><w:name w:val="Divider"/><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="4" w:space="1" w:color="D9E0DC"/></w:pBdr></w:pPr></w:style></w:styles>"""
        ))
    }

    private fun paragraph(text: String, style: String? = null, prefix: String = "", parseInline: Boolean = true): String {
        val pPr = style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" }.orEmpty()
        val runs = if (parseInline) wordRuns(prefix + text) else wordRun(prefix + text)
        return "<w:p>$pPr$runs</w:p>"
    }

    private fun safeFileName(value: String): String = value.trim()
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .trim('.', '_', '-')
        .take(80)
        .ifBlank { "document" }

    private fun commit(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath())
        }
    }

    private fun verify(file: File, format: Format) {
        require(file.isFile && file.length() > 0L) { "生成文件为空" }
        when (format) {
            Format.DOCX -> verifyArchive(file, setOf("[Content_Types].xml", "word/document.xml", "word/styles.xml"))
            Format.PPTX -> verifyArchive(file, setOf("[Content_Types].xml", "ppt/presentation.xml", "ppt/slides/slide1.xml"))
            Format.PDF -> file.inputStream().use { input ->
                val header = ByteArray(5)
                require(input.read(header) == header.size && String(header, Charsets.US_ASCII) == "%PDF-") { "PDF 文件校验失败" }
            }.also { verifyPdfRenderable(file) }
            Format.HTML -> require(file.readText(Charsets.UTF_8).startsWith("<!doctype html>")) { "HTML 文件校验失败" }
        }
    }

    private fun verifyArchive(file: File, requiredEntries: Set<String>) {
        ZipFile(file).use { archive ->
            requiredEntries.forEach { name ->
                requireNotNull(archive.getEntry(name)) { "文档结构不完整：缺少 $name" }
            }
        }
    }

    /** Verifies that Android can parse at least the first generated PDF page, not just its header. */
    private fun verifyPdfRenderable(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF 不含可渲染页面" }
                renderer.openPage(0).use { page ->
                    require(page.width > 0 && page.height > 0) { "PDF 页面尺寸无效" }
                }
            }
        }
    }

    /** Checks the generated package against the intended structured deck before publication. */
    private fun verifyPptxQuality(file: File, project: DocumentProject) {
        val slides = project.presentationSlides()
        ZipFile(file).use { archive ->
            slides.forEachIndexed { index, slide ->
                val slideXml = archive.getInputStream(requireNotNull(archive.getEntry("ppt/slides/slide${index + 1}.xml"))).bufferedReader().readText()
                require(slide.title.isBlank() || slideXml.contains(xmlEscape(slide.title.take(80)))) { "第 ${index + 1} 页标题未写入 PPTX" }
                if (slide.chart != null || slide.imagePath.isNotBlank()) {
                    val rels = archive.getInputStream(requireNotNull(archive.getEntry("ppt/slides/_rels/slide${index + 1}.xml.rels"))).bufferedReader().readText()
                    require(rels.contains("/image")) { "第 ${index + 1} 页视觉素材未写入 PPTX" }
                }
                if (slide.notes.isNotBlank()) {
                    val note = archive.getInputStream(requireNotNull(archive.getEntry("ppt/notesSlides/notesSlide${index + 1}.xml"))).bufferedReader().readText()
                    require(note.contains(xmlEscape(slide.notes.take(80)))) { "第 ${index + 1} 页讲稿备注未写入 PPTX" }
                }
            }
        }
    }

    private fun htmlInline(value: String): String = parseInline(value).joinToString("") { span ->
        when (span) {
            is InlineSpan.Plain -> xmlEscape(span.text)
            is InlineSpan.Bold -> "<strong>${xmlEscape(span.text)}</strong>"
            is InlineSpan.Italic -> "<em>${xmlEscape(span.text)}</em>"
            is InlineSpan.BoldItalic -> "<strong><em>${xmlEscape(span.text)}</em></strong>"
            is InlineSpan.Strike -> "<s>${xmlEscape(span.text)}</s>"
            is InlineSpan.Code -> "<code>${xmlEscape(span.text)}</code>"
            is InlineSpan.Link -> {
                val url = span.url.trim()
                if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) {
                    "<a href=\"${xmlEscape(url)}\">${xmlEscape(span.text)}</a>"
                } else xmlEscape(span.text)
            }
        }
    }

    private fun wordRuns(value: String): String = parseInline(value).joinToString("") { span ->
        when (span) {
            is InlineSpan.Plain -> wordRun(span.text)
            is InlineSpan.Bold -> wordRun(span.text, bold = true)
            is InlineSpan.Italic -> wordRun(span.text, italic = true)
            is InlineSpan.BoldItalic -> wordRun(span.text, bold = true, italic = true)
            is InlineSpan.Strike -> wordRun(span.text, strike = true)
            is InlineSpan.Code -> wordRun(span.text, code = true)
            is InlineSpan.Link -> wordRun("${span.text} (${span.url})", underline = true)
        }
    }

    private fun wordRun(
        value: String,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false,
        code: Boolean = false,
        underline: Boolean = false
    ): String {
        val properties = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (strike) append("<w:strike/>")
            if (underline) append("<w:u w:val=\"single\"/>")
            if (code) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/>")
        }
        val text = xmlEscape(value).split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">$it</w:t>" }
        return "<w:r>${if (properties.isBlank()) "" else "<w:rPr>$properties</w:rPr>"}$text</w:r>"
    }

    private fun wordTable(table: MarkdownBlock.Table): String = buildString {
        val columns = table.header.size.coerceAtLeast(1)
        append("<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/><w:left w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/><w:right w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"D9E0DC\"/></w:tblBorders></w:tblPr>")
        fun row(cells: List<String>, header: Boolean) {
            append("<w:tr>")
            repeat(columns) { index ->
                append("<w:tc><w:tcPr>${if (header) "<w:shd w:val=\"clear\" w:fill=\"F1F4F2\"/>" else ""}</w:tcPr>")
                append(paragraph(cells.getOrElse(index) { "" }, parseInline = true))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        row(table.header, true)
        table.rows.forEach { row(it, false) }
        append("</w:tbl>")
    }

    private data class PptAsset(val entryName: String, val extension: String, val bytes: ByteArray)
    private data class PptSlideAssets(
        val content: PptAsset? = null,
        val logo: PptAsset? = null,
        val background: PptAsset? = null,
    )

    private fun writePptx(target: File, project: DocumentProject, root: File) {
        val safeSlides = project.presentationSlides()
        require(safeSlides.isNotEmpty()) { "PPT 至少需要一页投影片" }
        val assets = linkedMapOf<Int, PptSlideAssets>()
        val binaryAssets = linkedMapOf<String, ByteArray>()
        val assetCache = linkedMapOf<String, PptAsset>()
        fun loadAsset(path: String, kind: String): PptAsset? {
            if (path.isBlank()) return null
            assetCache[path]?.let { return it }
            val file = WorkspaceFileOps.resolve(root, path).getOrThrow()
            require(file.isFile) { "$kind 图片不存在" }
            val extension = file.extension.lowercase()
            require(extension in setOf("png", "jpg", "jpeg")) { "$kind 仅支持 PNG/JPG 图片" }
            require(file.length() <= 12L * 1024 * 1024) { "$kind 图片超过 12 MB" }
            val entryName = "ppt/media/image${binaryAssets.size + 1}.$extension"
            val asset = PptAsset(entryName, extension, file.readBytes())
            assetCache[path] = asset
            binaryAssets[entryName] = asset.bytes
            return asset
        }
        safeSlides.forEachIndexed { index, slide ->
            val content = slide.imagePath.trim().takeIf { it.isNotBlank() }?.let { loadAsset(it, "第 ${index + 1} 页") }
                ?: if (slide.chart != null) {
                slide.chart.validate(index)
                val entryName = "ppt/media/chart${binaryAssets.size + 1}.png"
                val asset = PptAsset(entryName, "png", chartPng(slide.chart, slide.chartType, project.theme))
                binaryAssets[entryName] = asset.bytes
                asset
            } else null
            val logo = loadAsset(project.brief.logoPath.trim(), "品牌标识")
            val background = loadAsset(project.brief.backgroundPath.trim(), "背景")
            assets[index] = PptSlideAssets(content = content, logo = logo, background = background)
        }
        val files = linkedMapOf<String, String>()
        files["[Content_Types].xml"] = pptContentTypes(safeSlides.size, binaryAssets.keys.map { name -> PptAsset(name, name.substringAfterLast('.'), binaryAssets.getValue(name)) })
        files["_rels/.rels"] = rootRels("ppt/presentation.xml")
        files["ppt/presentation.xml"] = presentationXml(safeSlides.size, project.aspectRatio, project.template)
        files["ppt/_rels/presentation.xml.rels"] = presentationRels(safeSlides.size)
        files["ppt/slideMasters/slideMaster1.xml"] = slideMasterXml()
        files["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = slideMasterRels()
        files["ppt/slideLayouts/slideLayout1.xml"] = slideLayoutXml()
        files["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = slideLayoutRels()
        files["ppt/theme/theme1.xml"] = themeXml(project.theme, project.template)
        files["ppt/notesMasters/notesMaster1.xml"] = notesMasterXml()
        files["ppt/notesMasters/_rels/notesMaster1.xml.rels"] = notesMasterRels()
        safeSlides.forEachIndexed { index, slide ->
            val slideAssets = assets[index] ?: PptSlideAssets()
            files["ppt/slides/slide${index + 1}.xml"] = slideXml(project, slide, slideAssets, index + 1, safeSlides.size)
            files["ppt/slides/_rels/slide${index + 1}.xml.rels"] = slideRels(slideAssets, index + 1)
            files["ppt/notesSlides/notesSlide${index + 1}.xml"] = notesSlideXml(slide.notes)
            files["ppt/notesSlides/_rels/notesSlide${index + 1}.xml.rels"] = notesSlideRels(index + 1)
        }
        zip(target, files, binaryAssets)
    }

    private fun chartPng(chart: ChartSpec, type: ChartType, theme: DocumentTheme): ByteArray {
        val bitmap = Bitmap.createBitmap(1280, 620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#${theme.background}"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = android.graphics.Color.parseColor("#${theme.accent}")
        val ink = android.graphics.Color.parseColor("#${theme.foreground}")
        paint.color = ink
        paint.textSize = 34f
        paint.isFakeBoldText = true
        if (chart.title.isNotBlank()) canvas.drawText(chart.title.take(50), 56f, 56f, paint)
        val max = chart.values.maxOf { it.value }.coerceAtLeast(1.0)
        val left = 70f
        val bottom = 530f
        val chartHeight = 400f
        when (type) {
            ChartType.BAR -> {
                val barWidth = 800f / chart.values.size.coerceAtLeast(1)
                chart.values.forEachIndexed { index, value ->
                    val height = (value.value / max * chartHeight).toFloat()
                    paint.color = accent
                    val x = left + index * barWidth + 18f
                    canvas.drawRoundRect(x, bottom - height, x + barWidth - 36f, bottom, 8f, 8f, paint)
                    paint.color = ink; paint.textSize = 20f
                    canvas.drawText(value.label.take(14), x, bottom + 34f, paint)
                    canvas.drawText(trimNumber(value.value), x, bottom - height - 12f, paint)
                }
            }
            ChartType.LINE -> {
                val step = 800f / (chart.values.size - 1).coerceAtLeast(1)
                val points = chart.values.mapIndexed { index, value ->
                    android.graphics.PointF(left + index * step, bottom - (value.value / max * chartHeight).toFloat())
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f; paint.color = accent
                points.zipWithNext().forEach { (from, to) -> canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
                paint.style = Paint.Style.FILL
                points.forEachIndexed { index, point ->
                    paint.color = accent; canvas.drawCircle(point.x, point.y, 12f, paint)
                    paint.color = ink; paint.textSize = 20f
                    canvas.drawText(chart.values[index].label.take(14), point.x - 12f, bottom + 34f, paint)
                    canvas.drawText(trimNumber(chart.values[index].value), point.x - 12f, point.y - 18f, paint)
                }
            }
            ChartType.PIE -> {
                val total = chart.values.sumOf { it.value }.coerceAtLeast(1.0)
                val colors = intArrayOf(accent, 0xFF4F46E5.toInt(), 0xFFE3A34A.toInt(), 0xFFC95C54.toInt(), 0xFF0EA5E9.toInt(), 0xFF6B7280.toInt())
                var start = -90f
                chart.values.forEachIndexed { index, value ->
                    val sweep = (value.value / total * 360.0).toFloat()
                    paint.color = colors[index % colors.size]
                    canvas.drawArc(100f, 110f, 580f, 590f, start, sweep, true, paint)
                    start += sweep
                    paint.textSize = 22f
                    canvas.drawText("${value.label.take(16)} ${trimNumber(value.value)}", 700f, 150f + index * 48f, paint)
                }
            }
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun trimNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)

    private fun zip(target: File, files: Map<String, String>, binaryFiles: Map<String, ByteArray> = emptyMap()) {
        ZipOutputStream(FileOutputStream(target)).use { out ->
            files.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name))
                out.write(value.toByteArray(StandardCharsets.UTF_8))
                out.closeEntry()
            }
            binaryFiles.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun contentTypes(main: String) = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/$main" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>"""
    private fun rootRels(target: String) = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"/></Relationships>"""
    /** XML 1.0 rejects control characters which can appear in generated logs or copied code. */
    private fun xmlEscape(value: String): String {
        val safe = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF
            ) {
                safe.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return safe.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun pptContentTypes(count: Int, assets: Collection<PptAsset>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>""")
        assets.map { it.extension }.distinct().forEach { extension ->
            append("<Default Extension=\"$extension\" ContentType=\"${if (extension == "png") "image/png" else "image/jpeg"}\"/>")
        }
        repeat(count) { index ->
            append("<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            append("<Override PartName=\"/ppt/notesSlides/notesSlide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>")
        }
        append("</Types>")
    }

    private fun presentationXml(count: Int, aspectRatio: DocumentAspectRatio, template: PresentationTemplate) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id="rId${count + 2}"/></p:notesMasterIdLst><p:sldIdLst>""")
        repeat(count) { index -> append("<p:sldId id=\"${256 + index}\" r:id=\"rId${index + 2}\"/>") }
        append("</p:sldIdLst><p:sldSz cx=\"${aspectRatio.width}\" cy=\"${aspectRatio.height}\" type=\"${if (aspectRatio == DocumentAspectRatio.WIDESCREEN) "screen16x9" else "screen4x3"}\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:showPr useTimings=\"${if (template == PresentationTemplate.DARK_STAGE) 1 else 0}\"/><p:defaultTextStyle/></p:presentation>")
    }

    private fun presentationRels(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        repeat(count) { index -> append("<Relationship Id=\"rId${index + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${index + 1}.xml\"/>") }
        append("<Relationship Id=\"rId${count + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster\" Target=\"notesMasters/notesMaster1.xml\"/>")
        append("</Relationships>")
    }

    private fun slideXml(project: DocumentProject, slide: SlideSpec, assets: PptSlideAssets, slideNumber: Int, slideCount: Int): String {
        val theme = project.theme
        val ratio = project.aspectRatio.width.toDouble() / DocumentAspectRatio.WIDESCREEN.width
        fun x(value: Int) = (value * ratio).toInt()
        val margin = project.template.margin
        val left = x(margin.horizontal)
        val top = margin.vertical
        val right = x(12_192_000 - margin.horizontal)
        val contentWidth = right - left
        val template = project.template
        val shapes = buildString {
            assets.background?.let { append(pictureXml(40, "全局背景", 0, 0, project.aspectRatio.width, project.aspectRatio.height, "rId2")) }
            append(templateDecorationXml(theme, template))
            assets.logo?.let { append(pictureXml(41, "品牌标识", right - x(900000), top, x(700000), x(700000), if (assets.background != null) "rId3" else "rId2")) }
            when (slide.layout) {
                SlideLayout.COVER -> {
                    append(shapeXml(2, "封面标题", left, 1450000, contentWidth, 1300000, slide.title, 3400, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "封面副标题", left, 3000000, contentWidth, 1800000, slide.body, 1900, theme.accent, false, typeface = template.bodyTypeface))
                }
                SlideLayout.AGENDA -> {
                    append(shapeXml(2, "目录标题", left, top, contentWidth, 700000, slide.title.ifBlank { "目录" }, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "目录", left, 1500000, contentWidth, 4000000, slide.body, 1900, theme.foreground, false, bullet = true, typeface = template.bodyTypeface))
                }
                SlideLayout.SECTION -> {
                    append(shapeXml(2, "章节标题", left, 2200000, contentWidth, 1400000, slide.title, 3600, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "章节说明", left, 3900000, contentWidth, 1000000, slide.body, 1800, theme.accent, false, typeface = template.bodyTypeface))
                }
                SlideLayout.QUOTE -> append(shapeXml(2, "引言", left, 1850000, contentWidth, 3000000, "“${slide.body.ifBlank { slide.title }}”", 2800, theme.foreground, false, italic = true, typeface = template.headingTypeface))
                SlideLayout.CLOSING -> {
                    append(shapeXml(2, "结尾标题", left, 2300000, contentWidth, 1300000, slide.title, 3400, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "结尾正文", left, 3900000, contentWidth, 1200000, slide.body, 1900, theme.accent, false, typeface = template.bodyTypeface))
                }
                SlideLayout.TWO_COLUMN, SlideLayout.COMPARISON -> {
                    val columns = slide.body.split(Regex("\\n[ \\t]*---+[ \\t]*\\n"), limit = 2)
                    val gap = x(350000)
                    val columnWidth = (contentWidth - gap) / 2
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "左栏", left, 1500000, columnWidth, 4300000, columns.getOrElse(0) { slide.body }, 1800, theme.foreground, false, bullet = true, typeface = template.bodyTypeface))
                    append(shapeXml(4, "右栏", left + columnWidth + gap, 1500000, columnWidth, 4300000, columns.getOrElse(1) { "" }, 1800, theme.foreground, false, bullet = true, typeface = template.bodyTypeface))
                }
                SlideLayout.IMAGE_TEXT, SlideLayout.DATA -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    val textWidth = if (assets.content != null) (contentWidth - x(350000)) / 2 else contentWidth
                    append(shapeXml(3, "正文", left, 1500000, textWidth, 4300000, slide.body, bodyTextSize(slide.body, 1800), theme.foreground, false, bullet = true, typeface = template.bodyTypeface))
                    assets.content?.let { append(pictureXml(4, "媒体", left + textWidth + x(350000), 1650000, textWidth, 4000000, contentRelId(assets), slide.imageFocus)) }
                }
                SlideLayout.TIMELINE -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(timelineXml(slide.body, left, 1750000, contentWidth, theme, template))
                }
                SlideLayout.PROCESS -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(processXml(slide.body, left, 1900000, contentWidth, theme, template))
                }
                SlideLayout.TABLE -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(tableXml(slide.body, left, 1500000, contentWidth, theme, template))
                }
                SlideLayout.METRICS -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(metricsXml(slide.body, left, 1650000, contentWidth, theme, template))
                }
                SlideLayout.TITLE_BODY -> {
                    append(shapeXml(2, "标题", left, top, contentWidth, 800000, slide.title, 2700, theme.foreground, true, typeface = template.headingTypeface))
                    append(shapeXml(3, "正文", left, 1550000, contentWidth, 4200000, slide.body, bodyTextSize(slide.body, 1900), theme.foreground, false, bullet = true, typeface = template.bodyTypeface))
                }
            }
            append(footerXml(20, project, slideNumber, slideCount, right, theme))
        }
        return """<?xml version="1.0" encoding="UTF-8"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld>${backgroundShape(theme, project.template)}<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>${transitionXml(project.transition)}</p:sld>"""
    }

    private fun backgroundShape(theme: DocumentTheme, template: PresentationTemplate): String = buildString {
        append("<p:bg><p:bgPr>")
        when (template.backgroundStyle) {
            BackgroundStyle.SOLID -> append("<a:solidFill><a:srgbClr val=\"${theme.background}\"/></a:solidFill>")
            BackgroundStyle.BLOCK -> append("<a:solidFill><a:srgbClr val=\"${theme.background}\"/></a:solidFill>")
            BackgroundStyle.DUOTONE -> append("<a:gradFill rotWithShape=\"1\"><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"${theme.background}\"/></a:gs><a:gs pos=\"100000\"><a:srgbClr val=\"${theme.muted}\"/></a:gs></a:gsLst><a:lin ang=\"5400000\" scaled=\"0\"/></a:gradFill>")
        }
        append("<a:effectLst/></p:bgPr></p:bg>")
    }

    private fun templateDecorationXml(theme: DocumentTheme, template: PresentationTemplate): String = when (template.backgroundStyle) {
        BackgroundStyle.SOLID -> ""
        BackgroundStyle.BLOCK -> coloredBarXml(30, "模板色带", 0, 0, 12_192_000, 220_000, theme.accent)
        BackgroundStyle.DUOTONE -> coloredBarXml(31, "模板底带", 0, 6_420_000, 12_192_000, 438_000, theme.accent)
    }

    private fun coloredBarXml(id: Int, name: String, x: Int, y: Int, width: Int, height: Int, color: String): String = """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>"""

    private fun bodyTextSize(body: String, preferred: Int): Int = when {
        body.length > 900 || body.lines().size > 18 -> 1200
        body.length > 600 || body.lines().size > 12 -> 1450
        body.length > 360 || body.lines().size > 8 -> 1650
        else -> preferred
    }

    private fun contentRelId(assets: PptSlideAssets): String = when {
        assets.background != null && assets.logo != null -> "rId4"
        assets.background != null || assets.logo != null -> "rId3"
        else -> "rId2"
    }

    private fun timelineXml(body: String, left: Int, top: Int, width: Int, theme: DocumentTheme, template: PresentationTemplate): String {
        val items = body.lines().filter(String::isNotBlank).take(5).ifEmpty { listOf("阶段一", "阶段二") }
        val gap = width / items.size
        return buildString {
            append(coloredBarXml(50, "时间线", left, top + 430000, width, 70000, theme.muted))
            items.forEachIndexed { index, item ->
                val x = left + index * gap
                append(coloredBarXml(51 + index * 2, "时间节点", x, top + 300000, 220000, 320000, theme.accent))
                append(shapeXml(52 + index * 2, "时间线内容", x, top + 780000, (gap - 120000).coerceAtLeast(500000), 1500000, item, 1500, theme.foreground, false, typeface = template.bodyTypeface))
            }
        }
    }

    private fun processXml(body: String, left: Int, top: Int, width: Int, theme: DocumentTheme, template: PresentationTemplate): String {
        val items = body.lines().filter(String::isNotBlank).flatMap { it.split("→", "->") }.map(String::trim).filter(String::isNotBlank).take(5).ifEmpty { listOf("输入", "处理", "结果") }
        val gap = width / items.size
        return buildString {
            items.forEachIndexed { index, item ->
                val x = left + index * gap
                append(coloredBarXml(60 + index * 2, "流程步骤", x, top, (gap - 180000).coerceAtLeast(600000), 560000, if (index == items.lastIndex) theme.accent else theme.muted))
                append(shapeXml(61 + index * 2, "流程文字", x + 70000, top + 120000, (gap - 320000).coerceAtLeast(420000), 300000, item, 1500, theme.foreground, true, typeface = template.bodyTypeface))
                if (index != items.lastIndex) append(shapeXml(70 + index, "流程箭头", x + gap - 140000, top + 140000, 140000, 220000, "→", 1800, theme.accent, true, typeface = template.headingTypeface))
            }
        }
    }

    private fun tableXml(body: String, left: Int, top: Int, width: Int, theme: DocumentTheme, template: PresentationTemplate): String {
        val rows = PptDeckComposer.parseTable(body).take(7).ifEmpty { listOf(listOf("项目", "内容"), listOf("示例", "请填写表格数据")) }
        val columns = rows.maxOf { it.size }.coerceAtMost(4)
        val cellWidth = width / columns
        val cellHeight = 520000
        return buildString {
            rows.forEachIndexed { rowIndex, row ->
                repeat(columns) { columnIndex ->
                    val x = left + columnIndex * cellWidth
                    val y = top + rowIndex * cellHeight
                    append(coloredBarXml(80 + rowIndex * columns + columnIndex, "表格单元格", x, y, cellWidth - 20000, cellHeight - 20000, if (rowIndex == 0) theme.accent else theme.muted))
                    append(shapeXml(120 + rowIndex * columns + columnIndex, "表格文字", x + 40000, y + 90000, cellWidth - 100000, cellHeight - 150000, row.getOrElse(columnIndex) { "" }, 1200, if (rowIndex == 0) theme.background else theme.foreground, rowIndex == 0, typeface = template.bodyTypeface))
                }
            }
        }
    }

    private fun metricsXml(body: String, left: Int, top: Int, width: Int, theme: DocumentTheme, template: PresentationTemplate): String {
        val values = body.lines().mapNotNull { line ->
            val split = line.indexOfAny(charArrayOf(':', '：'))
            if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
        }.take(4).ifEmpty { listOf("指标" to "--", "指标" to "--") }
        val columns = if (values.size <= 2) values.size else 2
        val cellWidth = width / columns
        return buildString {
            values.forEachIndexed { index, (label, value) ->
                val x = left + (index % columns) * cellWidth
                val y = top + (index / columns) * 1500000
                append(coloredBarXml(170 + index, "指标背景", x, y, cellWidth - 140000, 1200000, theme.muted))
                append(shapeXml(180 + index, "指标数值", x + 100000, y + 180000, cellWidth - 340000, 480000, value, 3000, theme.accent, true, typeface = template.headingTypeface))
                append(shapeXml(190 + index, "指标说明", x + 100000, y + 760000, cellWidth - 340000, 260000, label, 1300, theme.foreground, false, typeface = template.bodyTypeface))
            }
        }
    }

    private fun shapeXml(id: Int, name: String, x: Int, y: Int, width: Int, height: Int, text: String, size: Int, color: String, bold: Boolean, italic: Boolean = false, bullet: Boolean = false, typeface: String = "Aptos"): String {
        val body = text.lines().filter { it.isNotBlank() }.ifEmpty { listOf("") }.joinToString("") { line ->
            val clean = line.trimStart().removePrefix("-").removePrefix("*").removePrefix("•").trim()
            val pPr = if (bullet) "<a:pPr marL=\"240000\" indent=\"-120000\"><a:buChar char=\"•\"/></a:pPr>" else ""
            "<a:p>$pPr<a:r><a:rPr lang=\"zh-CN\" sz=\"$size\"${if (bold) " b=\"1\"" else ""}${if (italic) " i=\"1\"" else ""}><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:latin typeface=\"${xmlEscape(typeface)}\"/><a:ea typeface=\"${xmlEscape(typeface)}\"/></a:rPr><a:t>${xmlEscape(clean)}</a:t></a:r><a:endParaRPr lang=\"zh-CN\"/></a:p>"
        }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" anchor="t"/><a:lstStyle/>$body</p:txBody></p:sp>"""
    }

    private fun pictureXml(id: Int, name: String, x: Int, y: Int, width: Int, height: Int, relId: String, focus: ImageFocus = ImageFocus.CENTER): String {
        val crop = when (focus) {
            ImageFocus.TOP -> "<a:fillRect b=\"20000\"/>"
            ImageFocus.BOTTOM -> "<a:fillRect t=\"20000\"/>"
            ImageFocus.LEFT -> "<a:fillRect r=\"20000\"/>"
            ImageFocus.RIGHT -> "<a:fillRect l=\"20000\"/>"
            ImageFocus.CENTER -> "<a:fillRect/>"
        }
        return """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="$name"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="$relId"/><a:stretch>$crop</a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
    }

    private fun footerXml(id: Int, project: DocumentProject, slideNumber: Int, slideCount: Int, right: Int, theme: DocumentTheme): String {
        if (slideNumber == 1 || slideNumber == slideCount) return ""
        val footer = listOf(project.brief.presenter, "$slideNumber / $slideCount").filter(String::isNotBlank).joinToString("  ·  ")
        return shapeXml(id, "页脚", right - 2_400_000, 6_170_000, 2_400_000, 250_000, footer, 900, theme.accent, false, typeface = project.template.bodyTypeface)
    }

    private fun transitionXml(transition: PresentationTransition): String = when (transition) {
        PresentationTransition.NONE -> ""
        PresentationTransition.FADE -> "<p:transition spd=\"med\"><p:fade/></p:transition>"
        PresentationTransition.PUSH -> "<p:transition spd=\"med\" advClick=\"1\"><p:push dir=\"l\"/></p:transition>"
    }

    private fun slideRels(assets: PptSlideAssets, slideNumber: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
        var relation = 2
        listOf(assets.background, assets.logo, assets.content).forEach { asset ->
            if (asset != null) {
                append("<Relationship Id=\"rId$relation\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/${asset.entryName.substringAfterLast('/')}\"/>")
                relation++
            }
        }
        append("<Relationship Id=\"rId$relation\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" Target=\"../notesSlides/notesSlide$slideNumber.xml\"/>")
        append("</Relationships>")
    }

    private fun notesSlideRels(slideNumber: Int) = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="../slides/slide$slideNumber.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="../notesMasters/notesMaster1.xml"/></Relationships>"""
    private fun notesSlideXml(notes: String) = """<?xml version="1.0" encoding="UTF-8"?><p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:sp><p:nvSpPr><p:cNvPr id="2" name="备注"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="10820400" cy="5943600"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="1800"/><a:t>${xmlEscape(notes)}</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"""
    private fun notesMasterRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""
    private fun notesMasterXml() = """<?xml version="1.0" encoding="UTF-8"?><p:notesMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="Notes Master"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr></p:spTree></p:cSld><p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/></p:notesMaster>"""

    private fun slideXml(title: String, text: String): String {
        val body = xmlEscape(text).replace("\r?\n".toRegex(), "&#xA;")
        return """<?xml version="1.0" encoding="UTF-8"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:sp><p:nvSpPr><p:cNvPr id="2" name="TextBox 1"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="10820400" cy="5943600"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="3000" b="1"/><a:t>${xmlEscape(title)}</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p><a:p><a:r><a:rPr lang="zh-CN" sz="1900"/><a:t>$body</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }
    private fun slideRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
    private fun slideMasterRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""
    private fun slideLayoutRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
    private fun slideLayoutXml() = """<?xml version="1.0" encoding="UTF-8"?><p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
    private fun slideMasterXml() = """<?xml version="1.0" encoding="UTF-8"?><p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="Master"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles/><p:hf/><p:timing/></p:sldMaster>"""
    private fun themeXml(theme: DocumentTheme, template: PresentationTemplate): String = themeXml()
        .replace("F8FAFC", theme.background)
        .replace("1F2937", theme.foreground)
        .replace("2F7E6D", theme.accent)
        .replace("D9E0DC", theme.muted)
        .replace("Aptos Display", template.headingTypeface)
        .replace("Aptos", template.bodyTypeface)

    private fun themeXml() = """<?xml version="1.0" encoding="UTF-8"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="HSUCODE"><a:themeElements><a:clrScheme name="HSUCODE"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2F7E6D"/></a:accent1><a:accent2><a:srgbClr val="E3A34A"/></a:accent2><a:accent3><a:srgbClr val="C95C54"/></a:accent3><a:accent4><a:srgbClr val="6B7280"/></a:accent4><a:accent5><a:srgbClr val="4F46E5"/></a:accent5><a:accent6><a:srgbClr val="0EA5E9"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="HSUCODE"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface="Microsoft YaHei"/><a:cs typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface="Microsoft YaHei"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme><a:fmtScheme name="HSUCODE"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/></a:fmtScheme></a:themeElements></a:theme>"""
}
