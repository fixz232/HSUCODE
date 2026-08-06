package com.hsucode.app

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
fun DeveloperWorkbenchScreen(
    workspaceRoot: String,
    onBack: () -> Unit,
    onFiles: () -> Unit,
    onDocuments: () -> Unit,
    onNetwork: () -> Unit,
    onExtensions: () -> Unit,
    onPrompts: () -> Unit,
    onTerminal: () -> Unit,
    onEnvironment: () -> Unit,
    onGit: () -> Unit
) {
    val colors = LocalHsuColors.current
    val runtimeReady = WorkspaceRuntime.hasLinux()
    val rootLabel = workspaceRoot.ifBlank { UserWorkspaceShell.displayPath() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkbenchTopBar("开发工作台", onBack)
        }
        item {
            Text(
                if (runtimeReady) "Ubuntu 环境已就绪" else "Android Shell 工作区",
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink
            )
            Text(
                "$rootLabel\n${WorkspaceManager.describe()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        item {
            WorkbenchRow(
                icon = Icons.Outlined.FolderOpen,
                title = "文件工作区",
                summary = "浏览、读写、搜索、导入导出、解压与 HTML 导出",
                onClick = onFiles
            )
        }
        item {
            WorkbenchRow(
                icon = Icons.Outlined.Description,
                title = "文档与演示",
                summary = "生成 PPTX、DOCX、PDF 和 HTML 交付文件",
                onClick = onDocuments
            )
        }
        item {
            WorkbenchRow(
                icon = Icons.Outlined.Http,
                title = "网络请求",
                summary = "HTTP 调试、网页外部访问、上传与下载",
                onClick = onNetwork
            )
        }
        item {
            WorkbenchRow(
                icon = Icons.Outlined.TravelExplore,
                title = "MCP 与 Skills 市场",
                summary = "远程 MCP、本地 npx/uvx 预设和可复用技能",
                onClick = onExtensions
            )
        }
        item {
            WorkbenchRow(
                icon = Icons.Outlined.Description,
                title = "提示词库",
                summary = "全局提示词、任务模板与快捷安装",
                onClick = onPrompts
            )
        }
        item {
            Text("运行与协作", style = MaterialTheme.typography.labelLarge, color = colors.sub, modifier = Modifier.padding(top = 10.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CompactAction(Icons.Outlined.Terminal, "终端", Modifier.weight(1f), onTerminal)
                CompactAction(Icons.Outlined.Code, "环境", Modifier.weight(1f), onEnvironment)
                CompactAction(Icons.Outlined.Archive, "Git", Modifier.weight(1f), onGit)
            }
        }
    }
}

@Composable
fun WorkbenchTopBar(title: String, onBack: () -> Unit) {
    val colors = LocalHsuColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun WorkbenchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val colors = LocalHsuColors.current
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = colors.green, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.sub, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = colors.faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompactAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
fun WorkspaceFilesScreen(workspaceRoot: String, onBack: () -> Unit) {
    val colors = LocalHsuColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileOps.Entry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showGlob by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorLoading by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileOps.Entry?>(null) }
    var unzipTarget by remember { mutableStateOf<WorkspaceFileOps.Entry?>(null) }
    var moveTarget by remember { mutableStateOf<WorkspaceFileOps.Entry?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    fun activeRoot(): Result<File> = WorkspaceFileOps.root(workspaceRoot)
    fun refresh() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activeRoot().fold(
                    onSuccess = { root -> WorkspaceFileOps.list(root, currentPath) },
                    onFailure = { Result.failure(it) }
                )
            }
            result.onSuccess { entries = it; error = null }.onFailure { error = it.message ?: "无法读取工作区" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = activeRoot().getOrThrow()
                    val name = displayName(context, uri).ifBlank { "imported_file" }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        WorkspaceFileOps.importFile(root, joinPath(currentPath, name), input).getOrThrow()
                    } ?: error("无法打开选中的文件")
                    name
                }
            }
            result.onSuccess { status = "已导入 $it"; refresh() }.onFailure { error = it.message ?: "导入失败" }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        WorkspaceFileOps.exportFile(activeRoot().getOrThrow(), source, output).getOrThrow()
                    } ?: error("无法创建导出文件")
                    source.substringAfterLast('/')
                }
            }
            result.onSuccess { status = "已导出 $it" }.onFailure { error = it.message ?: "导出失败" }
        }
    }

    LaunchedEffect(workspaceRoot, currentPath) { refresh() }
    LaunchedEffect(openFile) {
        val path = openFile ?: return@LaunchedEffect
        editorLoading = true
        val result = withContext(Dispatchers.IO) {
            activeRoot().fold(
                onSuccess = { WorkspaceFileOps.readText(it, path) },
                onFailure = { Result.failure(it) }
            )
        }
        result.onSuccess { editorText = it; error = null }.onFailure { error = it.message ?: "无法读取文件" }
        editorLoading = false
    }

    if (openFile != null) {
        FileEditorScreen(
            path = openFile!!,
            text = editorText,
            loading = editorLoading,
            onTextChange = { editorText = it },
            onBack = { openFile = null },
            onSave = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        activeRoot().fold(
                            onSuccess = { WorkspaceFileOps.writeText(it, openFile!!, editorText) },
                            onFailure = { Result.failure(it) }
                        )
                    }
                    result.onSuccess { status = "已保存"; openFile = null; refresh() }
                        .onFailure { error = it.message ?: "保存失败" }
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("文件工作区", style = MaterialTheme.typography.titleLarge)
                Text(
                    currentPath.ifBlank { workspaceRoot },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showSearch = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索文件", tint = colors.ink)
            }
            IconButton(onClick = { refresh() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = colors.ink)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建或导入", tint = colors.ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("新建文件") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { menuOpen = false; showNewFile = true })
                    DropdownMenuItem(text = { Text("新建文件夹") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { menuOpen = false; showNewFolder = true })
                    DropdownMenuItem(text = { Text("导入文件") }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) }, onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("按文件名查找") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = { menuOpen = false; showGlob = true })
                    DropdownMenuItem(text = { Text("创建 Web 项目") }, leadingIcon = { Icon(Icons.Outlined.Code, null) }, onClick = {
                        menuOpen = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { createWebStarter(activeRoot().getOrThrow(), currentPath) }
                            result.onSuccess { status = "已创建 Web 项目"; refresh() }.onFailure { error = it.message ?: "创建失败" }
                        }
                    })
                }
            }
        }
        if (currentPath.isNotBlank()) {
            TextButton(onClick = { currentPath = parentPath(currentPath) }, modifier = Modifier.padding(start = 16.dp)) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("上一级")
            }
        }
        status?.let { message -> InlineNotice(message, false) { status = null } }
        error?.let { message -> InlineNotice(message, true) { error = null } }
        if (entries.isEmpty() && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个文件夹是空的", style = MaterialTheme.typography.bodyMedium, color = colors.sub)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.relativePath }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        onOpen = {
                            if (entry.isDirectory) currentPath = entry.relativePath else openFile = entry.relativePath
                        },
                        onExport = { pendingExport = entry.relativePath; exportLauncher.launch(entry.file.name) },
                        onArchive = { unzipTarget = entry },
                        onHtml = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.exportHtml(it, entry.relativePath) } }
                                result.onSuccess { status = "已生成 ${it.name}"; refresh() }.onFailure { error = it.message ?: "转换失败" }
                            }
                        },
                        onDelete = { deleteTarget = entry },
                        onMove = { moveTarget = entry }
                    )
                }
            }
        }
    }

    if (showNewFile) {
        NameDialog("新建文件", "文件名，例如 notes.md", onDismiss = { showNewFile = false }) { name ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.writeText(it, joinPath(currentPath, name), "") } }
                result.onSuccess { showNewFile = false; openFile = joinPath(currentPath, name); refresh() }
                    .onFailure { error = it.message ?: "新建失败" }
            }
        }
    }
    if (showNewFolder) {
        NameDialog("新建文件夹", "文件夹名", onDismiss = { showNewFolder = false }) { name ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.createDirectory(it, joinPath(currentPath, name)) } }
                result.onSuccess { showNewFolder = false; refresh() }.onFailure { error = it.message ?: "创建失败" }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${target.file.name}") },
            text = { Text("该操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.delete(it, target.relativePath) } }
                        result.onSuccess { deleteTarget = null; refresh() }.onFailure { error = it.message ?: "删除失败" }
                    }
                }) { Text("删除", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
    unzipTarget?.let { archive ->
        var targetFolder by remember(archive.relativePath) { mutableStateOf(joinPath(parentPath(archive.relativePath), archive.file.name.substringBeforeLast('.'))) }
        AlertDialog(
            onDismissRequest = { unzipTarget = null },
            title = { Text("解压 ZIP") },
            text = { OutlinedTextField(targetFolder, { targetFolder = it }, label = { Text("目标文件夹") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.unzip(it, archive.relativePath, targetFolder) } }
                        result.onSuccess { count -> unzipTarget = null; status = "已解压 $count 项"; refresh() }
                            .onFailure { error = it.message ?: "解压失败" }
                    }
                }) { Text("解压") }
            },
            dismissButton = { TextButton(onClick = { unzipTarget = null }) { Text("取消") } }
        )
    }
    if (showSearch) {
        WorkspaceSearchDialog(
            rootProvider = ::activeRoot,
            onDismiss = { showSearch = false },
            onOpen = { path -> showSearch = false; openFile = path }
        )
    }
    if (showGlob) {
        WorkspaceGlobDialog(
            rootProvider = ::activeRoot,
            onDismiss = { showGlob = false },
            onOpen = { path -> showGlob = false; openFile = path }
        )
    }
    moveTarget?.let { target ->
        NameDialog("移动 ${target.file.name}", "目标相对路径，例如 archive/${target.file.name}", onDismiss = { moveTarget = null }) { destination ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { activeRoot().getOrThrow().let { WorkspaceFileOps.move(it, target.relativePath, destination) } }
                result.onSuccess { moveTarget = null; status = "已移动 ${target.file.name}"; refresh() }
                    .onFailure { error = it.message ?: "移动失败" }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: WorkspaceFileOps.Entry,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onArchive: () -> Unit,
    onHtml: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit
) {
    val colors = LocalHsuColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (entry.isDirectory) colors.yellow else colors.sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (entry.isDirectory) "文件夹" else formatBytes(entry.size), style = MaterialTheme.typography.bodySmall, color = colors.sub)
        }
        if (!entry.isDirectory) {
            if (entry.file.extension.equals("zip", true)) {
                IconButton(onClick = onArchive, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Archive, "解压 ZIP", tint = colors.sub, modifier = Modifier.size(20.dp)) }
            } else if (entry.file.extension.lowercase() in setOf("txt", "md", "markdown")) {
                IconButton(onClick = onHtml, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Code, "导出 HTML", tint = colors.sub, modifier = Modifier.size(20.dp)) }
            }
            IconButton(onClick = onExport, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.FileDownload, "导出文件", tint = colors.sub, modifier = Modifier.size(20.dp)) }
        }
        IconButton(onClick = onMove, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowUpward, "移动", tint = colors.sub, modifier = Modifier.size(20.dp)) }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = colors.sub, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun FileEditorScreen(path: String, text: String, loading: Boolean, onTextChange: (String) -> Unit, onBack: () -> Unit, onSave: () -> Unit) {
    val colors = LocalHsuColors.current
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = colors.ink) }
            Text(path, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onSave, enabled = !loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Save, "保存", tint = colors.green) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = !loading,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(if (loading) "正在读取…" else "文件内容") }
        )
    }
}

@Composable
private fun NameDialog(title: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(placeholder) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun WorkspaceSearchDialog(rootProvider: () -> Result<File>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WorkspaceFileOps.SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索工作区") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("文件内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (searching) Text("搜索中…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                results.forEach { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(hit.relativePath) }.padding(vertical = 8.dp)) {
                        Text("${hit.relativePath}:${hit.line}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        Text(hit.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                searching = true
                scope.launch {
                    val found = withContext(Dispatchers.IO) { rootProvider().fold({ WorkspaceFileOps.search(it, query) }, { Result.failure(it) }) }
                    results = found.getOrDefault(emptyList())
                    searching = false
                }
            }, enabled = query.isNotBlank() && !searching) { Text("搜索") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun WorkspaceGlobDialog(rootProvider: () -> Result<File>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf("**/*") }
    var results by remember { mutableStateOf<List<WorkspaceFileOps.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按文件名查找") },
        text = {
            Column {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("Glob，例如 **/*.kt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (busy) Text("查找中…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                results.take(40).forEach { entry ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(entry.relativePath) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(entry.relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pattern.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    results = withContext(Dispatchers.IO) { rootProvider().fold({ WorkspaceFileOps.glob(it, pattern).getOrDefault(emptyList()) }, { emptyList() }) }
                    busy = false
                }
            }) { Text("查找") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun InlineNotice(message: String, isError: Boolean, onDismiss: () -> Unit) {
    val colors = LocalHsuColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(if (isError) colors.red.copy(alpha = 0.1f) else colors.green.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.red else colors.green, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.DeleteOutline, "关闭提示", modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun NetworkToolsScreen(workspaceRoot: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    }
    var method by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf("https://") }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var uploadUri by remember { mutableStateOf<Uri?>(null) }
    var uploadName by remember { mutableStateOf("") }
    var downloadName by remember { mutableStateOf("") }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uploadUri = uri
        uploadName = uri?.let { displayName(context, it) }.orEmpty()
    }

    fun execute(upload: Boolean) {
        val endpoint = url.trim()
        if (!isHttpUrl(endpoint)) { response = "请输入有效的 http 或 https 地址"; return }
        busy = true
        scope.launch {
            response = withContext(Dispatchers.IO) {
                runCatching {
                    var temporaryUpload: File? = null
                    try {
                        val requestBody = when {
                            upload -> {
                                val uri = uploadUri ?: error("请先选择要上传的文件")
                                val input = context.contentResolver.openInputStream(uri) ?: error("无法读取上传文件")
                                val temp = File.createTempFile("hsucode-upload-", ".bin", context.cacheDir)
                                temporaryUpload = temp
                                input.use { it.copyTo(temp.outputStream()) }
                                MultipartBody.Builder().setType(MultipartBody.FORM)
                                    .addFormDataPart("file", uploadName.ifBlank { "upload.bin" }, temp.asRequestBody("application/octet-stream".toMediaType()))
                                    .build()
                            }
                            method == "POST" -> body.toRequestBody("application/json; charset=utf-8".toMediaType())
                            else -> null
                        }
                        val builder = Request.Builder().url(endpoint)
                        parseHeaders(headers).forEach { (name, value) -> builder.header(name, value) }
                        builder.method(if (upload) "POST" else method, requestBody)
                        client.newCall(builder.build()).execute().use { result ->
                            val text = result.body?.string().orEmpty()
                            buildString {
                                append("HTTP ${result.code} ${result.message}\n")
                                result.headers.forEach { header -> append("${header.first}: ${header.second}\n") }
                                append("\n")
                                append(text.take(180_000))
                                if (text.length > 180_000) append("\n\n[响应已截断]")
                            }
                        }
                    } finally {
                        temporaryUpload?.delete()
                    }
                }.getOrElse { "请求失败: ${it.message ?: "未知错误"}" }
            }
            busy = false
        }
    }

    fun download() {
        val endpoint = url.trim()
        if (!isHttpUrl(endpoint)) { response = "请输入有效的 http 或 https 地址"; return }
        val suggested = endpoint.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
        val targetName = downloadName.trim().ifBlank { suggested }
        busy = true
        scope.launch {
            response = withContext(Dispatchers.IO) {
                runCatching {
                    val root = WorkspaceFileOps.root(workspaceRoot).getOrThrow()
                    val target = WorkspaceFileOps.resolve(root, targetName).getOrThrow()
                    require(!target.exists()) { "同名文件已存在，请换一个下载文件名" }
                    target.parentFile?.mkdirs()
                    var copied = 0L
                    try {
                        val request = Request.Builder().url(endpoint).apply {
                            parseHeaders(headers).forEach { (name, value) -> header(name, value) }
                        }.build()
                        client.newCall(request).execute().use { result ->
                            require(result.isSuccessful) { "HTTP ${result.code}" }
                            val source = result.body?.byteStream() ?: error("下载响应为空")
                            source.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        copied += count
                                        require(copied <= 100L * 1024L * 1024L) { "下载超过 100 MB 上限" }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        "已下载 ${formatBytes(copied)} 到 $targetName"
                    } catch (error: Exception) {
                        target.delete()
                        throw error
                    }
                }.getOrElse { "下载失败: ${it.message ?: "未知错误"}" }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(LocalHsuColors.current.bg)) {
        WorkbenchTopBar("网络请求", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GET", "POST").forEach { choice ->
                        OutlinedButton(onClick = { method = choice }, modifier = Modifier.height(44.dp)) { Text(if (method == choice) "✓ $choice" else choice) }
                    }
                    IconButton(onClick = {
                        if (isHttpUrl(url.trim())) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))) }
                    }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "在浏览器打开") }
                }
            }
            item { OutlinedTextField(url, { url = it }, label = { Text("请求 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(headers, { headers = it }, label = { Text("请求头，每行 Name: Value") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
            if (method == "POST") item { OutlinedTextField(body, { body = it }, label = { Text("JSON 请求体") }, minLines = 5, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { execute(false) }, enabled = !busy, modifier = Modifier.weight(1f).height(50.dp)) { Text(if (busy) "请求中…" else "发送请求") }
                    OutlinedButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.height(50.dp)) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(7.dp)); Text("选择文件") }
                }
            }
            if (uploadUri != null) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(uploadName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { execute(true) }, enabled = !busy) { Text("上传") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(downloadName, { downloadName = it }, singleLine = true, label = { Text("下载文件名") }, placeholder = { Text("按 URL 自动命名") }, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::download, enabled = !busy, modifier = Modifier.height(56.dp)) {
                        Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("下载")
                    }
                }
            }
            item { Text("响应", style = MaterialTheme.typography.labelLarge, color = LocalHsuColors.current.sub, modifier = Modifier.padding(top = 8.dp)) }
            item {
                OutlinedTextField(
                    value = response,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("HTTP 响应会显示在这里") }
                )
            }
        }
    }
}

private fun createWebStarter(root: File, currentPath: String): Result<Unit> = runCatching {
    val folder = WorkspaceFileOps.resolve(root, joinPath(currentPath, "web-project")).getOrThrow()
    require(folder.mkdirs() || folder.isDirectory) { "无法创建 Web 项目目录" }
    File(folder, "index.html").writeText("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>HSUCODE Web</title><link rel=\"stylesheet\" href=\"styles.css\"><main><h1>HSUCODE Web</h1><p>从工作区开始编辑。</p><button id=\"action\">测试交互</button></main><script src=\"app.js\"></script>")
    File(folder, "styles.css").writeText("body{margin:0;background:#f7f9f7;color:#17201d;font:16px/1.6 system-ui,sans-serif}main{max-width:680px;margin:72px auto;padding:0 24px}button{padding:10px 14px;border:0;border-radius:8px;background:#176b4c;color:white;font:inherit}")
    File(folder, "app.js").writeText("document.querySelector('#action').addEventListener('click',()=>alert('HSUCODE Web 正在运行'))")
}

private fun displayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")

private fun parseHeaders(value: String): List<Pair<String, String>> = value.lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
}.filter { it.first.isNotBlank() }.toList()

private fun isHttpUrl(value: String): Boolean = runCatching {
    val uri = Uri.parse(value)
    (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun parentPath(path: String): String = path.substringBeforeLast('/', "")
private fun joinPath(parent: String, child: String): String = if (parent.isBlank()) child else "$parent/$child"
private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "${value / 1024 / 1024} MB"
    value >= 1024L -> "${value / 1024} KB"
    else -> "$value B"
}
