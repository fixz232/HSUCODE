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
            "PPTX 用换行 + --- 分隔幻灯片，也可传 slides 数组。优先使用结构化页面；支持封面、目录、章节、双栏、对比、图文、数据、时间线、流程图、表格和指标页。返回工作区路径和手机 Download/HSUCODE/日期路径。"

    override val parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("format", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("pptx", "docx", "pdf", "html")))
                put("description", "导出格式")
            })
            put("title", JSONObject().apply { put("type", "string"); put("description", "文档或演示标题") })
            put("file_name", JSONObject().apply { put("type", "string"); put("description", "可选文件名，不含扩展名；默认使用标题") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "正文；PPTX 可用 --- 分隔幻灯片。若传 slides 可省略") })
            put("slides", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("layout", JSONObject().apply { put("type", "string"); put("description", "cover、title_body、two_column、image_text、data 等") })
                        put("title", JSONObject().apply { put("type", "string") })
                        put("body", JSONObject().apply { put("type", "string") })
                        put("notes", JSONObject().apply { put("type", "string") })
                        put("image_path", JSONObject().apply { put("type", "string") })
                        put("image_url", JSONObject().apply { put("type", "string"); put("description", "可选 HTTPS 图片地址；会下载到工作区并嵌入 PPTX") })
                        put("chart", JSONObject().apply { put("type", "object"); put("description", "{title, values:[{label,value}]}") })
                        put("chart_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("bar", "line", "pie"))) })
                    })
                })
                put("description", "可选的结构化 PPT 投影片数组")
            })
            put("theme", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("HSUCODE", "FOREST", "MIDNIGHT", "PAPER"))); put("description", "PPT 主题") })
            put("template", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("HSUCODE", "BUSINESS", "EDITORIAL", "DARK_STAGE"))); put("description", "PPT 统一模板：同时控制背景、字体和页边距") })
            put("aspect_ratio", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("16:9", "4:3"))); put("description", "PPT 比例") })
            put("purpose", JSONObject().apply { put("type", "string"); put("description", "演示目标，例如汇报、答辩、宣讲或提案") })
            put("audience", JSONObject().apply { put("type", "string"); put("description", "目标受众") })
            put("presenter", JSONObject().apply { put("type", "string"); put("description", "汇报人") })
            put("date", JSONObject().apply { put("type", "string"); put("description", "封面日期") })
            put("logo_path", JSONObject().apply { put("type", "string"); put("description", "工作区内 PNG/JPG 品牌标识，会统一显示在投影片") })
            put("background_path", JSONObject().apply { put("type", "string"); put("description", "工作区内 PNG/JPG 背景图片，会作为每页全局背景") })
            put("transition", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("none", "fade", "push"))); put("description", "全局页面切换；默认淡入") })
        })
        put("required", JSONArray(listOf("format", "title")))
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
            val fileName = params["file_name"]?.trim()?.takeIf { it.isNotBlank() }
            val content = params["content"].orEmpty()
            val slides = parseSlides(params["slides"])
            require(content.isNotBlank() || slides.isNotEmpty()) { "content 与 slides 至少提供一个" }
            val requestedTheme = params["theme"]?.trim()?.uppercase()
            val template = PresentationTemplate.entries.firstOrNull { it.name.equals(params["template"]?.trim(), ignoreCase = true) } ?: PresentationTemplate.HSUCODE
            val theme = when (requestedTheme) {
                "FOREST" -> DocumentTheme.FOREST
                "MIDNIGHT" -> DocumentTheme.MIDNIGHT
                "PAPER" -> DocumentTheme.PAPER
                "HSUCODE" -> DocumentTheme.HSUCODE
                else -> template.defaultTheme
            }
            val aspect = if (params["aspect_ratio"]?.trim() == "4:3") DocumentAspectRatio.STANDARD else DocumentAspectRatio.WIDESCREEN
            val transition = PresentationTransition.entries.firstOrNull { it.name.equals(params["transition"]?.trim(), ignoreCase = true) } ?: PresentationTransition.FADE
            val brief = PresentationBrief(
                purpose = params["purpose"].orEmpty(),
                audience = params["audience"].orEmpty(),
                presenter = params["presenter"].orEmpty(),
                date = params["date"].orEmpty(),
                logoPath = params["logo_path"].orEmpty(),
                backgroundPath = params["background_path"].orEmpty(),
            )
            val root = WorkspaceFileOps.root(WorkspaceContext.workspaceRoot).getOrThrow()
            val structuredSlides = parseStructuredSlides(params["slides"])
            val downloadedSlides = structuredSlides.mapIndexed { index, slide ->
                val url = parseImageUrl(params["slides"], index)
                if (url.isNullOrBlank()) slide else slide.copy(imagePath = downloadImage(root, url, index))
            }
            val project = if (downloadedSlides.isNotEmpty()) {
                DocumentProject(title = title, fileName = fileName ?: title, theme = theme, template = template, aspectRatio = aspect, brief = brief, transition = transition, sourceMarkdown = content, slides = downloadedSlides)
            } else {
                DocumentProject.fromMarkdown(title, content, slides, fileName ?: title, theme, template, aspect, brief, transition)
            }
            DocumentProjectStore.save(root, project)
            val created = DocumentExport.create(root, format, project, fileName)
            val published = WorkspaceContext.publishChatFile(created.file, mimeType = mimeType(format))
            val destination = published?.let { "；已同步到手机 $it" }
                ?: "；下载目录同步失败，工作区副本已保留"
            ToolResult.Success("已生成 ${created.format.name.lowercase()}：${created.file.absolutePath}$destination")
        }.getOrElse { ToolResult.Error("文档生成失败：${it.message ?: "未知错误"}") }
    }

    private fun mimeType(format: DocumentExport.Format): String = when (format) {
        DocumentExport.Format.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        DocumentExport.Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        DocumentExport.Format.PDF -> "application/pdf"
        DocumentExport.Format.HTML -> "text/html"
    }

    private fun parseSlides(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) { repeat(array.length()) { array.optString(it).takeIf(String::isNotBlank)?.let(::add) } }
        }.getOrDefault(emptyList())
    }

    private fun parseStructuredSlides(raw: String?): List<SlideSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val layout = SlideLayout.entries.firstOrNull { it.name.equals(item.optString("layout"), ignoreCase = true) } ?: SlideLayout.TITLE_BODY
                    val chart = item.optJSONObject("chart")?.let(ChartSpec::fromJson)
                    val chartType = ChartType.entries.firstOrNull { it.name.equals(item.optString("chart_type"), ignoreCase = true) } ?: ChartType.BAR
                    add(SlideSpec(layout, item.optString("title"), item.optString("body"), item.optString("notes"), item.optString("image_path"), chart, chartType))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseImageUrl(raw: String?, index: Int): String? = runCatching {
        JSONArray(raw ?: return null).optJSONObject(index)?.optString("image_url")?.trim()
    }.getOrNull()?.takeIf { it.startsWith("https://") }

    private fun downloadImage(root: java.io.File, url: String, index: Int): String {
        val directory = WorkspaceFileOps.resolve(root, "imports/ppt-assets").getOrThrow().apply { mkdirs() }
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connect()
        require(connection.responseCode in 200..299) { "图片下载失败 HTTP ${connection.responseCode}" }
        val type = connection.contentType.orEmpty().lowercase()
        val extension = when {
            type.contains("png") -> "png"
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            else -> error("图片必须是 PNG/JPG")
        }
        val target = WorkspaceFileOps.uniqueChild(directory, "slide-${index + 1}.$extension")
        connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output, 16 * 1024) } }
        require(target.length() <= 12L * 1024 * 1024) { "图片超过 12 MB" }
        return "imports/ppt-assets/${target.name}"
    }
}
