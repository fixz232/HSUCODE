/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: integrates the backup and restore screen into application navigation.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import com.hsucode.data.IdentityEntity
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.app.R
import com.hsucode.data.SessionEntity
import com.hsucode.tools.RootDiagnosticResult
import com.hsucode.tools.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
private val Ink: Color @Composable get() = LocalHsuColors.current.ink

class MainActivity : ComponentActivity() {

    private lateinit var voiceInputHelper: VoiceInputHelper
    private lateinit var ttsHelper: TtsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("HSUCODE", "MainActivity.onCreate START")
        val app = application as HsucodeApplication

        voiceInputHelper = VoiceInputHelper(this)
        ttsHelper = TtsHelper(this)
        ttsHelper.initialize()

        // Android 9 and below require the legacy runtime permission for the public
        // Downloads fallback. Android 10+ uses MediaStore and never prompts here.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                401
            )
        }

        setContent {
            HsuTheme(dark = app.darkMode) {
            var currentPage by remember { mutableStateOf("task_workbench") }
            // 终端页可从「对话页顶栏」或「环境配置页」进入;记录来源,退出时精确回到来处(修返回逻辑 bug)。
            var terminalOrigin by remember { mutableStateOf("chat") }
            var workspaceOrigin by remember { mutableStateOf("settings") }
            var rootDiagResult by remember { mutableStateOf<RootDiagnosticResult?>(null) }
            var editingIdentityId by remember { mutableStateOf<Long?>(null) }
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()

            // 数据库开不起来被自动重建过:必须告诉用户,不能让数据「无声消失」。
            // 只在本次进程内提示一次,消掉后不再打扰。
            var dbRecovery by remember {
                mutableStateOf(com.hsucode.data.AppDatabase.lastRecoveredFailure)
            }
            if (dbRecovery != null) {
                val backup = com.hsucode.data.AppDatabase.lastBackupName
                AlertDialog(
                    onDismissRequest = { dbRecovery = null },
                    title = { Text("数据库已重建") },
                    text = {
                        Text(
                            buildString {
                                append("上次的数据文件打不开,应用已新建了一个空数据库,否则会一直闪退。\n\n")
                                if (backup != null) {
                                    append("旧数据没有删除,已改名保留在应用私有目录:\n$backup\n\n")
                                    append("如果里面有重要内容,先别卸载应用 —— 卸载会连备份一起清掉。")
                                } else {
                                    append("旧数据文件已损坏且无法保留。")
                                }
                                // 「打不开」和「数据坏了」是两回事,用户看到的报错完全一样,
                                // 但只有前者有救。权限类失败一律是外部改动造成的 —— 最常见的是
                                // 让 AI 用 root 去动应用私有目录 —— 说清楚,用户下次才知道怎么避开。
                                if (dbRecovery?.contains("Permission denied") == true ||
                                    dbRecovery?.contains("not readable") == true
                                ) {
                                    append("\n\n这是权限被改动造成的,数据本身没坏。")
                                    append("通常是让 AI 用 root 动了应用私有目录 ")
                                    append("(/data/data/com.hsucode.app)。")
                                    append("本版本已禁止 AI 改这个目录,升级后不会再出现。")
                                }
                                append("\n\n原因:$dbRecovery")
                            }
                        )
                    },
                    confirmButton = { TextButton(onClick = { dbRecovery = null }) { Text("知道了") } }
                )
            }

            // 启动时静默检查更新:失败/限流/已最新一律安静跳过,只有真有新版才弹窗。
            var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            LaunchedEffect(Unit) {
                val result = UpdateChecker.check(
                    context = this@MainActivity,
                    settingGet = { k -> app.database.settingDao().get(k) },
                    settingPut = { k, v -> app.database.settingDao().put(k, v) }
                )
                updateInfo = (result as? UpdateChecker.CheckResult.Available)?.info
            }
            updateInfo?.let { info ->
                UpdateDialog(
                    info = info,
                    currentVersion = UpdateChecker.currentVersion(this@MainActivity),
                    onDismiss = { updateInfo = null },
                    onSkip = {
                        drawerScope.launch {
                            UpdateChecker.skipVersion(info.version) { k, v -> app.database.settingDao().put(k, v) }
                        }
                        updateInfo = null
                    }
                )
            }

            // Collect sessions from Room
            val sessions by app.sessionListFlow.collectAsState(initial = emptyList())
            val currentSessionId = app.currentSessionId
            val starredSessions by app.starredSessionsFlow.collectAsState(initial = emptyList())
            val ungroupedSessions by app.ungroupedSessionsFlow.collectAsState(initial = emptyList())
            val goalSessions by app.goalSessionsFlow.collectAsState(initial = emptyList())
            val projects by app.projectListFlow.collectAsState(initial = emptyList())
            val groupRooms by app.database.groupRoomDao().observeRooms().collectAsState(initial = emptyList())
            val subAgentSceneSnapshot by app.subAgentScene.snapshot.collectAsState()
            // 从侧栏直接点进某个房间时带上 id,群聊页据此跳过列表直接开那间
            var openRoomId by remember { mutableStateOf<Long?>(null) }
            val allIdentities by app.identityListFlow.collectAsState(initial = emptyList())
            // 群聊专用的角色卡不进主对话列表 —— 它们写的是团队里的一个位置,
            // 单独拿来跟你对话没有意义,混在一起只会把真正能用的卡淹掉。
            val identities = remember(allIdentities) {
                allIdentities.filter { it.scope != IdentityEntity.SCOPE_GROUP }
            }
            val activeIdentityId = app.activeIdentityId

            // Build project→sessions map from full sessions list
            val projectSessionsMap = remember(sessions) {
                sessions.filter { it.projectId != null }.groupBy { it.projectId!! }
            }

            // 侧滑/返回:抽屉开→关;子页→回上一页;聊天页→连续两次(2s 内)才退出应用。
            var lastBackMs by remember { mutableStateOf(0L) }
            BackHandler(enabled = drawerState.isOpen) { drawerScope.launch { drawerState.close() } }
            BackHandler(enabled = !drawerState.isOpen && currentPage != "chat") {
                currentPage = when (currentPage) {
                    "terminal" -> terminalOrigin
                    "env_config" -> workspaceOrigin
                    else -> parentPageOf(currentPage)
                }
            }
            BackHandler(enabled = !drawerState.isOpen && currentPage == "chat") {
                val now = System.currentTimeMillis()
                if (now - lastBackMs < 2000) this@MainActivity.finish()
                else { lastBackMs = now; Toast.makeText(this@MainActivity, "再滑一次返回退出", Toast.LENGTH_SHORT).show() }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerTonalElevation = 0.dp,
                        drawerContainerColor = LocalHsuColors.current.bg,
                        drawerShape = RoundedCornerShape(0.dp)
                    ) {
                        SidebarContent(
                        currentSessionId = currentSessionId,
                        starredSessions = starredSessions,
                        ungroupedSessions = ungroupedSessions,
                        projects = projects,
                        projectSessionsMap = projectSessionsMap,
                        onCreateNew = {
                            val newId = app.createNewSession()
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onSelectSession = { id ->
                            app.switchToSession(id)
                            currentPage = "chat"
                        },
                        onRenameSession = { id, title ->
                            app.renameSession(id, title)
                        },
                        onDeleteSession = { id ->
                            app.deleteSession(id)
                            if (id == currentSessionId) {
                                currentPage = "chat"
                            }
                        },
                        onNavigateToSettings = { currentPage = "settings" },
                        onNavigateToWork = { currentPage = "task_workbench" },
                        onClose = { drawerScope.launch { drawerState.close() } },
                        onCreateProject = { name -> app.createProject(name) },
                        onCreateNewInProject = { projectId ->
                            val newId = app.createSessionInProject(projectId)
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onRenameProject = { id, name -> app.renameProject(id, name) },
                        onDeleteProject = { id -> app.deleteProject(id) },
                        onSetProjectWorkspace = { id, path -> app.updateProjectWorkspace(id, path) },
                        onToggleProject = { id -> app.toggleProjectExpanded(id) },
                        onMoveSessionToProject = { sessionId, projectId -> app.moveSessionToProject(sessionId, projectId) },
                        onSetSessionStarred = { id, starred -> app.setSessionStarred(id, starred) },
                        identities = identities,
                        activeIdentityId = activeIdentityId,
                        onSetActiveIdentity = { id -> app.setActiveIdentity(id) },
                        onCreateIdentity = { editingIdentityId = null; currentPage = "identity_edit" },
                        onNavigateToIdentityList = { currentPage = "identity_list" },
                        onSearchMessages = { q -> app.searchMessages(q) },
                        goalSessions = goalSessions,
                        goalLiveStatus = { id -> app.goalRunStatus[id] ?: "" },
                        groupRooms = groupRooms,
                        onOpenGroupRooms = { currentPage = "group_rooms" },
                        onOpenGroupRoom = { rid ->
                            openRoomId = rid
                            currentPage = "group_rooms"
                        },
                        onCreateGoal = {
                            val newId = app.createGoalSession()
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onSelectGoal = { id ->
                            app.switchToSession(id)
                            currentPage = "chat"
                        }
                    )
                    }
                },
                gesturesEnabled = drawerState.isOpen
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                    },
                    label = "page_transition"
                ) { page ->
                    when (page) {
                    "chat" -> {
                        val active by produceState<com.hsucode.data.ProviderConfigEntity?>(
                            initialValue = null,
                            key1 = app.currentModelLabel
                        ) {
                            value = withContext(Dispatchers.IO) { app.database.providerConfigDao().getActive() }
                        }
                        val names = active?.enabledModelIds ?: emptyList()
                        val currentSession = sessions.firstOrNull { it.id == currentSessionId }
                        val currentIdentity = allIdentities.firstOrNull { it.id == app.activeIdentityId }
                        val skillNames = remember { mutableStateOf<List<String>>(emptyList()) }
                        val mcpNames = remember { mutableStateOf<List<String>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            skillNames.value = withContext(Dispatchers.IO) {
                                app.database.skillDao().getAll().map { it.name }
                            }
                            mcpNames.value = withContext(Dispatchers.IO) {
                                app.database.mcpServerDao().getAll().map { it.name }
                            }
                        }
                        val curIsGoal = goalSessions.any { it.id == app.currentSessionId }
                        val curGoal = goalSessions.firstOrNull { it.id == app.currentSessionId }
                        ChatScreen(
                            chatState = app.agentChatState,
                            conversationTitle = currentSession?.title.orEmpty().ifBlank { "新聊天" },
                            assistantName = currentIdentity?.name.orEmpty().ifBlank { "默认助手" },
                            currentModel = app.currentModelLabel,
                            supplierId = active?.supplierId.orEmpty(),
                            providerName = active?.name.orEmpty(),
                            workspaceRoot = app.agentChatState.sessionWorkspaceRoot,
                            availableModels = names,
                            isGoalSession = curIsGoal,
                            goalStatusCode = curGoal?.goalStatus ?: "",
                            goalLiveText = app.goalRunStatus[app.currentSessionId] ?: "",
                            goalRunning = app.isGoalRunning(app.currentSessionId),
                            onStartGoal = { text -> app.startGoalForSession(app.currentSessionId, text) },
                            onStopGoal = { app.stopGoal(app.currentSessionId) },
                            powerMode = app.currentPowerMode,
                            onSwitchModel = { modelId -> app.switchModel(modelId) },
                            thinkingEnabled = app.thinkingEnabled,
                            thinkingLevel = app.thinkingLevel,
                            onThinkingEnabledChange = { app.updateThinkingEnabled(it) },
                            onThinkingLevelChange = { app.updateThinkingLevel(it) },
                            onNavigateToSettings = { currentPage = "settings" },
                            onNavigateToWorkflow = { currentPage = "workflow" },
                            onNavigateToGoal = { currentPage = "goal" },
                            onNavigateToAgentScene = { currentPage = "agent_scene" },
                            onNavigateToStats = { currentPage = "stats" },
                            onNavigateToTerminal = { terminalOrigin = "chat"; currentPage = "terminal" },
                            onNavigateToWorkspace = { workspaceOrigin = "chat"; currentPage = "env_config" },
                            subAgentActive = subAgentSceneSnapshot.brainBusy,
                            ttsHelper = ttsHelper,
                            voiceInputHelper = voiceInputHelper,
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                            onNewChat = {
                                val newId = app.createNewSession()
                                app.switchToSession(newId)
                                currentPage = "chat"
                            },
                            planState = app.planState,
                            skillNames = skillNames.value,
                            onRegenerate = { msgId -> app.regenerateFromMessage(msgId) },
                            onDeleteMessage = { msgId -> app.deleteMessage(msgId) },
                            onCompactContext = { app.compactContext() },
                            onInsertSkill = { name -> app.insertSkillIntoInput(name) },
                            mcpNames = mcpNames.value,
                            onInsertMcp = { name -> app.insertMcpIntoInput(name) },
                            onNavigateToMcp = { currentPage = "mcp" },
                            onSetConversationWorkspace = { path -> app.setConversationWorkspace(path) },
                            onSetWebSearchEnabled = { enabled -> app.setWebSearchEnabled(enabled) },
                            permissionMode = app.permissionModeState,
                            onUpdatePermissionMode = { mode -> app.updatePermissionMode(mode) },
                            collabMode = app.collabModeEnabled,
                            onSetCollabMode = { on -> app.setCollabMode(on) }
                        )
                    }
                    "settings" -> SettingsScreen(
                        onBack = { currentPage = "chat" },
                        onNavigateToModelCenter = { currentPage = "model_center" },
                        onNavigateToSupplierConfig = { currentPage = "supplier" },
                        onNavigateToModelMarket = { currentPage = "model_market" },
                        onNavigateToGit = { currentPage = "git_config" },
                        onNavigateToAuditLog = { currentPage = "audit" },
                        onNavigateToMemoryStorage = { currentPage = "memory_storage" },
                        onNavigateToSkills = { currentPage = "skills" },
                        onNavigateToMcp = { currentPage = "mcp" },
                        onNavigateToCuratedMemory = { currentPage = "curated_memory" },
                        onNavigateToCron = { currentPage = "cron_jobs" },
                        onNavigateToContextCompress = { currentPage = "context_compress" },
                        workspaceRoot = app.workspaceRootGlobal,
                        onUpdateWorkspaceRoot = { path -> app.updateWorkspaceRoot(path) },
                        onNavigateToAuxModels = { currentPage = "aux_models" },
                        onNavigateToFunctionModels = { currentPage = "function_models" },
                        onNavigateToLanDevices = { currentPage = "lan_devices" },
                        onNavigateToLogs = { currentPage = "logs" },
                        onNavigateToCodeIndex = { currentPage = "code_index" },
                        onNavigateToUsageStats = { currentPage = "usage_stats" },
                        onNavigateToKanban = { currentPage = "kanban" },
                        onNavigateToGroupRooms = { currentPage = "group_rooms" },
                        onNavigateToProfiles = { currentPage = "profiles" },
                        onNavigateToSubAgents = { currentPage = "sub_agents" },
                        onNavigateToEnvConfig = { workspaceOrigin = "settings"; currentPage = "env_config" },
                        onNavigateToDeveloperWorkbench = { currentPage = "developer_workbench" },
                        onNavigateToBackup = { currentPage = "backup_restore" },
                        onNavigateToTaskRecovery = { currentPage = "task_recovery" },
                        onNavigateToTaskCenter = { currentPage = "task_center" },
                        onNavigateToPermissionRules = { currentPage = "permission_rules" },
                        onNavigateToRoleCards = { currentPage = "role_cards" },
                        onNavigateToMemoryRelations = { currentPage = "memory_relations" },
                        onNavigateToAccessibility = { currentPage = "accessibility" },
                        shizukuEnabled = app.shizukuEnabled,
                        shizukuStatus = ShizukuManager.state.collectAsState().value.label,
                        onUpdateShizuku = { app.updateShizukuEnabled(it) },
                        onNavigateToShizuku = { currentPage = "shizuku" },
                        onNavigateToHealth = { currentPage = "configuration_health" },
                        onNavigateToAbout = { currentPage = "about" },
                        darkMode = app.darkMode,
                        onUpdateDarkMode = { app.updateDarkMode(it) },
                        rootDetector = app.rootDetector,
                        rootModeEnabled = app.rootModeEnabled,
                        onUpdateRootMode = { app.updateRootModeEnabled(it) },
                        permissionMode = app.permissionModeState,
                        onUpdatePermissionMode = { mode -> app.updatePermissionMode(mode) },
                        onRootDiagnostic = {
                            app.applicationScope.launch {
                                try {
                                    val id = RootShellManager.execute("id")
                                    val whoami = RootShellManager.execute("whoami")
                                    val lsSd = RootShellManager.execute("ls /sdcard | head -10")
                                    val catBuild = RootShellManager.execute("cat /system/build.prop 2>/dev/null | head -3")
                                    val lsData = RootShellManager.execute("ls /data/data 2>/dev/null | head -10")
                                    val errors = mutableListOf<String>()
                                    if (id.exitCode != 0) errors.add("id failed: ${id.stderr.take(60)}")
                                    if (whoami.exitCode != 0) errors.add("whoami failed: ${whoami.stderr.take(60)}")
                                    rootDiagResult = com.hsucode.tools.RootDiagnosticResult(
                                        id = id.stdout.take(200),
                                        whoami = whoami.stdout.take(200),
                                        lsSdcard = lsSd.stdout.take(300),
                                        catSystemBuild = catBuild.stdout.take(200),
                                        lsDataData = lsData.stdout.take(200),
                                        errors = errors
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        rootDiagnosticResult = rootDiagResult,
                        searchApiKey = app.webSearchTool.apiKey,
                        onUpdateSearchApiKey = { key -> app.updateSearchApiKey(key) },
                        onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                    )
                    "model_market" -> ModelMarketScreen(
                        database = app.database,
                        keystore = app.keystore,
                        openAiClient = app.openAiClient,
                        onBack = { currentPage = "settings" }
                    )
                    "git_config" -> GitConfigScreen(
                        database = app.database,
                        keystore = app.keystore,
                        onBack = { currentPage = "settings" }
                    )
                    "supplier" -> SupplierConfigScreen(
                        database = app.database,
                        keystore = app.keystore,
                        openAiClient = app.openAiClient,
                        onBack = { currentPage = "settings" }
                    )
                    "audit" -> AuditLogScreen(
                        onBack = { currentPage = "settings" },
                        database = app.database
                    )
                    "workflow" -> WorkflowScreen(
                        agentCore = app.agentCore,
                        workflowState = app.workflowState,
                        onBack = { currentPage = "chat" },
                        onNavigateToReplay = { currentPage = "replay" }
                    )
                    "replay" -> {
                        var loaded by remember { mutableStateOf(false) }
                        var trajectory by remember { mutableStateOf<com.hsucode.data.TrajectoryEntity?>(null) }
                        LaunchedEffect(Unit) {
                            trajectory = withContext(Dispatchers.IO) { app.database.trajectoryDao().getAll().firstOrNull() }
                            loaded = true
                        }
                        when {
                            trajectory != null -> ReplayScreen(trajectory = trajectory!!, onBack = { currentPage = "workflow" })
                            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            else -> Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("暂无可回放记录")
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { currentPage = "workflow" }) { Text("返回工作流") }
                            }
                        }
                    }
                    "goal" -> GoalScreen(
                        goalRunner = app.goalRunner,
                        onBack = { currentPage = "chat" }
                    )
                    "memory_storage" -> MemoryStorageScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "skills" -> SkillScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "mcp" -> McpServerScreen(
                        mcpManager = app.mcpManager,
                        onBack = { currentPage = "settings" }
                    )
                    "curated_memory" -> CuratedMemoryScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "cron_jobs" -> CronJobsScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "aux_models" -> AuxModelsScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "task_workbench" -> TaskWorkbenchHomeScreen(
                        runtime = app.taskRuntime,
                        workspaceRoot = app.agentChatState.sessionWorkspaceRoot.ifBlank { app.workspaceRootGlobal },
                        onOpenChat = { currentPage = "chat" },
                        onOpenTasks = { currentPage = "task_center" },
                        onOpenWorkbench = { currentPage = "developer_workbench" },
                        onOpenAutomation = { currentPage = "accessibility" },
                        onOpenWorkflow = { currentPage = "workflow" },
                        onOpenRoles = { currentPage = "role_cards" },
                        onOpenExtensions = { currentPage = "extension_market" },
                        onOpenSettings = { currentPage = "settings" },
                        onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    )
                    "lan_devices" -> LanDiscoveryScreen(onBack = { currentPage = "settings" })
                    "logs" -> LogViewerScreen(onBack = { currentPage = "settings" })
                    "code_index" -> CodeIndexScreen(database = app.database, onBack = { currentPage = "settings" })
                    "usage_stats" -> UsageStatsScreen(database = app.database, onBack = { currentPage = "settings" })
                    "kanban" -> KanbanScreen(database = app.database, planState = app.planState, runner = app.kanbanRunner, onBack = { currentPage = "settings" })
                    "group_rooms" -> GroupRoomsScreen(database = app.database, keystore = app.keystore,
                        initialRoomId = openRoomId,
                        onConsumedInitialRoom = { openRoomId = null },
                        // 成员工作台现在是群聊【内嵌】的一层,不再跳出到主对话页
                        onBack = { openRoomId = null; currentPage = "chat" })
                    "profiles" -> ProfilesScreen(database = app.database, onBack = { currentPage = "settings" })
                    "function_models" -> FunctionModelsScreen(
                        database = app.database,
                        keystore = app.keystore,
                        onBack = { currentPage = "settings" }
                    )
                    "sub_agents" -> SubAgentsScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" },
                        onRunAgent = { agent, task ->
                            app.runSubAgent(agent, task)
                            currentPage = "agent_scene"
                        }
                    )
                    "context_compress" -> ContextCompressionScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "about" -> AboutScreen(app = app, onBack = { currentPage = "settings" })
                    "backup_restore" -> BackupRestoreScreen(app = app, onBack = { currentPage = "settings" })
                    "task_recovery" -> TaskRecoveryScreen(app = app, onBack = { currentPage = "settings" })
                    "task_center" -> TaskCenterScreen(runtime = app.taskRuntime, onBack = { currentPage = "task_workbench" })
                    "permission_rules" -> PermissionRulesScreen(database = app.database, onBack = { currentPage = "settings" }, onChanged = { app.reloadPermissionRules() })
                    "role_cards" -> RoleCardScreen(database = app.database, onBack = { currentPage = "task_workbench" })
                    "memory_relations" -> MemoryRelationsScreen(database = app.database, onBack = { currentPage = "settings" })
                    "accessibility" -> AccessibilityAutomationScreen(onBack = { currentPage = "task_workbench" })
                    "shizuku" -> ShizukuScreen(
                        enabled = app.shizukuEnabled,
                        terminalEnabled = app.shizukuTerminalEnabled,
                        onEnabledChange = { app.updateShizukuEnabled(it) },
                        onTerminalEnabledChange = { app.updateShizukuTerminalEnabled(it) },
                        onBack = { currentPage = "task_workbench" }
                    )
                    "configuration_health" -> ConfigurationHealthScreen(
                        app = app,
                        onBack = { currentPage = "task_workbench" },
                        onProvider = { currentPage = "supplier" },
                        onWorkspace = { currentPage = "settings" },
                        onEnvironment = { workspaceOrigin = "settings"; currentPage = "env_config" },
                        onMcp = { currentPage = "mcp" },
                        onUpdate = { currentPage = "about" },
                        onRecovery = { currentPage = "task_recovery" }
                    )
                    "model_center" -> ModelCenterScreen(
                        database = app.database,
                        keystore = app.keystore,
                        openAiClient = app.openAiClient,
                        onBack = { currentPage = "settings" }
                    )
                    "developer_workbench" -> DeveloperWorkbenchScreen(
                        workspaceRoot = app.agentChatState.sessionWorkspaceRoot.ifBlank { app.workspaceRootGlobal },
                        runtime = app.taskRuntime,
                        onBack = { currentPage = "task_workbench" },
                        onFiles = { currentPage = "workspace_files" },
                        onDocuments = { currentPage = "document_workbench" },
                        onNetwork = { currentPage = "network_tools" },
                        onExtensions = { currentPage = "extension_market" },
                        onPrompts = { currentPage = "prompt_library" },
                        onTerminal = { terminalOrigin = "developer_workbench"; currentPage = "terminal" },
                        onEnvironment = { workspaceOrigin = "developer_workbench"; currentPage = "env_config" },
                        onGit = { currentPage = "git_config" },
                        onReview = { currentPage = "workspace_review" },
                        onBrowser = { currentPage = "browser_agent" },
                        onAutomation = { currentPage = "accessibility" },
                        onWorkflow = { currentPage = "workflow" },
                        onRoles = { currentPage = "role_cards" },
                    )
                    "workspace_files" -> WorkspaceFilesScreen(
                        workspaceRoot = app.agentChatState.sessionWorkspaceRoot.ifBlank { app.workspaceRootGlobal },
                        onBack = { currentPage = "developer_workbench" }
                    )
                    "document_workbench" -> DocumentWorkbenchScreen(
                        workspaceRoot = app.agentChatState.sessionWorkspaceRoot.ifBlank { app.workspaceRootGlobal },
                        onBack = { currentPage = "developer_workbench" },
                        onOpenFiles = { currentPage = "workspace_files" },
                    )
                    "network_tools" -> NetworkToolsScreen(
                        workspaceRoot = app.agentChatState.sessionWorkspaceRoot.ifBlank { app.workspaceRootGlobal },
                        onBack = { currentPage = "developer_workbench" }
                    )
                    "workspace_review" -> WorkspaceReviewScreen(onBack = { currentPage = "developer_workbench" })
                    "browser_agent" -> BrowserAgentScreen(onBack = { currentPage = "developer_workbench" })
                    "extension_market" -> ExtensionMarketScreen(
                        database = app.database,
                        mcpManager = app.mcpManager,
                        onBack = { currentPage = "developer_workbench" },
                        onEnvironment = { workspaceOrigin = "extension_market"; currentPage = "env_config" },
                        onManageMcp = { currentPage = "mcp" },
                        onManageSkills = { currentPage = "skills" }
                    )
                    "prompt_library" -> PromptLibraryScreen(
                        database = app.database,
                        onBack = { currentPage = "developer_workbench" },
                        onSaved = { app.invalidateAllSystemPrompts() }
                    )
                    "env_config" -> EnvConfigScreen(
                        onBack = { currentPage = workspaceOrigin },
                        onOpenTerminal = { terminalOrigin = "env_config"; currentPage = "terminal" }
                    )
                    "agent_scene" -> AgentSceneScreen(
                        scene = app.subAgentScene,
                        database = app.database,
                        onBack = { currentPage = "chat" }
                    )
                    "stats" -> StatsScreen(
                        database = app.database,
                        chatState = app.agentChatState,
                        sessionId = app.currentSessionId,
                        onBack = { currentPage = "chat" }
                    )
                    "terminal" -> TerminalScreen(
                        terminal = app.terminalState,
                        onBack = { currentPage = terminalOrigin },
                        onDeployEnvironment = {
                            app.applicationScope.launch(Dispatchers.IO) {
                                ProotLinuxEnvironment.bootstrap(force = ProotLinuxEnvironment.isReady())
                            }
                        }
                    )
                    "identity_list" -> {
                        val sessionCounts = remember(sessions) {
                            sessions.filter { it.identityId != null }.groupingBy { it.identityId!! }.eachCount()
                        }
                        IdentityListScreen(
                            identities = identities,
                            activeId = activeIdentityId,
                            onBack = { currentPage = "chat" },
                            onCreateNew = { editingIdentityId = null; currentPage = "identity_edit" },
                            onEdit = { id -> editingIdentityId = id; currentPage = "identity_edit" },
                            onSetActive = { id -> app.setActiveIdentity(id) },
                            onToggleStar = { id, starred -> app.setIdentityStarred(id, starred) },
                            onRename = { id, name -> app.renameIdentity(id, name) },
                            onDelete = { id -> app.deleteIdentity(id) },
                            sessionCountForIdentity = { id -> sessionCounts[id] ?: 0 }
                        )
                    }
                    "identity_edit" -> {
                        val target = identities.firstOrNull { it.id == editingIdentityId }
                        IdentityEditScreen(
                            identity = target,
                            onBack = { currentPage = "identity_list" },
                            onSave = { r ->
                                if (target != null) {
                                    app.updateIdentity(target.copy(
                                        name = r.name, systemPrompt = r.systemPrompt, temperature = r.temperature,
                                        description = r.description, openingStatement = r.openingStatement,
                                        marks = r.marks, allowedTools = r.allowedTools
                                    ))
                                } else {
                                    app.createIdentity(r)
                                }
                                currentPage = "identity_list"
                            },
                            onDelete = if (target != null) {
                                { app.deleteIdentity(target.id); currentPage = "identity_list" }
                            } else null
                        )
                    }
                }
                }
            }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TermuxPtySessionManager.close()
        if (::ttsHelper.isInitialized) ttsHelper.shutdown()
        if (::voiceInputHelper.isInitialized) voiceInputHelper.reset()
    }
}
/** 各子页返回时的「上一页」映射:设置类子页回设置,其余回聊天。 */
private fun parentPageOf(page: String): String = when (page) {
    "settings" -> "chat"
    "task_workbench" -> "chat"
    "supplier", "model_market", "git_config", "audit", "memory_storage", "skills", "mcp", "curated_memory",
    "cron_jobs", "aux_models", "function_models", "sub_agents", "env_config", "context_compress", "about",
    "lan_devices", "logs", "usage_stats", "kanban", "group_rooms", "profiles", "code_index", "developer_workbench" -> "settings"
    "workspace_files", "document_workbench", "network_tools", "extension_market", "prompt_library" -> "developer_workbench"
    "replay" -> "workflow"
    "identity_edit" -> "identity_list"
    "shizuku" -> "settings"
    "identity_list" -> "settings"
    "workflow", "goal", "agent_scene" -> "chat"
    else -> "chat"
}
