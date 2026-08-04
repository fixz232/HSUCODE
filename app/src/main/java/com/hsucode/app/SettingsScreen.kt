package com.hsucode.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.security.PermissionMode
import com.hsucode.tools.RootDiagnosticResult

private data class SettingsCategory(val id: String, val label: String)

private data class SettingsEntry(
    val category: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val toggleValue: Boolean? = null,
    val onToggle: ((Boolean) -> Unit)? = null
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSupplierConfig: () -> Unit,
    onNavigateToModelMarket: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToAuditLog: () -> Unit,
    onNavigateToMemoryStorage: () -> Unit = {},
    rootDetector: RootDetector? = null,
    permissionMode: PermissionMode = PermissionMode.ASK,
    onUpdatePermissionMode: (PermissionMode) -> Unit = {},
    onRootDiagnostic: (() -> Unit)? = null,
    rootDiagnosticResult: RootDiagnosticResult? = null,
    searchApiKey: String = "",
    onUpdateSearchApiKey: (String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    darkMode: Boolean = false,
    onUpdateDarkMode: (Boolean) -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToCuratedMemory: () -> Unit = {},
    onNavigateToCron: () -> Unit = {},
    onNavigateToContextCompress: () -> Unit = {},
    workspaceRoot: String = "",
    onUpdateWorkspaceRoot: (String) -> Unit = {},
    onNavigateToAuxModels: () -> Unit = {},
    onNavigateToFunctionModels: () -> Unit = {},
    onNavigateToLanDevices: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToCodeIndex: () -> Unit = {},
    onNavigateToUsageStats: () -> Unit = {},
    onNavigateToKanban: () -> Unit = {},
    onNavigateToGroupRooms: () -> Unit = {},
    onNavigateToProfiles: () -> Unit = {},
    onNavigateToSubAgents: () -> Unit = {},
    onNavigateToEnvConfig: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val colors = LocalHsuColors.current
    val app = LocalContext.current.applicationContext as HsucodeApplication
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var showSearchKeyDialog by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }

    val categories = remember {
        listOf(
            SettingsCategory("all", "全部"), SettingsCategory("appearance", "外观"),
            SettingsCategory("models", "模型"), SettingsCategory("security", "安全"),
            SettingsCategory("data", "数据"), SettingsCategory("tools", "工具"),
            SettingsCategory("about", "关于")
        )
    }
    val entries = listOf(
        SettingsEntry("appearance", "暗色模式", if (darkMode) "已开启" else "已关闭", Icons.Outlined.DarkMode,
            { onUpdateDarkMode(!darkMode) }, darkMode, onUpdateDarkMode),
        SettingsEntry("appearance", "回车发送", if (app.enterToSend) "回车发送消息" else "回车换行", Icons.Outlined.Keyboard,
            { app.updateEnterToSend(!app.enterToSend) }, app.enterToSend, app::updateEnterToSend),

        SettingsEntry("models", "模型市场", "添加预置供应商和模型", Icons.Outlined.Storefront, onNavigateToModelMarket),
        SettingsEntry("models", "供应商配置", "API 端点、密钥和默认模型", Icons.Outlined.Cloud, onNavigateToSupplierConfig),
        SettingsEntry("models", "功能模型", "总结、复盘、子智能体和裁判模型", Icons.Outlined.AccountTree, onNavigateToFunctionModels),
        SettingsEntry("models", "模型委托", "视觉、推理、翻译和转写模型", Icons.Outlined.Tune, onNavigateToAuxModels),

        SettingsEntry("security", "Root 状态", rootDetector?.status?.label ?: "检测中", Icons.Outlined.AdminPanelSettings,
            { rootDetector?.recheck() }),
        SettingsEntry("security", "Root 诊断", "检查命令和关键目录访问", Icons.Outlined.BugReport,
            { onRootDiagnostic?.invoke() }),
        SettingsEntry("security", "审计日志", "工具调用、决策和执行结果", Icons.Outlined.History, onNavigateToAuditLog),

        SettingsEntry("data", "全局工作区", workspaceRoot.ifBlank { "/storage/emulated/0/HSUCODE" }, Icons.Outlined.FolderOpen,
            { showWorkspaceDialog = true }),
        SettingsEntry("data", "记忆与存储", "本地记忆数据", Icons.Outlined.Storage, onNavigateToMemoryStorage),
        SettingsEntry("data", "上下文压缩", "长度、阈值和总结规则", Icons.Outlined.Compress, onNavigateToContextCompress),
        SettingsEntry("data", "精编记忆", "个人背景与近期状态", Icons.Outlined.Bookmarks, onNavigateToCuratedMemory),
        SettingsEntry("data", "定时任务", "后台自动任务", Icons.Outlined.Schedule, onNavigateToCron),
        SettingsEntry("data", "用量分析", "模型、Token、缓存与费用趋势", Icons.Outlined.Analytics, onNavigateToUsageStats),

        SettingsEntry("tools", "环境配置", "开发运行时和命令行工具", Icons.Outlined.Terminal, onNavigateToEnvConfig),
        SettingsEntry("tools", "配置环境", "工作与个人环境配置", Icons.Outlined.Workspaces, onNavigateToProfiles),
        SettingsEntry("tools", "子智能体", "专职智能体与工具范围", Icons.Outlined.SmartToy, onNavigateToSubAgents),
        SettingsEntry("tools", "局域网设备", "同一网络中的 HSUCODE 设备", Icons.Outlined.Devices, onNavigateToLanDevices),
        SettingsEntry("tools", "群聊房间", "多智能体协作房间", Icons.Outlined.Groups, onNavigateToGroupRooms),
        SettingsEntry("tools", "工作看板", "跨会话任务队列", Icons.Outlined.Dashboard, onNavigateToKanban),
        SettingsEntry("tools", "运行日志", "按级别和关键词筛选", Icons.Outlined.Article, onNavigateToLogs),
        SettingsEntry("tools", "代码索引", "本地符号和调用关系", Icons.Outlined.Code, onNavigateToCodeIndex),
        SettingsEntry("tools", "Skills", "可复用的工作指令", Icons.Outlined.Extension, onNavigateToSkills),
        SettingsEntry("tools", "MCP 服务器", "外部工具服务器", Icons.Outlined.Dns, onNavigateToMcp),
        SettingsEntry("tools", "Git 接入", "GitHub 授权与 MCP", Icons.Outlined.Source, onNavigateToGit),
        SettingsEntry("tools", "搜索密钥", if (searchApiKey.isBlank()) "未配置" else "已配置", Icons.Outlined.Key,
            { showSearchKeyDialog = true }),

        SettingsEntry("about", "关于 HSUCODE", "版本、更新、源代码与许可", Icons.Outlined.Info, onNavigateToAbout)
    )
    val normalizedQuery = query.trim()
    val filtered = entries.filter { entry ->
        (category == "all" || entry.category == category) &&
            (normalizedQuery.isEmpty() || entry.label.contains(normalizedQuery, true) ||
                entry.description.contains(normalizedQuery, true))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
                }
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, contentDescription = "打开导航", tint = colors.sub)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                    }
                },
                placeholder = { Text("搜索设置") },
                shape = RoundedCornerShape(8.dp)
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { item ->
                    FilterChip(
                        selected = category == item.id,
                        onClick = { category = item.id },
                        label = { Text(item.label) }
                    )
                }
            }
        }

        val showPermission = (category == "all" || category == "security") &&
            (normalizedQuery.isEmpty() || "工具权限".contains(normalizedQuery, true) || "询问只读完全访问".contains(normalizedQuery, true))
        if (showPermission) {
            item { SettingsSectionTitle("工具权限") }
            item {
                PermissionSelector(permissionMode, onUpdatePermissionMode)
            }
        }

        if (filtered.isEmpty() && !showPermission) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = colors.faint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("没有匹配的设置", color = colors.sub)
                }
            }
        } else {
            val grouped = filtered.groupBy { it.category }
            val sectionOrder = listOf("appearance", "models", "security", "data", "tools", "about")
            val sectionNames = mapOf(
                "appearance" to "外观与输入", "models" to "账户与模型", "security" to "权限与安全",
                "data" to "数据与自动化", "tools" to "开发工具", "about" to "关于"
            )
            sectionOrder.forEach { section ->
                val sectionItems = grouped[section].orEmpty()
                if (sectionItems.isNotEmpty()) {
                    item { SettingsSectionTitle(sectionNames.getValue(section)) }
                    items(sectionItems, key = { it.label }) { entry -> SettingsRow(entry) }
                    if (section == "security" && rootDiagnosticResult != null) {
                        item { RootDiagnosticPanel(rootDiagnosticResult) }
                    }
                }
            }
        }
    }

    if (showWorkspaceDialog) {
        DirectoryPickerDialog(
            initialPath = workspaceRoot,
            onConfirm = { onUpdateWorkspaceRoot(it); showWorkspaceDialog = false },
            onDismiss = { showWorkspaceDialog = false }
        )
    }
    if (showSearchKeyDialog) {
        SearchKeyDialog(
            initialValue = searchApiKey,
            onDismiss = { showSearchKeyDialog = false },
            onSave = { onUpdateSearchApiKey(it); showSearchKeyDialog = false }
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = LocalHsuColors.current.sub,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsRow(entry: SettingsEntry) {
    val colors = LocalHsuColors.current
    Surface(onClick = entry.onClick, color = colors.bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(entry.icon, contentDescription = null, tint = colors.sub, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            if (entry.toggleValue != null && entry.onToggle != null) {
                Switch(checked = entry.toggleValue, onCheckedChange = entry.onToggle)
            } else {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.faint)
            }
        }
    }
}

@Composable
private fun PermissionSelector(mode: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    val options = listOf(
        PermissionMode.ASK to "询问",
        PermissionMode.READ_ONLY to "只读",
        PermissionMode.ALLOW_ALL to "完全访问"
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label, maxLines = 1) }
                )
            }
        }
        Text(
            when (mode) {
                PermissionMode.ALLOW_ALL -> "工具可直接执行，致命操作仍会拦截"
                PermissionMode.READ_ONLY, PermissionMode.PLAN -> "仅允许读取和检索"
                PermissionMode.DENY_ALL -> "当前禁止所有工具调用"
                PermissionMode.ASK -> "写入和执行前请求确认"
            },
            style = MaterialTheme.typography.bodySmall,
            color = LocalHsuColors.current.sub,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun RootDiagnosticPanel(diag: RootDiagnosticResult) {
    val colors = LocalHsuColors.current
    val success = diag.errors.isEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .background(colors.bgElevated, RoundedCornerShape(8.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (success) colors.green else colors.red
            )
            Spacer(Modifier.width(8.dp))
            Text(if (success) "Root 诊断通过" else "Root 诊断发现问题", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text("id: ${diag.id.ifBlank { "无结果" }}", style = MaterialTheme.typography.bodySmall)
        Text("whoami: ${diag.whoami.ifBlank { "无结果" }}", style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(diag.errors.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp)) {
                diag.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.red) }
            }
        }
    }
}

@Composable
private fun SearchKeyDialog(initialValue: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索 API Key") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Tavily API Key") }
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
