package com.hsucode.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class GeneratedDocument(
    val file: File,
    val format: DocumentExport.Format,
    val location: String?,
    val uri: android.net.Uri?
)

/** Document/PPT export workbench with preview, draft recovery and share/open actions. */
@Composable
fun DocumentWorkbenchScreen(
    workspaceRoot: String,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val colors = LocalHsuColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("HSUCODE 工作成果") }
    var fileName by remember { mutableStateOf("工作成果") }
    var content by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf(DocumentTheme.HSUCODE) }
    var template by remember { mutableStateOf(PresentationTemplate.HSUCODE) }
    var aspectRatio by remember { mutableStateOf(DocumentAspectRatio.WIDESCREEN) }
    var purpose by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("") }
    var presenter by remember { mutableStateOf("") }
    var presentationDate by remember { mutableStateOf("") }
    var logoPath by remember { mutableStateOf("") }
    var backgroundPath by remember { mutableStateOf("") }
    var transition by remember { mutableStateOf(PresentationTransition.FADE) }
    var slides by remember { mutableStateOf<List<SlideSpec>>(emptyList()) }
    var projectId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var expandedSlideIndex by remember { mutableStateOf<Int?>(null) }
    var layoutPickerIndex by remember { mutableStateOf<Int?>(null) }
    var chartPickerIndex by remember { mutableStateOf<Int?>(null) }
    var focusPickerIndex by remember { mutableStateOf<Int?>(null) }
    var imagePickerIndex by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var draftLoaded by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var savedProjects by remember { mutableStateOf<List<DocumentProject>>(emptyList()) }
    var lastGenerated by remember { mutableStateOf<GeneratedDocument?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "正在导入并读取文档…"
            statusIsError = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    val imports = WorkspaceFileOps.resolve(root, "imports").getOrThrow().apply { mkdirs() }
                    val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else null
                    } ?: "import-${System.currentTimeMillis()}"
                    val imported = WorkspaceFileOps.uniqueChild(imports, displayName.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_"))
                    try {
                        require(imported.extension.lowercase(Locale.ROOT) != "pdf") {
                            "PDF 暂不能在编辑器中直接导入；请在聊天中用 document_convert 配置 pdftotext 后转换为 Markdown 或文本"
                        }
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            imported.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var copied = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count <= 0) break
                                    copied += count
                                    require(copied <= 24L * 1024L * 1024L) { "导入文件超过 24 MB，暂不支持读取" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error("无法打开所选文件")
                        val extracted = DocumentTextExtractor.extract(imported, 100_000)
                        Triple(imported, extracted.text, extracted.truncated)
                    } catch (error: Throwable) {
                        imported.delete()
                        throw error
                    }
                }
            }.onSuccess { (imported, extracted, truncated) ->
                title = imported.nameWithoutExtension.ifBlank { title }
                fileName = imported.nameWithoutExtension.ifBlank { fileName }
                content = extracted
                slides = DocumentProject.fromMarkdown(title, extracted).effectiveSlides()
                projectId = UUID.randomUUID().toString()
                status = "已导入 ${imported.name}${if (truncated) "（内容已截取）" else ""}"
            }.onFailure { error ->
                status = "导入失败：${error.message ?: "未知错误"}"
                statusIsError = true
            }
            busy = false
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val slideIndex = imagePickerIndex
        imagePickerIndex = null
        if (uri == null || slideIndex == null || slideIndex !in slides.indices) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "正在导入图片…"
            statusIsError = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                    val extension = when {
                        mime.contains("png") -> "png"
                        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                        else -> error("仅支持 PNG/JPG 图片")
                    }
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    val assets = WorkspaceFileOps.resolve(root, "imports/ppt-assets").getOrThrow().apply { mkdirs() }
                    val target = WorkspaceFileOps.uniqueChild(assets, "slide-${slideIndex + 1}.$extension")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var copied = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count <= 0) break
                                    copied += count
                                    require(copied <= 12L * 1024L * 1024L) { "图片超过 12 MB" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error("无法打开所选图片")
                        "imports/ppt-assets/${target.name}"
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                }
            }.onSuccess { path ->
                slides = slides.mapIndexed { index, slide -> if (index == slideIndex) slide.copy(imagePath = path) else slide }
                status = "已导入图片"
            }.onFailure { error ->
                status = "图片导入失败：${error.message ?: "未知错误"}"
                statusIsError = true
            }
            busy = false
        }
    }

    fun refreshRecent() {
        scope.launch {
            recentFiles = withContext(Dispatchers.IO) {
                runCatching {
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    File(root, "exports").listFiles().orEmpty()
                        .filter { it.isFile && !it.name.startsWith(".") }
                        .sortedByDescending { it.lastModified() }
                        .take(8)
                }.getOrDefault(emptyList())
            }
        }
    }

    fun refreshProjects() {
        scope.launch {
            savedProjects = withContext(Dispatchers.IO) {
                runCatching {
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    DocumentProjectStore.list(root)
                }.getOrDefault(emptyList())
            }
        }
    }

    fun loadProject(project: DocumentProject) {
        projectId = project.id
        title = project.title
        fileName = project.fileName
        content = project.sourceMarkdown
        theme = project.theme
        template = project.template
        aspectRatio = project.aspectRatio
        purpose = project.brief.purpose
        audience = project.brief.audience
        presenter = project.brief.presenter
        presentationDate = project.brief.date
        logoPath = project.brief.logoPath
        backgroundPath = project.brief.backgroundPath
        transition = project.transition
        slides = project.slides
        status = "已打开项目：${project.title}"
        statusIsError = false
    }

    LaunchedEffect(Unit) {
        val draft = withContext(Dispatchers.IO) { DocumentDraftStore.read(context) }
        if (draft != null) {
            title = draft.optString("title", title)
            fileName = draft.optString("fileName", fileName)
            content = draft.optString("content", "")
            theme = DocumentTheme.entries.firstOrNull { it.name == draft.optString("theme") } ?: DocumentTheme.HSUCODE
            template = PresentationTemplate.entries.firstOrNull { it.name == draft.optString("template") } ?: PresentationTemplate.HSUCODE
            aspectRatio = DocumentAspectRatio.entries.firstOrNull { it.name == draft.optString("aspectRatio") } ?: DocumentAspectRatio.WIDESCREEN
            purpose = draft.optString("purpose")
            audience = draft.optString("audience")
            presenter = draft.optString("presenter")
            presentationDate = draft.optString("presentationDate")
            logoPath = draft.optString("logoPath")
            backgroundPath = draft.optString("backgroundPath")
            transition = PresentationTransition.entries.firstOrNull { it.name == draft.optString("transition") } ?: PresentationTransition.FADE
            slides = draft.optJSONArray("slides")?.let { array ->
                buildList {
                    repeat(array.length()) { index -> array.optJSONObject(index)?.let { add(SlideSpec.fromJson(it)) } }
                }
            }.orEmpty()
        }
        if (slides.isEmpty() && content.isNotBlank()) slides = DocumentProject.fromMarkdown(title, content).effectiveSlides()
        draftLoaded = true
    }
    LaunchedEffect(title, fileName, content, theme, template, aspectRatio, purpose, audience, presenter, presentationDate, logoPath, backgroundPath, transition, slides, draftLoaded) {
        if (draftLoaded) {
            delay(700)
            withContext(Dispatchers.IO) { DocumentDraftStore.write(context, title, fileName, content, theme, template, aspectRatio, purpose, audience, presenter, presentationDate, logoPath, backgroundPath, transition, slides) }
        }
    }
    LaunchedEffect(workspaceRoot) {
        refreshRecent()
        refreshProjects()
    }

    fun clearDraft() {
        title = "HSUCODE 工作成果"
        fileName = "工作成果"
        content = ""
        theme = DocumentTheme.HSUCODE
        template = PresentationTemplate.HSUCODE
        aspectRatio = DocumentAspectRatio.WIDESCREEN
        purpose = ""
        audience = ""
        presenter = ""
        presentationDate = ""
        logoPath = ""
        backgroundPath = ""
        transition = PresentationTransition.FADE
        slides = emptyList()
        projectId = UUID.randomUUID().toString()
        status = "草稿已清空"
        statusIsError = false
        scope.launch(Dispatchers.IO) { DocumentDraftStore.clear(context) }
    }

    fun create(format: DocumentExport.Format) {
        if (title.isBlank() || (content.isBlank() && slides.isEmpty())) {
            status = "请先填写标题和页面内容"
            statusIsError = true
            return
        }
        if (fileName.isBlank()) {
            status = "请填写文件名"
            statusIsError = true
            return
        }
        busy = true
        status = "正在生成 ${format.name.lowercase(Locale.ROOT)}…"
        statusIsError = false
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    val brief = PresentationBrief(purpose, audience, presenter, presentationDate, logoPath, backgroundPath)
                    val project = DocumentProject(
                        id = projectId,
                        title = title,
                        fileName = fileName,
                        theme = theme,
                        template = template,
                        aspectRatio = aspectRatio,
                        brief = brief,
                        transition = transition,
                        sourceMarkdown = content,
                        slides = slides,
                    )
                    DocumentProjectStore.save(root, project)
                    val created = DocumentExport.create(root, format, project, fileName = fileName)
                    val published = WorkspaceContext.publishChatFileResult(
                        created.file,
                        mimeType = mimeType(format)
                    )
                    val shareUri = published?.uri ?: fileProviderUri(context, created.file)
                    GeneratedDocument(created.file, format, published?.location, shareUri)
                }
            }
            result.onSuccess { generated ->
                lastGenerated = generated
                status = buildString {
                    append("已生成：${generated.file.name}")
                    if (generated.location != null) append("\n已同步：${generated.location}")
                    else append("\n工作区副本已保留，公共下载目录同步失败")
                }
                statusIsError = generated.location == null
                refreshRecent()
                refreshProjects()
            }.onFailure {
                status = "生成失败：${it.message ?: "未知错误"}"
                statusIsError = true
            }
            busy = false
        }
    }

    fun openDocument(document: GeneratedDocument) {
        val uri = document.uri ?: fileProviderUri(context, document.file)
        if (uri == null) {
            status = "当前文件位置无法提供给外部应用，请从文件工作区打开"
            statusIsError = true
            return
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType(document.format))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { error ->
            status = if (error is ActivityNotFoundException) "没有可打开此格式的应用" else "打开失败：${error.message}"
            statusIsError = true
        }
    }

    fun shareDocument(document: GeneratedDocument) {
        val uri = document.uri ?: fileProviderUri(context, document.file)
        if (uri == null) {
            status = "当前文件位置无法分享，请从文件工作区导出"
            statusIsError = true
            return
        }
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mimeType(document.format)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享文档"))
        }.onFailure { status = "分享失败：${it.message ?: "未知错误"}"; statusIsError = true }
    }

    Column(
        Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("文档与演示", color = colors.ink, style = MaterialTheme.typography.titleLarge)
                Text("PPTX 保留结构化版式；DOCX 为可编辑正文", color = colors.sub, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation")) }, enabled = !busy) {
                Icon(Icons.Outlined.FileOpen, contentDescription = "导入文档", tint = colors.sub)
            }
            IconButton(onClick = { showPreview = true }, enabled = content.isNotBlank() || slides.isNotEmpty()) {
                Icon(Icons.Outlined.Visibility, contentDescription = "预览", tint = colors.sub)
            }
        }

        Surface(color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("编辑内容", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fileName, onValueChange = { fileName = it }, label = { Text("文件名（无需扩展名）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    placeholder = { Text("支持标题、列表、代码块、引用、表格和链接") },
                    minLines = 12,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("逐页编排", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("每页独立保存，预览和 PPTX 导出会使用这里的版式、图片、图表与讲稿备注。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { slides = DocumentProject.fromMarkdown(title.ifBlank { "未命名" }, content).effectiveSlides() },
                        enabled = !busy && content.isNotBlank(), modifier = Modifier.weight(1f)
                    ) { Text("从正文重建页面") }
                    OutlinedButton(
                        onClick = { slides = slides + SlideSpec(title = "新页面") },
                        enabled = !busy, modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Outlined.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("新增页面") }
                }
                if (slides.isNotEmpty()) {
                    Text("${slides.size} 页 · 点按页面标题展开编辑", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
                slides.forEachIndexed { index, slide ->
                    Surface(color = colors.bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { expandedSlideIndex = if (expandedSlideIndex == index) null else index }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("第 ${index + 1} 页 · ${slide.layout.label}", style = MaterialTheme.typography.labelLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(slide.title.ifBlank { "无标题" }, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(if (expandedSlideIndex == index) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = if (expandedSlideIndex == index) "收起页面" else "展开页面")
                                }
                                IconButton(onClick = { if (index > 0) slides = slides.toMutableList().also { it.add(index - 1, it.removeAt(index)) } }, enabled = !busy && index > 0, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移") }
                                IconButton(onClick = { if (index < slides.lastIndex) slides = slides.toMutableList().also { it.add(index + 1, it.removeAt(index)) } }, enabled = !busy && index < slides.lastIndex, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移") }
                                IconButton(onClick = { slides = slides.filterIndexed { itemIndex, _ -> itemIndex != index } }, enabled = !busy && slides.size > 1, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除") }
                            }
                            if (expandedSlideIndex == index) {
                                OutlinedTextField(value = slide.title, onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(title = value) else item } }, label = { Text("页面标题") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = slide.body, onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(body = value) else item } }, label = { Text("页面正文") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { layoutPickerIndex = index }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("版式：${slide.layout.label}") }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { imagePickerIndex = index; imageLauncher.launch(arrayOf("image/png", "image/jpeg")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("选择图片") }
                                    OutlinedButton(onClick = { slides = slides.mapIndexed { i, item -> if (i == index) item.copy(imagePath = "") else item } }, enabled = !busy && slide.imagePath.isNotBlank(), modifier = Modifier.weight(1f)) { Text("移除图片") }
                                }
                                OutlinedTextField(value = slide.imagePath, onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(imagePath = value) else item } }, label = { Text("图片路径（可选）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                if (slide.imagePath.isNotBlank()) {
                                    OutlinedButton(onClick = { focusPickerIndex = index }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("图片焦点：${slide.imageFocus.label}") }
                                }
                                if (slide.layout == SlideLayout.DATA) {
                                    val chartText = slide.chart?.toCompactText().orEmpty()
                                    OutlinedTextField(value = slide.chart?.title.orEmpty(), onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(chart = ChartSpec(value, item.chart?.values.orEmpty())) else item } }, label = { Text("图表标题") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = chartText, onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(chart = ChartSpec(item.chart?.title.orEmpty(), ChartSpec.fromCompactText(item.chart?.title.orEmpty(), value)?.values.orEmpty())) else item } }, label = { Text("图表数据（每行：标签: 数值）") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = { chartPickerIndex = index }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("图表：${slide.chartType.label}") }
                                }
                                OutlinedTextField(value = slide.notes, onValueChange = { value -> slides = slides.mapIndexed { i, item -> if (i == index) item.copy(notes = value) else item } }, label = { Text("讲稿备注") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                Text("演示策划", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = purpose, onValueChange = { purpose = it }, label = { Text("目标（汇报 / 答辩 / 宣讲 / 提案）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = audience, onValueChange = { audience = it }, label = { Text("受众") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = logoPath, onValueChange = { logoPath = it }, label = { Text("品牌标识路径（可选，工作区内 PNG/JPG）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = backgroundPath, onValueChange = { backgroundPath = it }, label = { Text("全局背景路径（可选，工作区内 PNG/JPG）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = presenter, onValueChange = { presenter = it }, label = { Text("汇报人") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = presentationDate, onValueChange = { presentationDate = it }, label = { Text("日期") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                }
                Text("统一模板", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                PresentationTemplate.entries.forEach { option ->
                    OutlinedButton(
                        onClick = { template = option; theme = option.defaultTheme },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (template == option) "✓ ${option.label}" else option.label, modifier = Modifier.weight(1f))
                        Text("${option.headingTypeface} · ${option.margin.label}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("主题", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(42.dp))
                    DocumentTheme.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { theme = option },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) { Text(if (theme == option) "✓ ${option.label}" else option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("比例", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(42.dp))
                    DocumentAspectRatio.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { aspectRatio = option },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) { Text(if (aspectRatio == option) "✓ ${option.label}" else option.label) }
                    }
                    Spacer(Modifier.weight(2f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("切换", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(42.dp))
                    PresentationTransition.entries.forEach { option ->
                        OutlinedButton(onClick = { transition = option }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            Text(if (transition == option) "✓ ${option.label}" else option.label, maxLines = 1)
                        }
                    }
                }
                val projectPreview = remember(title, fileName, content, slides, theme, template, aspectRatio, purpose, audience, presenter, presentationDate, logoPath, backgroundPath, transition) {
                    DocumentProject(
                        title = title.ifBlank { "未命名" }, fileName = fileName.ifBlank { title.ifBlank { "未命名" } }, sourceMarkdown = content, slides = slides,
                        theme = theme, template = template, aspectRatio = aspectRatio,
                        brief = PresentationBrief(purpose, audience, presenter, presentationDate, logoPath, backgroundPath), transition = transition
                    )
                }
                val outline = projectPreview.presentationSlides().take(12)
                val quality = projectPreview.let(PptDeckComposer::qualityReport)
                if (content.isNotBlank() || slides.isNotEmpty()) {
                    Text("投影片大纲 · ${projectPreview.presentationSlides().size} 页", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                    Text("质量状态：${quality.summary()}", style = MaterialTheme.typography.bodySmall, color = if (quality.passed) colors.green else colors.yellow)
                    outline.forEachIndexed { index, slide ->
                        Text("${index + 1}. ${slide.title.ifBlank { "无标题" }} · ${slide.layout.label}", style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    quality.warnings.take(3).forEach { warning -> Text(warning, style = MaterialTheme.typography.bodySmall, color = colors.yellow) }
                    if (outline.size == 12 && projectPreview.presentationSlides().size > 12) {
                        Text("仅显示前 12 页，导出会保留全部页面", style = MaterialTheme.typography.bodySmall, color = colors.faint)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    DocumentDraftStore.write(context, title, fileName, content, theme, template, aspectRatio, purpose, audience, presenter, presentationDate, logoPath, backgroundPath, transition, slides)
                                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                                    DocumentProjectStore.save(root, DocumentProject(
                                        id = projectId, title = title.ifBlank { "未命名文档" }, fileName = fileName.ifBlank { "未命名文档" },
                                        theme = theme, template = template, aspectRatio = aspectRatio,
                                        brief = PresentationBrief(purpose, audience, presenter, presentationDate, logoPath, backgroundPath),
                                        transition = transition, sourceMarkdown = content, slides = slides
                                    ))
                                }
                            }
                            result.onSuccess {
                                status = "草稿与项目已保存"
                                statusIsError = false
                                refreshProjects()
                            }.onFailure {
                                status = "保存失败：${it.message ?: "未知错误"}"
                                statusIsError = true
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Save, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("保存草稿")
                    }
                    OutlinedButton(onClick = ::clearDraft, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("清空")
                    }
                }
            }
        }

        Text("导出格式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("PDF 和 HTML 将按投影片导出；DOCX 导出正文、表格和基础文字样式。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { create(DocumentExport.Format.PPTX) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Archive, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("PPTX") }
            Button(onClick = { create(DocumentExport.Format.DOCX) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Description, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("DOCX") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { create(DocumentExport.Format.PDF) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("PDF") }
            OutlinedButton(onClick = { create(DocumentExport.Format.HTML) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FileDownload, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("HTML") }
        }

        if (status.isNotBlank()) {
            Text(status, color = if (statusIsError) colors.red else colors.sub, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        lastGenerated?.let { document ->
            Surface(color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最近生成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(document.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openDocument(document) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.OpenInNew, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("打开") }
                        OutlinedButton(onClick = { shareDocument(document) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("分享") }
                    }
                }
            }
        }
        if (recentFiles.isNotEmpty()) {
            Text("导出历史", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Surface(color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    recentFiles.forEachIndexed { index, file ->
                        val format = formatFor(file)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).background(colors.activeBg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Description, contentDescription = null, tint = colors.green, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${format.name} · ${file.length() / 1024} KB · ${formatDate(file.lastModified())}", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                            }
                            IconButton(onClick = { openDocument(GeneratedDocument(file, format, null, fileProviderUri(context, file))) }) { Icon(Icons.Outlined.OpenInNew, contentDescription = "打开") }
                        }
                        if (index < recentFiles.lastIndex) HorizontalDivider(color = colors.border, modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
        if (savedProjects.isNotEmpty()) {
            Text("项目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Surface(color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    savedProjects.take(8).forEachIndexed { index, project ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(project.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${project.presentationSlides().size} 页 · ${formatDate(project.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                            }
                            IconButton(onClick = { loadProject(project) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = "打开项目")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                                            DocumentProjectStore.delete(root, project.id)
                                        }
                                    }.onSuccess {
                                        if (project.id == projectId) projectId = UUID.randomUUID().toString()
                                        status = "已删除项目：${project.title}"
                                        statusIsError = false
                                        refreshProjects()
                                    }.onFailure {
                                        status = "删除项目失败：${it.message ?: "未知错误"}"
                                        statusIsError = true
                                    }
                                }
                            }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除项目", tint = colors.red)
                            }
                        }
                        if (index < savedProjects.take(8).lastIndex) HorizontalDivider(color = colors.border, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = onOpenFiles, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Text("在文件工作区查看全部文件")
        }
    }

    layoutPickerIndex?.let { index ->
        if (index in slides.indices) {
            AlertDialog(
                onDismissRequest = { layoutPickerIndex = null },
                title = { Text("选择页面版式") },
                text = {
                    Column(Modifier.height(400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlideLayout.entries.forEach { option ->
                            OutlinedButton(onClick = {
                                slides = slides.mapIndexed { itemIndex, item -> if (itemIndex == index) item.copy(layout = option) else item }
                                layoutPickerIndex = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (slides[index].layout == option) "✓ ${option.label}" else option.label)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { layoutPickerIndex = null }) { Text("取消") } }
            )
        } else layoutPickerIndex = null
    }

    chartPickerIndex?.let { index ->
        if (index in slides.indices) {
            AlertDialog(
                onDismissRequest = { chartPickerIndex = null },
                title = { Text("选择图表类型") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChartType.entries.forEach { option ->
                            OutlinedButton(onClick = {
                                slides = slides.mapIndexed { itemIndex, item -> if (itemIndex == index) item.copy(chartType = option) else item }
                                chartPickerIndex = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (slides[index].chartType == option) "✓ ${option.label}" else option.label)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { chartPickerIndex = null }) { Text("取消") } }
            )
        } else chartPickerIndex = null
    }

    focusPickerIndex?.let { index ->
        if (index in slides.indices) {
            AlertDialog(
                onDismissRequest = { focusPickerIndex = null },
                title = { Text("选择图片焦点") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ImageFocus.entries.forEach { option ->
                            OutlinedButton(onClick = {
                                slides = slides.mapIndexed { itemIndex, item -> if (itemIndex == index) item.copy(imageFocus = option) else item }
                                focusPickerIndex = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (slides[index].imageFocus == option) "✓ ${option.label}" else option.label)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { focusPickerIndex = null }) { Text("取消") } }
            )
        } else focusPickerIndex = null
    }

    if (showPreview) {
        val previewProject = DocumentProject(
            title = title.ifBlank { "未命名" },
            fileName = fileName.ifBlank { title.ifBlank { "未命名" } },
            sourceMarkdown = content,
            slides = slides,
            theme = theme,
            template = template,
            aspectRatio = aspectRatio,
            brief = PresentationBrief(purpose, audience, presenter, presentationDate, logoPath, backgroundPath),
            transition = transition,
        )
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("投影片预览 · ${previewProject.presentationSlides().size} 页") },
            text = {
                Column(Modifier.height(460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模板：${template.label} · ${theme.label} · ${aspectRatio.label}", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                    previewProject.presentationSlides().forEachIndexed { index, slide ->
                        SlideThumbnail(
                            slide = slide,
                            index = index,
                            total = previewProject.presentationSlides().size,
                            project = previewProject,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("关闭") } }
        )
    }
}

/** Lightweight, dependency-free slide preview that preserves the exported canvas ratio. */
@Composable
private fun SlideThumbnail(
    slide: SlideSpec,
    index: Int,
    total: Int,
    project: DocumentProject,
) {
    val theme = project.theme
    val background = Color(android.graphics.Color.parseColor("#${theme.background}"))
    val foreground = Color(android.graphics.Color.parseColor("#${theme.foreground}"))
    val accent = Color(android.graphics.Color.parseColor("#${theme.accent}"))
    val ratio = if (project.aspectRatio == DocumentAspectRatio.WIDESCREEN) 16f / 9f else 4f / 3f
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (project.template.backgroundStyle != BackgroundStyle.SOLID) {
                Box(
                    Modifier.fillMaxWidth().height(5.dp).background(accent)
                )
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        slide.layout.label,
                        color = foreground.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    if (project.brief.logoPath.isNotBlank()) {
                        Text("品牌", color = accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    slide.title.ifBlank { "无标题" },
                    color = foreground,
                    style = if (slide.layout == SlideLayout.COVER || slide.layout == SlideLayout.CLOSING) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val lines = slide.body.lines().filter { it.isNotBlank() }.take(5)
                if (lines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.forEach { line ->
                            Text(
                                text = if (slide.layout in setOf(SlideLayout.TITLE_BODY, SlideLayout.AGENDA, SlideLayout.TWO_COLUMN, SlideLayout.COMPARISON, SlideLayout.DATA)) "• ${line.trimStart().removePrefix("-").trim()}" else line,
                                color = foreground.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (index != 0 && index != total - 1) {
                    Text(
                        "${project.brief.presenter.takeIf { it.isNotBlank() } ?: "HSUCODE"}  ·  $index / ${total - 1}",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private object DocumentDraftStore {
    private const val FILE_NAME = "document-workbench-draft.json"

    fun read(context: Context): JSONObject? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.let(::JSONObject)
    }.getOrNull()

    fun write(
        context: Context,
        title: String,
        fileName: String,
        content: String,
        theme: DocumentTheme = DocumentTheme.HSUCODE,
        template: PresentationTemplate = PresentationTemplate.HSUCODE,
        aspectRatio: DocumentAspectRatio = DocumentAspectRatio.WIDESCREEN,
        purpose: String = "",
        audience: String = "",
        presenter: String = "",
        presentationDate: String = "",
        logoPath: String = "",
        backgroundPath: String = "",
        transition: PresentationTransition = PresentationTransition.FADE,
        slides: List<SlideSpec> = emptyList(),
    ) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(
                JSONObject()
                    .put("title", title)
                    .put("fileName", fileName)
                    .put("content", content.take(4_000_000))
                    .put("theme", theme.name)
                    .put("template", template.name)
                    .put("aspectRatio", aspectRatio.name)
                    .put("purpose", purpose)
                    .put("audience", audience)
                    .put("presenter", presenter)
                    .put("presentationDate", presentationDate)
                    .put("logoPath", logoPath)
                    .put("backgroundPath", backgroundPath)
                    .put("transition", transition.name)
                    .put("slides", org.json.JSONArray().apply { slides.forEach { put(it.toJson()) } })
                    .toString()
            )
        }
    }

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE_NAME).delete() } }
}

private fun fileProviderUri(context: Context, file: File): android.net.Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}.getOrNull()

private fun mimeType(format: DocumentExport.Format): String = when (format) {
    DocumentExport.Format.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    DocumentExport.Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    DocumentExport.Format.PDF -> "application/pdf"
    DocumentExport.Format.HTML -> "text/html"
}

private fun formatFor(file: File): DocumentExport.Format = when (file.extension.lowercase(Locale.ROOT)) {
    "pptx" -> DocumentExport.Format.PPTX
    "docx" -> DocumentExport.Format.DOCX
    "pdf" -> DocumentExport.Format.PDF
    else -> DocumentExport.Format.HTML
}

private fun formatDate(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(time))
