package com.hsucode.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Dependency-free document deliverables for the workspace and Agent tools. */
object DocumentExport {
    enum class Format { PPTX, DOCX, PDF, HTML }

    data class CreatedFile(val file: File, val format: Format)

    fun create(root: File, format: Format, title: String, content: String, slides: List<String> = emptyList()): CreatedFile {
        require(title.trim().isNotEmpty()) { "标题不能为空" }
        require(content.length <= 4_000_000) { "内容超过 4 MB" }
        val exportDir = File(root, "exports").apply { require(mkdirs() || isDirectory) { "无法创建 exports 目录" } }
        val safeTitle = title.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80).ifBlank { "document" }
        val extension = when (format) {
            Format.PPTX -> ".pptx"
            Format.DOCX -> ".docx"
            Format.PDF -> ".pdf"
            Format.HTML -> ".html"
        }
        val target = WorkspaceFileOps.uniqueChild(exportDir, safeTitle + extension)
        when (format) {
            Format.PPTX -> writePptx(target, title.trim(), slides.ifEmpty { splitSlides(content) })
            Format.DOCX -> writeDocx(target, title.trim(), content)
            Format.PDF -> writePdf(target, title.trim(), content)
            Format.HTML -> writeHtml(target, title.trim(), content)
        }
        return CreatedFile(target, format)
    }

    fun splitSlides(content: String): List<String> = content
        // Treat a divider at the beginning/end as a divider too. The previous
        // expression required a newline before `---`, leaving a literal `---`
        // slide when content started with an empty section.
        .split(Regex("(?m)^[ \\t]*---+[ \\t]*(?:\\r?\\n|$)"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty { listOf(content.trim()) }

    private fun writeHtml(target: File, title: String, content: String) {
        val body = content.lines().joinToString("\n") { line ->
            when {
                line.startsWith("### ") -> "<h3>${xmlEscape(line.removePrefix("### "))}</h3>"
                line.startsWith("## ") -> "<h2>${xmlEscape(line.removePrefix("## "))}</h2>"
                line.startsWith("# ") -> "<h1>${xmlEscape(line.removePrefix("# "))}</h1>"
                line.isBlank() -> ""
                else -> "<p>${xmlEscape(line)}</p>"
            }
        }
        target.writeText(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${xmlEscape(title)}</title><style>body{max-width:760px;margin:32px auto;padding:0 20px;font:16px/1.65 sans-serif;color:#17201d}h1,h2,h3{line-height:1.25}</style><body><h1>${xmlEscape(title)}</h1>$body</body></html>",
            Charsets.UTF_8
        )
    }

    private fun writePdf(target: File, title: String, content: String) {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFF17201D.toInt(); isFakeBoldText = true }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = 0xFF25302C.toInt() }
        val pageWidth = 595
        val pageHeight = 842
        val left = 42f
        val right = 42f
        val maxWidth = pageWidth - left - right
        val bodyLines = content.lines().flatMap { wrap(it, bodyPaint, maxWidth) }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 58f
        wrap(title, titlePaint, maxWidth).forEach { line ->
            canvas.drawText(line, left, y, titlePaint)
            y += 28f
        }
        y += 8f
        for (line in bodyLines.ifEmpty { listOf("") }) {
            if (y > pageHeight - 48f) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = 58f
            }
            canvas.drawText(line, left, y, bodyPaint)
            y += 20f
        }
        document.finishPage(page)
        FileOutputStream(target).use { document.writeTo(it) }
        document.close()
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.forEach { char ->
            val candidate = current.toString() + char
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += current.toString()
                current = StringBuilder().append(char)
            } else current.append(char)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun writeDocx(target: File, title: String, content: String) {
        val paragraphs = buildString {
            append(paragraph(title, "Title"))
            content.lines().forEach { line ->
                val style = when {
                    line.startsWith("### ") -> "Heading3"
                    line.startsWith("## ") -> "Heading2"
                    line.startsWith("# ") -> "Heading1"
                    else -> null
                }
                val text = line.removePrefix("### ").removePrefix("## ").removePrefix("# ")
                if (text.isBlank()) append("<w:p/>") else append(paragraph(text, style))
            }
        }
        zip(target, mapOf(
            "[Content_Types].xml" to contentTypes("word/document.xml"),
            "_rels/.rels" to rootRels("word/document.xml"),
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="22"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="Heading 1"/><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="Heading 2"/><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="Heading 3"/><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style></w:styles>"""
        ))
    }

    private fun paragraph(text: String, style: String?): String {
        val pPr = style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" }.orEmpty()
        return "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"
    }

    private fun writePptx(target: File, title: String, slides: List<String>) {
        val safeSlides = slides.ifEmpty { listOf(title) }.take(40)
        val files = linkedMapOf<String, String>()
        files["[Content_Types].xml"] = pptContentTypes(safeSlides.size)
        files["_rels/.rels"] = rootRels("ppt/presentation.xml")
        files["ppt/presentation.xml"] = presentationXml(safeSlides.size)
        files["ppt/_rels/presentation.xml.rels"] = presentationRels(safeSlides.size)
        files["ppt/slideMasters/slideMaster1.xml"] = slideMasterXml()
        files["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = slideMasterRels()
        files["ppt/slideLayouts/slideLayout1.xml"] = slideLayoutXml()
        files["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = slideLayoutRels()
        files["ppt/theme/theme1.xml"] = themeXml()
        safeSlides.forEachIndexed { index, text ->
            files["ppt/slides/slide${index + 1}.xml"] = slideXml(title, text)
            files["ppt/slides/_rels/slide${index + 1}.xml.rels"] = slideRels()
        }
        zip(target, files)
    }

    private fun zip(target: File, files: Map<String, String>) {
        ZipOutputStream(FileOutputStream(target)).use { out ->
            files.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name))
                out.write(value.toByteArray(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun contentTypes(main: String) = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/$main" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
    private fun rootRels(target: String) = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"/></Relationships>"""
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun pptContentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        repeat(count) { index -> append("<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>") }
        append("</Types>")
    }

    private fun presentationXml(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>""")
        repeat(count) { index -> append("<p:sldId id=\"${256 + index}\" r:id=\"rId${index + 2}\"/>") }
        append("</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle/></p:presentation>")
    }

    private fun presentationRels(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        repeat(count) { index -> append("<Relationship Id=\"rId${index + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${index + 1}.xml\"/>") }
        append("</Relationships>")
    }

    private fun slideXml(title: String, text: String): String {
        val body = xmlEscape(text).replace("\r?\n".toRegex(), "&#xA;")
        return """<?xml version="1.0" encoding="UTF-8"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:sp><p:nvSpPr><p:cNvPr id="2" name="TextBox 1"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="10820400" cy="5943600"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="3000" b="1"/><a:t>${xmlEscape(title)}</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p><a:p><a:r><a:rPr lang="zh-CN" sz="1900"/><a:t>$body</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }
    private fun slideRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
    private fun slideMasterRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""
    private fun slideLayoutRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
    private fun slideLayoutXml() = """<?xml version="1.0" encoding="UTF-8"?><p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
    private fun slideMasterXml() = """<?xml version="1.0" encoding="UTF-8"?><p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="Master"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles/><p:hf/><p:timing/></p:sldMaster>"""
    private fun themeXml() = """<?xml version="1.0" encoding="UTF-8"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="HSUCODE"><a:themeElements><a:clrScheme name="HSUCODE"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2F7E6D"/></a:accent1><a:accent2><a:srgbClr val="E3A34A"/></a:accent2><a:accent3><a:srgbClr val="C95C54"/></a:accent3><a:accent4><a:srgbClr val="6B7280"/></a:accent4><a:accent5><a:srgbClr val="4F46E5"/></a:accent5><a:accent6><a:srgbClr val="0EA5E9"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="HSUCODE"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface="Microsoft YaHei"/><a:cs typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface="Microsoft YaHei"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme><a:fmtScheme name="HSUCODE"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/></a:fmtScheme></a:themeElements></a:theme>"""
}
