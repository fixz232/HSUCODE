package com.hsucode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject

class DocumentExportTest {
    @Test
    fun splitSlidesUsesMarkdownDividerAndKeepsSingleDocument() {
        assertEquals(listOf("第一张\n内容", "第二张"), DocumentExport.splitSlides("第一张\n内容\n---\n第二张"))
        assertEquals(listOf("普通正文"), DocumentExport.splitSlides("普通正文"))
    }

    @Test
    fun splitSlidesIgnoresEmptySections() {
        val slides = DocumentExport.splitSlides("---\n\n---\n有效内容\n---\n")
        assertEquals(1, slides.size)
        assertTrue(slides.single() == "有效内容")
    }

    @Test
    fun htmlUsesMarkdownBlocksAndEscapesUnsafeMarkup() {
        val root = Files.createTempDirectory("hsucode-doc-html").toFile()
        val created = DocumentExport.create(
            root,
            DocumentExport.Format.HTML,
            "测试标题",
            "## 小节\n\n- **重点**\n\n```kotlin\n<unsafe>\n```\n\n[官网](https://example.com)",
            fileName = "发布稿.html"
        )
        assertEquals("发布稿.html", created.file.name)
        val html = created.file.readText()
        assertTrue(html.contains("<h1 class=\"title\">小节</h1>"))
        assertTrue(html.contains("<strong>重点</strong>"))
        assertTrue(html.contains("&lt;unsafe&gt;"))
        assertTrue(html.contains("href=\"https://example.com\""))
        assertFalse(root.resolve("exports").listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun officeExportsHaveRequiredPartsAndDropIllegalXmlCharacters() {
        val root = Files.createTempDirectory("hsucode-doc-office").toFile()
        val content = "# 标题\n\n正文 😀\u0000\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        val docx = DocumentExport.create(root, DocumentExport.Format.DOCX, "文档", content).file
        val pptx = DocumentExport.create(root, DocumentExport.Format.PPTX, "演示", content).file

        ZipFile(docx).use { zip ->
            val xml = zip.getInputStream(requireNotNull(zip.getEntry("word/document.xml"))).bufferedReader().readText()
            assertTrue(xml.contains("正文 😀"))
            assertFalse(xml.contains("\u0000"))
            assertTrue(zip.getEntry("word/styles.xml") != null)
        }
        ZipFile(pptx).use { zip ->
            assertTrue(zip.getEntry("ppt/presentation.xml") != null)
            assertTrue(zip.getEntry("ppt/slides/slide1.xml") != null)
        }
    }

    @Test
    fun structuredProjectRoundTripsThemeAspectAndSlides() {
        val project = DocumentProject.fromMarkdown(
            title = "路线图",
            content = "# 封面\n\n目标\n---\n# 计划\n\n- 一\n- 二",
            theme = DocumentTheme.MIDNIGHT,
            aspectRatio = DocumentAspectRatio.STANDARD
        )
        val restored = DocumentProject.fromJson(JSONObject(project.toJson().toString()))
        assertEquals(DocumentTheme.MIDNIGHT, restored.theme)
        assertEquals(DocumentAspectRatio.STANDARD, restored.aspectRatio)
        assertEquals(2, restored.effectiveSlides().size)
        assertEquals("计划", restored.effectiveSlides()[1].title)
    }

    @Test
    fun projectRejectsTooManySlidesInsteadOfSilentlyDroppingThem() {
        val slides = (1..(DocumentProject.MAX_SLIDES + 1)).map { "第 $it 页" }
        val root = Files.createTempDirectory("hsucode-doc-limit").toFile()
        val error = runCatching {
            DocumentExport.create(root, DocumentExport.Format.PPTX, "超长演示", "", slides)
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("最多支持"))
    }

    @Test
    fun extractsTextFromGeneratedDocxAndPptx() {
        val root = Files.createTempDirectory("hsucode-doc-extract").toFile()
        val content = "# 项目标题\n\n正文内容\n---\n# 第二页\n\n第二页内容"
        val docx = DocumentExport.create(root, DocumentExport.Format.DOCX, "文档", content).file
        val pptx = DocumentExport.create(root, DocumentExport.Format.PPTX, "演示", content).file
        assertTrue(DocumentTextExtractor.extract(docx, 10_000).text.contains("正文内容"))
        assertTrue(DocumentTextExtractor.extract(pptx, 10_000).text.contains("第二页内容"))
    }

    @Test
    fun presentationTemplateCreatesStandardFlowAndAppliesVisualRules() {
        val root = Files.createTempDirectory("hsucode-ppt-template").toFile()
        val project = DocumentProject.fromMarkdown(
            title = "季度复盘",
            content = "# 进展\n\n- 指标提升\n---\n# 风险\n\n- 资源不足\n---\n# 计划\n\n- 下阶段行动",
            theme = DocumentTheme.MIDNIGHT,
            template = PresentationTemplate.DARK_STAGE,
            aspectRatio = DocumentAspectRatio.STANDARD,
            brief = PresentationBrief("经营汇报", "管理层", "寒酥", "2026-08-13"),
            transition = PresentationTransition.FADE,
        )
        val slides = project.presentationSlides()
        assertEquals(SlideLayout.COVER, slides.first().layout)
        assertEquals(SlideLayout.AGENDA, slides[1].layout)
        assertEquals(SlideLayout.CLOSING, slides.last().layout)
        assertTrue(PptDeckComposer.readiness(project).hasBody)

        val created = DocumentExport.create(root, DocumentExport.Format.PPTX, project).file
        ZipFile(created).use { zip ->
            val presentation = zip.getInputStream(requireNotNull(zip.getEntry("ppt/presentation.xml"))).bufferedReader().readText()
            val slide = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/slide1.xml"))).bufferedReader().readText()
            val theme = zip.getInputStream(requireNotNull(zip.getEntry("ppt/theme/theme1.xml"))).bufferedReader().readText()
            assertTrue(presentation.contains("cx=\"9144000\""))
            assertTrue(slide.contains("<p:bg>"))
            assertTrue(slide.contains("<p:transition"))
            assertTrue(slide.contains("寒酥"))
            assertTrue(theme.contains("Aptos Display"))
        }
    }

    @Test
    fun readinessWarnsWhenBriefOrSlideDensityIsMissing() {
        val project = DocumentProject.fromMarkdown(
            title = "说明",
            content = "# 主题\n\n" + (1..9).joinToString("\n") { "- 内容 $it" },
        )
        val readiness = PptDeckComposer.readiness(project)
        assertTrue(readiness.warnings.any { it.contains("演示目标") })
        assertTrue(readiness.warnings.any { it.contains("文字较多") })
    }

    @Test
    fun presentationEmbedsGlobalBackgroundAndLogoOnEverySlide() {
        val root = Files.createTempDirectory("hsucode-ppt-branding").toFile()
        root.resolve("brand.png").writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        root.resolve("background.jpg").writeBytes(byteArrayOf(0x04, 0x05, 0x06))
        val project = DocumentProject.fromMarkdown(
            title = "品牌演示",
            content = "# 第一页\n\n内容\n---\n# 第二页\n\n更多内容",
            brief = PresentationBrief(logoPath = "brand.png", backgroundPath = "background.jpg"),
        )
        val created = DocumentExport.create(root, DocumentExport.Format.PPTX, project).file
        ZipFile(created).use { zip ->
            val slideCount = project.presentationSlides().size
            repeat(slideCount) { index ->
                val xml = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/slide${index + 1}.xml"))).bufferedReader().readText()
                val rels = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/_rels/slide${index + 1}.xml.rels"))).bufferedReader().readText()
                assertTrue(xml.contains("全局背景"))
                assertTrue(xml.contains("品牌标识"))
                assertTrue(rels.contains("/image"))
            }
            assertTrue(zip.entries().asSequence().count { it.name.startsWith("ppt/media/") } >= slideCount * 2)
        }
    }

    @Test
    fun structuredSlidesKeepChartAndNotesInThePptxPackage() {
        val root = Files.createTempDirectory("hsucode-ppt-structured").toFile()
        val chart = ChartSpec.fromCompactText("季度收入", "Q1: 120\nQ2: 156")
        requireNotNull(chart)
        val project = DocumentProject(
            title = "结构化演示",
            sourceMarkdown = "备用正文",
            slides = listOf(
                SlideSpec(
                    layout = SlideLayout.DATA,
                    title = "收入趋势",
                    body = "按季度统计",
                    chart = chart,
                    notes = "讲稿备注：强调第二季度增长。",
                )
            ),
        )
        val restored = DocumentProject.fromJson(JSONObject(project.toJson().toString()))
        assertEquals("Q1: 120\nQ2: 156", restored.effectiveSlides().single().chart?.toCompactText())
        assertEquals("讲稿备注：强调第二季度增长。", restored.effectiveSlides().single().notes)

        val created = DocumentExport.create(root, DocumentExport.Format.PPTX, project).file
        ZipFile(created).use { zip ->
            val note = zip.getInputStream(requireNotNull(zip.getEntry("ppt/notesSlides/notesSlide2.xml"))).bufferedReader().readText()
            val rels = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/_rels/slide2.xml.rels"))).bufferedReader().readText()
            assertTrue(note.contains("讲稿备注"))
            assertTrue(rels.contains("/notesSlide"))
            assertTrue(zip.getEntry("ppt/notesMasters/notesMaster1.xml") != null)
        }
    }

    @Test
    fun readinessFlagsMisconfiguredVisualSlides() {
        val project = DocumentProject(
            title = "检查",
            slides = listOf(
                SlideSpec(SlideLayout.DATA, "数据", "摘要"),
                SlideSpec(SlideLayout.TITLE_BODY, "图表", chart = ChartSpec("图", listOf(ChartValue("A", 1.0)))),
            ),
        )
        val warnings = PptDeckComposer.readiness(project).warnings
        assertTrue(warnings.any { it.contains("数据版式") })
        assertTrue(warnings.any { it.contains("图表数据") })
    }

    @Test
    fun extendedLayoutsAndChartTypesAreSerializedToPptx() {
        val root = Files.createTempDirectory("hsucode-ppt-layouts").toFile()
        val project = DocumentProject(
            title = "业务方案",
            brief = PresentationBrief("提案", "管理层", "HSUCODE", "2026-08-13"),
            slides = listOf(
                SlideSpec(SlideLayout.TIMELINE, "实施节奏", "第一阶段\n第二阶段\n第三阶段", notes = "说明三阶段节奏"),
                SlideSpec(SlideLayout.PROCESS, "服务流程", "收集需求 → 制定方案 → 交付结果"),
                SlideSpec(SlideLayout.TABLE, "方案对比", "| 项目 | 方案 A |\n| --- | --- |\n| 成本 | 低 |"),
                SlideSpec(SlideLayout.METRICS, "核心指标", "增长率: 42%\n满意度: 96%"),
                SlideSpec(SlideLayout.DATA, "趋势", chart = ChartSpec("月度", listOf(ChartValue("一月", 10.0), ChartValue("二月", 18.0))), chartType = ChartType.LINE),
            ),
        )
        val created = DocumentExport.create(root, DocumentExport.Format.PPTX, project).file
        ZipFile(created).use { zip ->
            val timeline = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/slide2.xml"))).bufferedReader().readText()
            val process = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/slide3.xml"))).bufferedReader().readText()
            val table = zip.getInputStream(requireNotNull(zip.getEntry("ppt/slides/slide4.xml"))).bufferedReader().readText()
            assertTrue(timeline.contains("时间线"))
            assertTrue(process.contains("流程步骤"))
            assertTrue(table.contains("表格单元格"))
        }
        val quality = PptDeckComposer.qualityReport(project)
        assertTrue(quality.visualSlides >= 4)
        assertTrue(quality.notesSlides >= 1)
    }

    @Test
    fun structuredProjectExportsSlidesToHtml() {
        val root = Files.createTempDirectory("hsucode-structured-html").toFile()
        val project = DocumentProject(
            title = "交付方案",
            theme = DocumentTheme.MIDNIGHT,
            template = PresentationTemplate.DARK_STAGE,
            brief = PresentationBrief(presenter = "HSUCODE"),
            slides = listOf(
                SlideSpec(SlideLayout.TITLE_BODY, "结论", "优先完成核心闭环"),
                SlideSpec(SlideLayout.TABLE, "对比", "| 项目 | 状态 |\n| --- | --- |\n| 导出 | 已完成 |"),
                SlideSpec(SlideLayout.DATA, "趋势", chart = ChartSpec("月度", listOf(ChartValue("一月", 12.0), ChartValue("二月", 18.0))))
            )
        )
        val html = DocumentExport.create(root, DocumentExport.Format.HTML, project).file.readText()
        assertTrue(html.contains("class=\"deck\""))
        assertTrue(html.contains("交付方案"))
        assertTrue(html.contains("data:image/png;base64,"))
    }

    @Test
    fun documentProjectsCanBeSavedLoadedListedAndDeleted() {
        val root = Files.createTempDirectory("hsucode-project-store").toFile()
        val project = DocumentProject(title = "续编辑", slides = listOf(SlideSpec(title = "第一页", body = "内容")))
        DocumentProjectStore.save(root, project)
        assertEquals("续编辑", DocumentProjectStore.load(root, project.id).title)
        assertTrue(DocumentProjectStore.list(root).any { it.id == project.id })
        DocumentProjectStore.delete(root, project.id)
        assertFalse(DocumentProjectStore.list(root).any { it.id == project.id })
    }
}
