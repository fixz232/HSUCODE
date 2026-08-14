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

private data class SettingsCategory(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector
)

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
    onNavigateToTaskCenter: () -> Unit = {},
    onNavigateToPermissionRules: () -> Unit = {},
    onNavigateToRoleCards: () -> Unit = {},
    onNavigateToMemoryRelations: () -> Unit = {},
    onNavigateToAccessibility: () -> Unit = {},
    shizukuEnabled: Boolean = false,
    shizukuStatus: String = "未连接",
    onUpdateShizuku: (Boolean) -> Unit = {},
    onNavigateToShizuku: () -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val colors = LocalHsuColors.current
    val app = LocalContext.current.applicationContext as HsucodeApplication
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("home") }
    var showSearchKeyDialog by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }

    val categories = remember {
        listOf(
            SettingsCategory("models", "模型中心", "供应商、模型、路由和健康", Icons.Outlined.Hub),
            SettingsCategory("agent", "智能体与任务", "任务续跑、记忆、角色与协作", Icons.Outlined.SmartToy),
            SettingsCategory("workspace", "工作区与开发", "文件、终端、环境与代码工具", Icons.Outlined.FolderOpen),
            SettingsCategory("security", "权限与安全", "工具权限、Root 和审计记录", Icons.Outlined.Security),
            SettingsCategory("data", "数据与恢复", "备份、存储和用量记录", Icons.Outlined.Storage),
            SettingsCategory("appearance", "外观与输入", "主题、输入行为和显示偏好", Icons.Outlined.Palette),
            SettingsCategory("integration", "扩展与集成", "Skills、MCP、Git 与网络服务", Icons.Outlined.Extension),
            SettingsCategory("about", "关于", "版本、更新、开源许可和致谢", Icons.Outlined.Info)
        )
    }
    val entries = listOf(
        SettingsEntry("appearance", "暗色模式", if (darkMode) "已开启" else "已关闭", Icons.Outlined.DarkMode,
            { onUpdateDarkMode(!darkMode) }, darkMode, onUpdateDarkMode),
        SettingsEntry("appearance", "回车发送", if (app.enterToSend) "回车发送消息" else "回车换行", Icons.Outlined.Keyboard,
            { app.updateEnterToSend(!app.enterToSend) }, app.enterToSend, app::updateEnterToSend),

        // A single model entry prevents users from having to distinguish five overlapping configuration pages.
        SettingsEntry("models", "模型中心", "供应商、模型、功能分配、用量与健康", Icons.Outlined.Hub, onNavigateToModelCenter),

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
        SettingsEntry("security", "细粒度权限", "按工具和目标路径设置允许或拒绝", Icons.Outlined.Rule, onNavigateToPermissionRules),
        SettingsEntry("security", "无障碍自动化", "按需启用系统界面自动化", Icons.Outlined.AccessibilityNew, onNavigateToAccessibility),
        SettingsEntry("security", "Shizuku 增强通道", if (shizukuEnabled) "已开启 · $shizukuStatus" else shizukuStatus, Icons.Outlined.Terminal,
            { onUpdateShizuku(!shizukuEnabled) }, shizukuEnabled, onUpdateShizuku),
        SettingsEntry("security", "Shizuku 连接管理", "启动服务、请求授权并运行连接测试", Icons.Outlined.Link, onNavigateToShizuku),

        SettingsEntry("workspace", "全局工作区", "${workspaceRoot.ifBlank { UserWorkspaceShell.displayPath() }} · ${WorkspaceManager.describe()}", Icons.Outlined.FolderOpen,
            { showWorkspaceDialog = true }),
        SettingsEntry("data", "记忆与存储", "本地记忆数据", Icons.Outlined.Storage, onNavigateToMemoryStorage),
        SettingsEntry("data", "备份与恢复", "全量加密备份与跨设备恢复", Icons.Outlined.Backup, onNavigateToBackup),
        SettingsEntry("data", "用量分析", "模型、Token、缓存与费用趋势", Icons.Outlined.Analytics, onNavigateToUsageStats),

        SettingsEntry("agent", "任务恢复", "查看正在续跑或可恢复的长任务", Icons.Outlined.Restore, onNavigateToTaskRecovery),
        SettingsEntry("agent", "统一任务中心", "查看运行、等待权限和已完成任务", Icons.Outlined.PendingActions, onNavigateToTaskCenter),
        SettingsEntry("agent", "上下文压缩", "长度、阈值和总结规则", Icons.Outlined.Compress, onNavigateToContextCompress),
        SettingsEntry("agent", "精编记忆", "个人背景与近期状态", Icons.Outlined.Bookmarks, onNavigateToCuratedMemory),
        SettingsEntry("agent", "记忆关系", "按标签整理可追溯的记忆关联", Icons.Outlined.AccountTree, onNavigateToMemoryRelations),
        SettingsEntry("agent", "角色卡生态", "导入和导出 Tavern 兼容 JSON 角色卡", Icons.Outlined.Badge, onNavigateToRoleCards),
        SettingsEntry("agent", "子智能体", "专职智能体与工具范围", Icons.Outlined.SmartToy, onNavigateToSubAgents),
        SettingsEntry("agent", "群聊房间", "多智能体协作房间", Icons.Outlined.Groups, onNavigateToGroupRooms),
        SettingsEntry("agent", "工作看板", "跨会话任务队列", Icons.Outlined.Dashboard, onNavigateToKanban),
        SettingsEntry("agent", "定时任务", "后台自动任务", Icons.Outlined.Schedule, onNavigateToCron),

        SettingsEntry("workspace", "开发工作台", "文件、网络、MCP、Skills 与提示词", Icons.Outlined.DeveloperMode, onNavigateToDeveloperWorkbench),
        SettingsEntry("workspace", "环境配置", "开发运行时和命令行工具", Icons.Outlined.Terminal, onNavigateToEnvConfig),
        SettingsEntry("workspace", "配置环境", "工作与个人环境配置", Icons.Outlined.Workspaces, onNavigateToProfiles),
        SettingsEntry("workspace", "运行日志", "按级别和关键词筛选", Icons.Outlined.Article, onNavigateToLogs),
        SettingsEntry("workspace", "代码索引", "本地符号和调用关系", Icons.Outlined.Code, onNavigateToCodeIndex),

        SettingsEntry("integration", "Skills", "可复用的工作指令", Icons.Outlined.Extension, onNavigateToSkills),
        SettingsEntry("integration", "MCP 服务器", "外部工具服务器", Icons.Outlined.Dns, onNavigateToMcp),
        SettingsEntry("integration", "Git 接入", "GitHub 授权与 MCP", Icons.Outlined.Source, onNavigateToGit),
        SettingsEntry("integration", "局域网设备", "同一网络中的 HSUCODE 设备", Icons.Outlined.Devices, onNavigateToLanDevices),
        SettingsEntry("integration", "搜索密钥", if (searchApiKey.isBlank()) "未配置" else "已配置", Icons.Outlined.Key,
            { showSearchKeyDialog = true }),

        SettingsEntry("about", "关于 HSUCODE", "版本、更新、源代码与许可", Icons.Outlined.Info, onNavigateToAbout)
    )
    val normalizedQuery = query.trim()
    val filtered = entries.filter { entry ->
        (selectedCategory == "home" || entry.category == selectedCategory) &&
            (normalizedQuery.isEmpty() || entry.label.contains(normalizedQuery, true) ||
                entry.description.contains(normalizedQuery, true))
    }

    // Keep the first viewport focused on the workflows users open most often.
    val quickLabels = listOf("模型中心", "统一任务中心", "全局工作区", "备份与恢复")
    val quickEntries = quickLabels.mapNotNull { label -> entries.firstOrNull { it.label == label } }
    val selectedDefinition = categories.firstOrNull { it.id == selectedCategory }
    val showHome = selectedCategory == "home" && normalizedQuery.isEmpty()
    val showSearchResults = normalizedQuery.isNotEmpty()

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth()
                .background(colors.bgElevated)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (selectedCategory == "home") onBack() else selectedCategory = "home" }) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = if (selectedCategory == "home") "返回" else "返回设置", tint = colors.ink)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(selectedDefinition?.label ?: "设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    selectedDefinition?.description ?: "配置模型、工作区、权限与开发工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, contentDescription = "打开导航", tint = colors.sub)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 52.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                    }
                },
                placeholder = { Text("搜索设置") },
                shape = RoundedCornerShape(8.dp),
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
        if (showSearchResults) {
            item {
                SettingsSectionTitle("搜索结果", "${filtered.size} 项匹配")
            }
            if (filtered.isEmpty()) {
                item { SettingsEmptyState("没有匹配的设置") }
            } else {
                item { SettingsList(filtered, permissionMode, onUpdatePermissionMode, selectedCategory == "security", rootDiagnosticResult) }
            }
        } else if (showHome) {
            item {
                SettingsSectionTitle("常用设置", "快速进入最常用的工作流")
            }
            item { SettingsList(quickEntries, permissionMode, onUpdatePermissionMode, false, null) }
            item { SettingsSectionTitle("全部分类", "按使用场景整理，进入后查看完整选项") }
            item {
                SettingsCategoryList(categories) { definition ->
                    selectedCategory = definition.id
                    query = ""
                }
            }
        } else {
            item { SettingsSectionTitle("全部设置", "${filtered.size} 项") }
            if (filtered.isEmpty()) item { SettingsEmptyState("没有匹配的设置") }
            else item { SettingsList(filtered, permissionMode, onUpdatePermissionMode, selectedCategory == "security", rootDiagnosticResult) }
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
private fun SettingsCategoryList(categories: List<SettingsCategory>, onClick: (SettingsCategory) -> Unit) {
    val colors = LocalHsuColors.current
    Surface(
        color = colors.bgElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column {
            categories.forEachIndexed { index, category ->
                Surface(onClick = { onClick(category) }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp).background(colors.activeBg, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(category.icon, contentDescription = null, tint = colors.green, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(category.description, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "打开${category.label}", tint = colors.faint, modifier = Modifier.size(20.dp))
                    }
                }
                if (index < categories.lastIndex) HorizontalDivider(color = colors.border)
            }
        }
    }
}

@Composable
private fun SettingsList(
    sectionItems: List<SettingsEntry>,
    permissionMode: PermissionMode,
    onUpdatePermissionMode: (PermissionMode) -> Unit,
    showPermission: Boolean,
    rootDiagnosticResult: RootDiagnosticResult?
) {
    val colors = LocalHsuColors.current
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(colors.bgElevated, RoundedCornerShape(8.dp))
    ) {
        if (showPermission) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("工具权限", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("控制工具执行前的确认范围", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                Spacer(Modifier.height(10.dp))
                PermissionSelector(permissionMode, onUpdatePermissionMode)
            }
            if (sectionItems.isNotEmpty()) HorizontalDivider(color = colors.border)
        }
        sectionItems.forEachIndexed { index, entry ->
            SettingsRow(
                entry = entry,
                showDivider = index < sectionItems.lastIndex || rootDiagnosticResult != null
            )
        }
        if (rootDiagnosticResult != null) {
            if (sectionItems.isNotEmpty()) HorizontalDivider(color = colors.border)
            RootDiagnosticPanel(rootDiagnosticResult)
        }
    }
}

@Composable
private fun SettingsEmptyState(message: String) {
    val colors = LocalHsuColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = colors.faint, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.sub)
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
        PermissionMode.AUTO_APPROVE_RISK to "自动批准",
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
                PermissionMode.AUTO_APPROVE_RISK -> "普通操作自动执行，仅风险操作请求确认"
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
