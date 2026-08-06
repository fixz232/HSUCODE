/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: adds the backup and restore settings entry.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    onNavigateToModelCenter: () -> Unit = {},
    onNavigateToSupplierConfig: () -> Unit,
    onNavigateToModelMarket: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToAuditLog: () -> Unit,
    onNavigateToMemoryStorage: () -> Unit = {},
    rootDetector: RootDetector? = null,
    rootModeEnabled: Boolean = false,
    onUpdateRootMode: (Boolean) -> Unit = {},
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
    onNavigateToDeveloperWorkbench: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToTaskRecovery: () -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
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

        SettingsEntry("models", "模型中心", "供应商、模型、功能分配、健康与智能路由", Icons.Outlined.Hub, onNavigateToModelCenter),

        SettingsEntry("security", "Root 状态", rootDetector?.status?.label ?: "检测中", Icons.Outlined.AdminPanelSettings,
            { rootDetector?.recheck() }),
        SettingsEntry(
            "security",
            "Root 模式",
            if (rootModeEnabled) "已开启：允许使用 Root chroot 环境" else "已关闭：默认使用免 Root PRoot 环境",
            Icons.Outlined.Security,
            { onUpdateRootMode(!rootModeEnabled) },
            rootModeEnabled,
            onUpdateRootMode
        ),
        SettingsEntry("security", "配置健康中心", "集中检查关键配置与后台能力", Icons.Outlined.HealthAndSafety,
            onNavigateToHealth),
        SettingsEntry("security", "Root 诊断", "检查命令和关键目录访问", Icons.Outlined.BugReport,
            { onRootDiagnostic?.invoke() }),
        SettingsEntry("security", "审计日志", "工具调用、决策和执行结果", Icons.Outlined.History, onNavigateToAuditLog),

        SettingsEntry("data", "全局工作区", "${workspaceRoot.ifBlank { UserWorkspaceShell.displayPath() }} · ${WorkspaceManager.describe()}", Icons.Outlined.FolderOpen,
            { showWorkspaceDialog = true }),
        SettingsEntry("data", "记忆与存储", "本地记忆数据", Icons.Outlined.Storage, onNavigateToMemoryStorage),
        SettingsEntry("data", "备份与恢复", "全量加密备份与跨设备恢复", Icons.Outlined.Backup, onNavigateToBackup),
        SettingsEntry("data", "任务恢复", "查看正在续跑或可恢复的长任务", Icons.Outlined.Restore, onNavigateToTaskRecovery),
        SettingsEntry("data", "上下文压缩", "长度、阈值和总结规则", Icons.Outlined.Compress, onNavigateToContextCompress),
        SettingsEntry("data", "精编记忆", "个人背景与近期状态", Icons.Outlined.Bookmarks, onNavigateToCuratedMemory),
        SettingsEntry("data", "定时任务", "后台自动任务", Icons.Outlined.Schedule, onNavigateToCron),
        SettingsEntry("data", "用量分析", "模型、Token、缓存与费用趋势", Icons.Outlined.Analytics, onNavigateToUsageStats),

        SettingsEntry("tools", "开发工作台", "文件、网络、MCP、Skills 与提示词", Icons.Outlined.DeveloperMode, onNavigateToDeveloperWorkbench),
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

    // Keep the first viewport focused on the workflows users open most often.
    val quickLabels = remember {
        setOf("模型中心", "开发工作台", "全局工作区", "配置健康中心", "备份与恢复")
    }
    val quickEntries = remember(entries) {
        quickLabels.mapNotNull { label -> entries.firstOrNull { it.label == label } }
    }
    var expandedCategories by rememberSaveable {
        mutableStateOf(listOf("models", "security"))
    }
    val expandedSet = expandedCategories.toSet()
    fun toggleCategory(id: String) {
        expandedCategories = if (id in expandedSet) {
            expandedCategories.filterNot { it == id }
        } else {
            expandedCategories + id
        }
    }
    val matchesPermission = normalizedQuery.isEmpty() ||
        "工具权限".contains(normalizedQuery, true) ||
        "询问只读完全访问".contains(normalizedQuery, true)
    val showQuick = category == "all" && normalizedQuery.isEmpty()

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth()
                .background(colors.bgElevated)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("配置模型、工作区与权限", style = MaterialTheme.typography.bodySmall, color = colors.sub)
            }
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, contentDescription = "打开导航", tint = colors.sub)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 54.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                    }
                },
                placeholder = { Text("搜索设置") },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.bgElevated,
                    unfocusedContainerColor = colors.bgElevated,
                    disabledContainerColor = colors.bgElevated,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedLeadingIconColor = colors.green,
                    unfocusedLeadingIconColor = colors.sub,
                    cursorColor = colors.green
                )
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
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

        if (showQuick) {
            item {
                SettingsSectionTitle("常用设置", "快速进入正在使用的核心功能")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(quickEntries, key = { it.label }) { entry ->
                        SettingsQuickTile(entry)
                    }
                }
            }
        }

        val grouped = filtered.groupBy { it.category }
        val sectionOrder = listOf("appearance", "models", "security", "data", "tools", "about")
        val sectionNames = mapOf(
            "appearance" to ("外观与输入" to "主题、输入行为和显示偏好"),
            "models" to ("模型中心" to "供应商、模型、路由和健康"),
            "security" to ("权限与安全" to "工具权限、Root 和审计记录"),
            "data" to ("数据与自动化" to "工作区、记忆、备份和任务"),
            "tools" to ("开发工具" to "终端、文件、联网和扩展能力"),
            "about" to ("关于" to "版本、更新、开源许可和致谢")
        )
        val visibleSections = sectionOrder.filter { section ->
            val sectionItems = grouped[section].orEmpty().filterNot {
                showQuick && it.label in quickLabels
            }
            sectionItems.isNotEmpty() || (section == "security" && category != "all" && matchesPermission)
        }

        if (visibleSections.isEmpty()) {
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
            visibleSections.forEach { section ->
                val sectionItems = grouped[section].orEmpty().filterNot {
                    showQuick && it.label in quickLabels
                }
                val expanded = normalizedQuery.isNotEmpty() || category != "all" || section in expandedSet
                item(key = "section_$section") {
                    SettingsSectionHeader(
                        title = sectionNames.getValue(section).first,
                        description = sectionNames.getValue(section).second,
                        count = sectionItems.size + if (section == "security" && matchesPermission) 1 else 0,
                        expanded = expanded,
                        onClick = { toggleCategory(section) }
                    )
                }
                if (expanded) {
                    item(key = "section_body_$section") {
                        SettingsSectionBody(
                            sectionItems = sectionItems,
                            showPermission = section == "security" && matchesPermission,
                            permissionMode = permissionMode,
                            onUpdatePermissionMode = onUpdatePermissionMode,
                            rootDiagnosticResult = if (section == "security") rootDiagnosticResult else null
                        )
                    }
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
private fun SettingsSectionTitle(title: String, description: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = LocalHsuColors.current.sub)
        if (!description.isNullOrBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = LocalHsuColors.current.faint)
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    description: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalHsuColors.current
    Surface(
        onClick = onClick,
        color = colors.bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = colors.faint)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = colors.sub,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SettingsQuickTile(entry: SettingsEntry) {
    val colors = LocalHsuColors.current
    Surface(
        onClick = entry.onClick,
        color = colors.bgElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(156.dp).heightIn(min = 72.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(entry.icon, contentDescription = null, tint = colors.green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.description, style = MaterialTheme.typography.labelSmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsSectionBody(
    sectionItems: List<SettingsEntry>,
    showPermission: Boolean,
    permissionMode: PermissionMode,
    onUpdatePermissionMode: (PermissionMode) -> Unit,
    rootDiagnosticResult: RootDiagnosticResult?
) {
    val colors = LocalHsuColors.current
    Surface(
        color = colors.bgElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (showPermission) {
                PermissionSelector(permissionMode, onUpdatePermissionMode)
                if (sectionItems.isNotEmpty()) {
                    HorizontalDivider(color = colors.border)
                }
            }
            sectionItems.forEachIndexed { index, entry ->
                SettingsRow(entry, showDivider = index < sectionItems.lastIndex || rootDiagnosticResult != null)
            }
            if (rootDiagnosticResult != null) {
                if (sectionItems.isNotEmpty()) HorizontalDivider(color = colors.border)
                RootDiagnosticPanel(rootDiagnosticResult)
            }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, showDivider: Boolean = false) {
    val colors = LocalHsuColors.current
    Column(Modifier.fillMaxWidth()) {
        Surface(onClick = entry.onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(entry.icon, contentDescription = null, tint = colors.sub, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
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
        if (showDivider) {
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(start = 50.dp))
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
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
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
