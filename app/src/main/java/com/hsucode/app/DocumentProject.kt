package com.hsucode.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Portable source model used by the document workbench and document tools.
 * It deliberately stores workspace-relative media paths only, so a project
 * remains movable and cannot cause exports to read arbitrary files.
 */
data class DocumentProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val fileName: String = title,
    val author: String = "HSUCODE",
    val theme: DocumentTheme = DocumentTheme.HSUCODE,
    val template: PresentationTemplate = PresentationTemplate.HSUCODE,
    val aspectRatio: DocumentAspectRatio = DocumentAspectRatio.WIDESCREEN,
    val brief: PresentationBrief = PresentationBrief(),
    val transition: PresentationTransition = PresentationTransition.FADE,
    val sourceMarkdown: String = "",
    val slides: List<SlideSpec> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun effectiveSlides(): List<SlideSpec> = slides.ifEmpty {
        DocumentExport.splitSlides(sourceMarkdown).mapIndexed { index, block ->
            val lines = block.lines().filter(String::isNotBlank)
            val heading = lines.firstOrNull()?.removePrefix("#")?.trim().orEmpty()
            SlideSpec(
                layout = SlideLayout.TITLE_BODY,
                title = heading.ifBlank { title },
                body = lines.drop(if (heading.isBlank()) 0 else 1).joinToString("\n").ifBlank { block }
            )
        }
    }.ifEmpty { listOf(SlideSpec(layout = SlideLayout.COVER, title = title)) }

    fun presentationSlides(): List<SlideSpec> = PptDeckComposer.compose(this)

    fun validate() {
        require(title.trim().isNotEmpty()) { "标题不能为空" }
        require(title.length <= 240) { "标题过长" }
        require(sourceMarkdown.length <= MAX_DOCUMENT_TEXT) { "文档内容超过 4 MB" }
        require(presentationSlides().size <= MAX_SLIDES) { "PPT 最多支持 $MAX_SLIDES 页，请拆分为多个演示文稿" }
        presentationSlides().forEachIndexed { index, slide ->
            require(slide.title.length <= 600 && slide.body.length <= 120_000) { "第 ${index + 1} 页内容过长" }
            require(slide.imagePath.length <= 500) { "第 ${index + 1} 页图片路径过长" }
            slide.chart?.validate(index)
        }
        require(brief.logoPath.length <= 500) { "品牌标识路径过长" }
        require(brief.backgroundPath.length <= 500) { "背景图片路径过长" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 2)
        put("id", id)
        put("title", title)
        put("fileName", fileName)
        put("author", author)
        put("theme", theme.name)
        put("template", template.name)
        put("aspectRatio", aspectRatio.name)
        put("brief", brief.toJson())
        put("transition", transition.name)
        put("sourceMarkdown", sourceMarkdown)
        put("updatedAt", updatedAt)
        put("slides", JSONArray().apply { effectiveSlides().forEach { put(it.toJson()) } })
    }

    companion object {
        const val MAX_DOCUMENT_TEXT = 4_000_000
        const val MAX_SLIDES = 120

        fun fromMarkdown(
            title: String,
            content: String,
            slideBodies: List<String> = emptyList(),
            fileName: String = title,
            theme: DocumentTheme = DocumentTheme.HSUCODE,
            template: PresentationTemplate = PresentationTemplate.HSUCODE,
            aspectRatio: DocumentAspectRatio = DocumentAspectRatio.WIDESCREEN,
            brief: PresentationBrief = PresentationBrief(),
            transition: PresentationTransition = PresentationTransition.FADE,
        ): DocumentProject {
            val sources = slideBodies.ifEmpty { DocumentExport.splitSlides(content) }
            val slideSpecs = sources.filter(String::isNotBlank).mapIndexed { index, raw ->
                val lines = raw.lines().map(String::trim).filter(String::isNotBlank)
                val first = lines.firstOrNull().orEmpty()
                val heading = first.removePrefix("#").trim()
                val body = lines.drop(if (heading.isBlank()) 0 else 1).joinToString("\n").ifBlank { raw.trim() }
                SlideSpec(
                    layout = inferLayout(heading, body, index),
                    title = heading.ifBlank { title },
                    body = body,
                    chart = inferChart(body),
                )
            }
            return DocumentProject(
                title = title.trim(),
                fileName = fileName.trim().ifBlank { title.trim() },
                theme = theme,
                template = template,
                aspectRatio = aspectRatio,
                brief = brief,
                transition = transition,
                sourceMarkdown = content,
                slides = slideSpecs
            )
        }

        fun fromJson(json: JSONObject): DocumentProject {
            val title = json.optString("title").trim().ifBlank { "未命名文档" }
            val slides = json.optJSONArray("slides")?.let { array ->
                buildList {
                    repeat(array.length()) { index ->
                        array.optJSONObject(index)?.let { add(SlideSpec.fromJson(it)) }
                    }
                }
            }.orEmpty()
            return DocumentProject(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = title,
                fileName = json.optString("fileName").ifBlank { title },
                author = json.optString("author").ifBlank { "HSUCODE" },
                theme = enumOrDefault(json.optString("theme"), DocumentTheme.HSUCODE),
                template = enumOrDefault(json.optString("template"), PresentationTemplate.HSUCODE),
                aspectRatio = enumOrDefault(json.optString("aspectRatio"), DocumentAspectRatio.WIDESCREEN),
                brief = json.optJSONObject("brief")?.let(PresentationBrief::fromJson) ?: PresentationBrief(),
                transition = enumOrDefault(json.optString("transition"), PresentationTransition.FADE),
                sourceMarkdown = json.optString("sourceMarkdown").take(MAX_DOCUMENT_TEXT),
                slides = slides,
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback

        private fun inferLayout(title: String, body: String, index: Int): SlideLayout {
            if (index == 0 && title.isNotBlank()) return SlideLayout.COVER
            if (body.contains("|") && body.lines().count { it.contains("|") } >= 2) return SlideLayout.TABLE
            if (body.lines().count { it.trimStart().startsWith(">") } >= 1) return SlideLayout.QUOTE
            if (body.lines().count { it.contains("->") || it.contains("→") } >= 2) return SlideLayout.PROCESS
            if (inferChart(body) != null) return SlideLayout.DATA
            return SlideLayout.TITLE_BODY
        }

        private fun inferChart(body: String): ChartSpec? {
            val values = body.lines().mapNotNull { line ->
                val split = line.indexOfAny(charArrayOf(':', '：', ','))
                if (split <= 0) return@mapNotNull null
                val value = line.substring(split + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                ChartValue(line.substring(0, split).trim(), value)
            }
            return values.takeIf { it.size in 2..12 }?.let { ChartSpec(values = it) }
        }
    }
}

enum class DocumentTheme(val label: String, val background: String, val foreground: String, val accent: String, val muted: String) {
    HSUCODE("HSUCODE", "F7F9F7", "17201D", "176B4C", "D9E0DC"),
    FOREST("林间", "F3F7F2", "153226", "317957", "D5E1D5"),
    MIDNIGHT("深夜", "111827", "F8FAFC", "62C595", "26364C"),
    PAPER("简报", "FFFFFF", "1E293B", "2563EB", "E2E8F0");
}

/** A presentation template controls background, typography and safe margins as one unit. */
enum class PresentationTemplate(
    val label: String,
    val defaultTheme: DocumentTheme,
    val backgroundStyle: BackgroundStyle,
    val headingTypeface: String,
    val bodyTypeface: String,
    val margin: PresentationMargin,
) {
    HSUCODE("HSUCODE 简洁", DocumentTheme.HSUCODE, BackgroundStyle.SOLID, "Aptos Display", "Aptos", PresentationMargin.COMFORTABLE),
    BUSINESS("商务汇报", DocumentTheme.PAPER, BackgroundStyle.BLOCK, "Aptos Display", "Aptos", PresentationMargin.COMFORTABLE),
    EDITORIAL("内容提案", DocumentTheme.FOREST, BackgroundStyle.DUOTONE, "Georgia", "Aptos", PresentationMargin.GENEROUS),
    DARK_STAGE("深色宣讲", DocumentTheme.MIDNIGHT, BackgroundStyle.BLOCK, "Aptos Display", "Aptos", PresentationMargin.COMFORTABLE);
}

enum class BackgroundStyle { SOLID, BLOCK, DUOTONE }

enum class PresentationMargin(val label: String, val horizontal: Int, val vertical: Int) {
    COMPACT("紧凑", 520_000, 360_000),
    COMFORTABLE("标准", 700_000, 500_000),
    GENEROUS("宽松", 900_000, 650_000);
}

enum class PresentationTransition(val label: String) {
    NONE("无"),
    FADE("淡入"),
    PUSH("推进");
}

data class PresentationBrief(
    val purpose: String = "",
    val audience: String = "",
    val presenter: String = "",
    val date: String = "",
    /** Relative workspace path to a PNG/JPG logo displayed on each slide. */
    val logoPath: String = "",
    /** Relative workspace path to a PNG/JPG image used as the presentation background. */
    val backgroundPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("purpose", purpose)
        put("audience", audience)
        put("presenter", presenter)
        put("date", date)
        put("logoPath", logoPath)
        put("backgroundPath", backgroundPath)
    }

    companion object {
        fun fromJson(json: JSONObject) = PresentationBrief(
            purpose = json.optString("purpose"),
            audience = json.optString("audience"),
            presenter = json.optString("presenter"),
            date = json.optString("date"),
            logoPath = json.optString("logoPath"),
            backgroundPath = json.optString("backgroundPath"),
        )
    }
}

data class PresentationReadiness(
    val hasCover: Boolean,
    val hasAgenda: Boolean,
    val hasBody: Boolean,
    val hasClosing: Boolean,
    val warnings: List<String>,
) {
    val ready: Boolean get() = hasCover && hasBody && hasClosing && warnings.isEmpty()
}

data class PptQualityReport(
    val slideCount: Int,
    val visualSlides: Int,
    val chartSlides: Int,
    val notesSlides: Int,
    val warnings: List<String>,
) {
    val passed: Boolean get() = warnings.isEmpty()
    fun summary(): String = "${slideCount} 页 · 视觉 ${visualSlides} · 图表 ${chartSlides} · 讲稿 ${notesSlides}${if (warnings.isEmpty()) " · 质量检查通过" else " · ${warnings.size} 项待处理"}"
}

/** Turns a Markdown-first draft into a standard presentation flow without hiding user-supplied pages. */
object PptDeckComposer {
    fun compose(project: DocumentProject): List<SlideSpec> {
        val source = project.effectiveSlides().toMutableList()
        if (source.isEmpty()) return listOf(SlideSpec(SlideLayout.COVER, project.title))
        if (source.first().layout != SlideLayout.COVER) {
            source.add(0, SlideSpec(SlideLayout.COVER, project.title, coverBody(project.brief)))
        } else if (source.first().body.isBlank()) {
            source[0] = source.first().copy(body = coverBody(project.brief))
        }
        val bodySlides = source.filter { it.layout !in setOf(SlideLayout.CLOSING, SlideLayout.COVER, SlideLayout.AGENDA) }
        if (bodySlides.size >= 2 && source.none { it.layout == SlideLayout.AGENDA }) {
            val topics = bodySlides
                .take(6).mapIndexed { index, slide -> "${index + 1}. ${slide.title.ifBlank { "主题 ${index + 1}" }}" }
            if (topics.size >= 2) source.add(1, SlideSpec(SlideLayout.AGENDA, "目录", topics.joinToString("\n")))
        }
        if (source.none { it.layout == SlideLayout.CLOSING }) {
            source += SlideSpec(SlideLayout.CLOSING, "谢谢", "Q&A")
        }
        return source
    }

    fun readiness(project: DocumentProject): PresentationReadiness {
        val slides = project.presentationSlides()
        val warnings = buildList {
            if (project.brief.purpose.isBlank()) add("未填写演示目标")
            if (project.brief.audience.isBlank()) add("未填写受众")
            slides.forEachIndexed { index, slide ->
                val page = index + 1
                val lines = slide.body.lines().count { it.isNotBlank() }
                if (slide.title.isBlank() && slide.layout !in setOf(SlideLayout.QUOTE, SlideLayout.CLOSING)) {
                    add("第 ${page} 页缺少标题")
                }
                if (lines > 8 && slide.layout in setOf(SlideLayout.TITLE_BODY, SlideLayout.TWO_COLUMN, SlideLayout.COMPARISON)) {
                    add("第 ${index + 1} 页文字较多，建议拆分或改用图表")
                }
                if (slide.title.length > 46) add("第 ${page} 页标题过长，建议压缩为一句结论")
                if (slide.layout == SlideLayout.DATA && slide.chart == null && slide.imagePath.isBlank()) {
                    add("第 ${page} 页为数据版式，建议填写图表数据或图片")
                }
                if (slide.imagePath.isNotBlank() && slide.layout !in setOf(SlideLayout.IMAGE_TEXT, SlideLayout.DATA)) {
                    add("第 ${page} 页已填写图片，但当前版式不会展示图片")
                }
                if (slide.chart != null && slide.layout != SlideLayout.DATA) {
                    add("第 ${page} 页已填写图表数据，建议改为数据图表版式")
                }
                if (slide.layout == SlideLayout.TABLE && parseTable(slide.body).firstOrNull().isNullOrEmpty()) {
                    add("第 ${page} 页为表格版式，建议使用 | 列 | 列 | 格式填写数据")
                }
                if (slide.layout == SlideLayout.PROCESS && slide.body.lines().none { it.contains("->") || it.contains("→") }) {
                    add("第 ${page} 页为流程图版式，建议每行填写“步骤 → 下一步”")
                }
                if (slide.layout == SlideLayout.METRICS && slide.body.lines().count { it.contains(":") || it.contains("：") } < 2) {
                    add("第 ${page} 页为指标版式，建议至少填写两项“指标: 数值”")
                }
            }
        }
        return PresentationReadiness(
            hasCover = slides.firstOrNull()?.layout == SlideLayout.COVER,
            hasAgenda = slides.any { it.layout == SlideLayout.AGENDA },
            hasBody = slides.any { it.layout !in setOf(SlideLayout.COVER, SlideLayout.AGENDA, SlideLayout.CLOSING) },
            hasClosing = slides.lastOrNull()?.layout == SlideLayout.CLOSING,
            warnings = warnings,
        )
    }

    fun qualityReport(project: DocumentProject): PptQualityReport {
        val slides = project.presentationSlides()
        val warnings = readiness(project).warnings.toMutableList()
        val visualSlides = slides.count { it.imagePath.isNotBlank() || it.chart != null || it.layout in setOf(SlideLayout.TIMELINE, SlideLayout.PROCESS, SlideLayout.TABLE, SlideLayout.METRICS) }
        val chartSlides = slides.count { it.chart != null }
        val notesSlides = slides.count { it.notes.isNotBlank() }
        if (slides.size > 4 && visualSlides < slides.size / 3) warnings += "视觉页偏少，建议为关键页面增加图表、流程、表格、指标或真实图片"
        if (slides.size > 3 && notesSlides < slides.size / 2) warnings += "讲稿备注不足，建议为主要页面补充讲解要点"
        if (slides.count { it.layout == SlideLayout.TITLE_BODY } > slides.size * 0.7) warnings += "标题与正文页占比过高，建议转换部分页面为数据、流程、时间线或指标版式"
        return PptQualityReport(slides.size, visualSlides, chartSlides, notesSlides, warnings.distinct())
    }

    private fun coverBody(brief: PresentationBrief): String = listOf(brief.purpose, brief.presenter, brief.date)
        .filter(String::isNotBlank)
        .joinToString("\n")

    internal fun parseTable(body: String): List<List<String>> = body.lines()
        .filter { it.contains("|") && !it.replace("|", "").trim().all { it == '-' || it == ':' || it.isWhitespace() } }
        .map { line -> line.trim().trim('|').split('|').map(String::trim) }
}

enum class DocumentAspectRatio(val label: String, val width: Int, val height: Int) {
    WIDESCREEN("16:9", 12_192_000, 6_858_000),
    STANDARD("4:3", 9_144_000, 6_858_000);
}

enum class SlideLayout(val label: String) {
    COVER("封面"),
    AGENDA("目录"),
    SECTION("章节"),
    TITLE_BODY("标题与正文"),
    TWO_COLUMN("双栏"),
    COMPARISON("对比"),
    QUOTE("引言"),
    IMAGE_TEXT("图片与文字"),
    DATA("数据图表"),
    TIMELINE("时间线"),
    PROCESS("流程图"),
    TABLE("表格"),
    METRICS("关键指标"),
    CLOSING("结尾");
}

enum class ChartType(val label: String) {
    BAR("柱状图"),
    LINE("折线图"),
    PIE("占比图");
}

data class SlideSpec(
    val layout: SlideLayout = SlideLayout.TITLE_BODY,
    val title: String = "",
    val body: String = "",
    val notes: String = "",
    /** Relative to the active workspace. Supports png, jpg and jpeg only. */
    val imagePath: String = "",
    val chart: ChartSpec? = null,
    val chartType: ChartType = ChartType.BAR,
    val imageFocus: ImageFocus = ImageFocus.CENTER,
    val visualStyle: SlideVisualStyle = SlideVisualStyle.AUTO,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("layout", layout.name)
        put("title", title)
        put("body", body)
        put("notes", notes)
        put("imagePath", imagePath)
        chart?.let { put("chart", it.toJson()) }
        put("chartType", chartType.name)
        put("imageFocus", imageFocus.name)
        put("visualStyle", visualStyle.name)
    }

    companion object {
        fun fromJson(json: JSONObject): SlideSpec = SlideSpec(
            layout = SlideLayout.entries.firstOrNull { it.name == json.optString("layout") } ?: SlideLayout.TITLE_BODY,
            title = json.optString("title"),
            body = json.optString("body"),
            notes = json.optString("notes"),
            imagePath = json.optString("imagePath"),
            chart = json.optJSONObject("chart")?.let(ChartSpec::fromJson),
            chartType = ChartType.entries.firstOrNull { it.name == json.optString("chartType") } ?: ChartType.BAR,
            imageFocus = ImageFocus.entries.firstOrNull { it.name == json.optString("imageFocus") } ?: ImageFocus.CENTER,
            visualStyle = SlideVisualStyle.entries.firstOrNull { it.name == json.optString("visualStyle") } ?: SlideVisualStyle.AUTO,
        )
    }
}

enum class ImageFocus(val label: String) { CENTER("居中"), TOP("顶部"), BOTTOM("底部"), LEFT("左侧"), RIGHT("右侧") }

enum class SlideVisualStyle(val label: String) { AUTO("自动"), TEXT("文字"), VISUAL("视觉"), EMPHASIS("强调") }

data class ChartSpec(val title: String = "", val values: List<ChartValue> = emptyList()) {
    fun validate(slideIndex: Int) {
        require(values.isNotEmpty() && values.size <= 12) { "第 ${slideIndex + 1} 页图表需要 1 至 12 个数据项" }
        require(values.all { it.label.length <= 60 && it.value.isFinite() && it.value >= 0.0 }) { "第 ${slideIndex + 1} 页图表数据无效" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("values", JSONArray().apply { values.forEach { put(it.toJson()) } })
    }

    companion object {
        /** Compact editor syntax: one item per line, for example `收入: 128`. */
        fun fromCompactText(title: String, raw: String): ChartSpec? {
            val values = raw.lines().mapNotNull { line ->
                val split = line.indexOfAny(charArrayOf(':', '：', ','))
                if (split <= 0) return@mapNotNull null
                val label = line.substring(0, split).trim()
                val value = line.substring(split + 1).trim().toDoubleOrNull()
                if (label.isBlank() || value == null || !value.isFinite() || value < 0.0) null else ChartValue(label.take(60), value)
            }
            return values.takeIf { it.isNotEmpty() }?.let { ChartSpec(title.trim(), it) }
        }

        fun fromJson(json: JSONObject): ChartSpec {
            val values = json.optJSONArray("values")?.let { array ->
                buildList {
                    repeat(array.length()) { index ->
                        array.optJSONObject(index)?.let { add(ChartValue(it.optString("label"), it.optDouble("value"))) }
                    }
                }
            }.orEmpty()
            return ChartSpec(json.optString("title"), values)
        }
    }
}

data class ChartValue(val label: String, val value: Double) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("value", value)
}

fun ChartSpec.toCompactText(): String = values.joinToString("\n") { "${it.label}: ${if (it.value % 1.0 == 0.0) it.value.toInt() else it.value}" }

object DocumentProjectStore {
    private const val PROJECTS_DIR = "documents"
    private const val PROJECT_FILE = "project.json"

    fun save(root: File, project: DocumentProject): File {
        project.validate()
        val directory = WorkspaceFileOps.resolve(root, "$PROJECTS_DIR/${project.id}").getOrThrow()
        require(directory.mkdirs() || directory.isDirectory) { "无法创建文档项目目录" }
        val file = WorkspaceFileOps.resolve(root, "$PROJECTS_DIR/${project.id}/$PROJECT_FILE").getOrThrow()
        val temporary = File(directory, ".$PROJECT_FILE.tmp")
        temporary.writeText(project.toJson().toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
        return file
    }

    fun load(root: File, projectId: String): DocumentProject = runCatching {
        require(projectId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "项目标识无效" }
        val file = WorkspaceFileOps.resolve(root, "$PROJECTS_DIR/$projectId/$PROJECT_FILE").getOrThrow()
        require(file.isFile && file.length() <= 5_000_000) { "文档项目不存在或内容过大" }
        DocumentProject.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }.getOrElse { throw IllegalArgumentException(it.message ?: "无法读取文档项目") }

    fun list(root: File): List<DocumentProject> = runCatching {
        val directory = WorkspaceFileOps.resolve(root, PROJECTS_DIR).getOrThrow()
        directory.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { child ->
            runCatching { load(root, child.name) }.getOrNull()
        }.sortedByDescending(DocumentProject::updatedAt)
    }.getOrDefault(emptyList())

    fun delete(root: File, projectId: String) {
        require(projectId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "项目标识无效" }
        val directory = WorkspaceFileOps.resolve(root, "$PROJECTS_DIR/$projectId").getOrThrow()
        require(directory.isDirectory) { "文档项目不存在" }
        require(directory.listFiles().orEmpty().all { it.delete() }) { "无法删除文档项目文件" }
        require(directory.delete()) { "无法删除文档项目目录" }
    }
}
