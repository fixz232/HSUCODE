package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/** 单项安装状态。 */
private enum class InstallState { IDLE, INSTALLING, OK, FAIL }

/**
 * 「Linux 与工作区」页：所有用户都能部署 PRoot Ubuntu；Root 设备可额外保留 chroot 环境。
 */
@Composable
fun EnvConfigScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val xc = LocalHsuColors.current
    val context = LocalContext.current

    val categories = remember { EnvCatalog.categories }
    val savedProgress = EnvSetupProgressStore.state(context)
    // toolId -> 已安装 / 选中 / 安装状态;category.title -> 展开
    val installed = remember {
        mutableStateMapOf<String, Boolean>().apply {
            savedProgress.installedIds.forEach { put(it, true) }
        }
    }
    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            EnvCatalog.allTools.forEach { put(it.id, it.id in EnvCatalog.defaultToolIds) }
        }
    }
    val installState = remember { mutableStateMapOf<String, InstallState>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { categories.forEach { put(it.title, true) } } }
    var running by remember { mutableStateOf(savedProgress.running) }
    var completedCount by remember { mutableIntStateOf(savedProgress.completedCount) }
    var totalCount by remember { mutableIntStateOf(savedProgress.totalCount) }
    var progressMessage by remember { mutableStateOf(savedProgress.message) }
    var failureDetail by remember { mutableStateOf(savedProgress.detail) }
    // L1 修复:初始 false —— 未部署/未就绪时不显示"检测中…"(仅真正开始检测时才置 true)。
    var detecting by remember { mutableStateOf(false) }
    val prootState = ProotLinuxEnvironment.state
    val rootState = LinuxEnvironment.state
    val backend = WorkspaceRuntime.backend()
    val linuxReady = backend in setOf(WorkspaceRuntime.Backend.PROOT_UBUNTU, WorkspaceRuntime.Backend.ROOT_CHROOT)

    fun persistProgress() {
        EnvSetupProgressStore.save(
            context,
            EnvSetupProgress(
                running = running,
                completedCount = completedCount,
                totalCount = totalCount,
                message = progressMessage,
                detail = failureDetail,
                installedIds = installed.filterValues { it }.keys
            )
        )
    }

    // A background installation may complete while this screen is not visible.
    LaunchedEffect(savedProgress) {
        running = savedProgress.running
        completedCount = savedProgress.completedCount
        totalCount = savedProgress.totalCount
        progressMessage = savedProgress.message
        failureDetail = savedProgress.detail
        installed.clear()
        savedProgress.installedIds.forEach { installed[it] = true }
    }

    // 环境就绪后(或进入时已就绪)检测已安装状态。
    LaunchedEffect(backend, prootState, rootState) {
        if (linuxReady) {
            detecting = true
            try {
                // Do not probe while another installation is mutating the guest.
                if (!running && !EnvSetupManager.isInstalling()) {
                    val probe = withContext(Dispatchers.IO) { EnvSetupManager.inspectInstalled(EnvCatalog.allTools) }
                    withContext(Dispatchers.Main) {
                        if (probe.isNotEmpty()) {
                            installed.clear()
                            installed.putAll(probe)
                            persistProgress()
                        } else if (!running) {
                            progressMessage = "无法读取 Ubuntu 中的工具状态，未执行安装。"
                            failureDetail = EnvSetupManager.probeError()
                                ?: "状态检测失败，请确认 Ubuntu 已就绪后重试。"
                            persistProgress()
                        }
                    }
                }
            } finally {
                detecting = false
            }
        } else {
            installed.clear()
        }
    }

    fun catAllSelected(cat: EnvCategory) = cat.tools.all { selected[it.id] == true }
    fun toggleCat(cat: EnvCategory) {
        val newVal = !catAllSelected(cat)
        cat.tools.forEach { selected[it.id] = newVal }
    }

    fun runInstall(todo: List<EnvTool>) {
        if (EnvSetupManager.isInstalling()) {
            progressMessage = "另一个环境配置任务仍在运行，请稍后再试。"
            persistProgress()
            return
        }
        if (todo.isEmpty()) {
            progressMessage = "所选工具已经安装完成。"
            failureDetail = ""
            persistProgress()
            return
        }
        if (!EnvSetupManager.beginInstall()) {
            progressMessage = "环境配置任务未能启动，请稍后重试。"
            persistProgress()
            return
        }
        running = true
        completedCount = 0
        totalCount = todo.size
        progressMessage = "正在更新 Ubuntu 软件源…"
        failureDetail = ""
        persistProgress()
        val appScope = (context.applicationContext as HsucodeApplication).applicationScope
        appScope.launch(Dispatchers.IO) {
            try {
                val prepared = EnvSetupManager.preparePackageManager()
                if (!prepared.ok) {
                    withContext(Dispatchers.Main) {
                        failureDetail = prepared.detail
                        progressMessage = "软件源更新失败，未开始安装。请检查网络后重试。"
                        persistProgress()
                    }
                    return@launch
                }
                for (t in todo) {
                    withContext(Dispatchers.Main) {
                        installState[t.id] = InstallState.INSTALLING
                        progressMessage = "正在安装 ${t.name}（${completedCount + 1}/$totalCount）…"
                        persistProgress()
                    }
                    val (ok, detail) = EnvSetupManager.install(t)
                    // 安装后复检真实状态。
                    val nowInstalled = EnvSetupManager.isInstalled(t)
                    withContext(Dispatchers.Main) {
                        // A successful installer is authoritative even if the follow-up probe is transiently unavailable.
                        val completed = ok || nowInstalled == true
                        installed[t.id] = completed
                        installState[t.id] = if (completed) InstallState.OK else InstallState.FAIL
                        completedCount++
                        if (!completed) failureDetail = detail
                        persistProgress()
                    }
                }
                withContext(Dispatchers.Main) {
                    val failed = todo.count { installState[it.id] == InstallState.FAIL }
                    progressMessage = if (failed == 0) "所选工具已安装完成。" else "$failed 个工具未安装成功，可展开查看状态后重试。"
                    persistProgress()
                }
            } finally {
                EnvSetupManager.endInstall()
                withContext(Dispatchers.Main) {
                    running = false
                    persistProgress()
                }
            }
        }
    }

    fun deployProot(continueWith: List<EnvTool> = emptyList()) {
        if (running || ProotLinuxEnvironment.state == ProotLinuxEnvironment.State.SETTING_UP) return
        progressMessage = if (continueWith.isEmpty()) {
            "正在部署 Ubuntu，完成后会自动检查环境。"
        } else {
            "正在部署 Ubuntu，完成后会继续安装所选工具。"
        }
        failureDetail = ""
        persistProgress()
        val appCtx = context.applicationContext
        (appCtx as HsucodeApplication).applicationScope.launch(Dispatchers.IO) {
            val deployed = ProotLinuxEnvironment.bootstrap(force = ProotLinuxEnvironment.isReady())
            withContext(Dispatchers.Main) {
                if (!deployed) {
                    failureDetail = ProotLinuxEnvironment.setupLog.trim().takeLast(600)
                        .ifBlank { "Ubuntu 部署未完成，请查看工作区状态后重试。" }
                    progressMessage = "Ubuntu 部署失败，未开始安装。"
                    persistProgress()
                } else if (continueWith.isNotEmpty()) {
                    // Keep the user's first configuration request intact after the one-time Ubuntu bootstrap.
                    runInstall(continueWith)
                } else {
                    progressMessage = "Ubuntu 已就绪，可开始配置所选工具。"
                    persistProgress()
                }
            }
        }
    }

    fun deployRootEnv() {
        if (running || LinuxEnvironment.state == LinuxEnvironment.State.SETTING_UP) return
        val appCtx = context.applicationContext
        (appCtx as HsucodeApplication).applicationScope.launch(Dispatchers.IO) {
            LinuxEnvironment.bootstrap(appCtx, force = LinuxEnvironment.isReady())
        }
    }

    // 免 Root 工作区是默认路径：首次打开环境页时自动开始部署，用户无需先理解后端切换。
    // 下载/解压过程仍在应用级协程运行，离开页面不会中断；已有环境不会重复下载。
    LaunchedEffect(Unit) {
        if (ProotLinuxEnvironment.state == ProotLinuxEnvironment.State.NOT_SETUP &&
            ProotLinuxEnvironment.isSupported()
        ) {
            deployProot()
        }
    }

    fun startInstall() {
        if (running) return
        if (detecting) {
            progressMessage = "正在检测已安装状态，请稍候。"
            persistProgress()
            return
        }
        val todo = EnvCatalog.allTools.filter { selected[it.id] == true && installed[it.id] != true }
        if (!linuxReady) {
            deployProot(todo)
            return
        }
        runInstall(todo)
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        // 顶部返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            if (detecting) Text("检测中…", fontSize = 11.sp, fontFamily = Mono, color = xc.faint)
        }
        Text("Linux 与工作区", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = xc.ink,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        Text("默认使用 PRoot Ubuntu，在应用私有目录内运行 apt、Python、Node 与 Git，不需要 Root。",
            fontSize = 13.sp, color = xc.sub,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "__workspace_status__") {
                ProotStatusCard(xc, onDeploy = { deployProot() }, onOpenTerminal = onOpenTerminal)
            }
            if (running || progressMessage.isNotBlank() || failureDetail.isNotBlank()) {
                item(key = "__install_progress__") {
                    InstallProgressCard(
                        xc = xc,
                        running = running,
                        completedCount = completedCount,
                        totalCount = totalCount,
                        message = progressMessage,
                        detail = failureDetail,
                        onOpenTerminal = onOpenTerminal
                    )
                }
            }
            if (WorkspaceRuntime.isRootModeEnabled()) {
                item(key = "__root_env_status__") {
                    RootEnvStatusCard(xc, onDeploy = { deployRootEnv() }, onOpenTerminal = onOpenTerminal)
                }
            }
            items(categories, key = { it.title }) { cat ->
                CategoryCard(
                    cat = cat, xc = xc,
                    isExpanded = expanded[cat.title] == true,
                    allSelected = catAllSelected(cat),
                    installed = installed, selected = selected, installState = installState,
                    onToggleExpand = { expanded[cat.title] = expanded[cat.title] != true },
                    onToggleAll = { toggleCat(cat) },
                    onToggleTool = { id -> selected[id] = selected[id] != true }
                )
            }
        }

        // 底部按钮
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(xc.sub.copy(alpha = 0.25f))
                    .clickable(enabled = !running, indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() },
                contentAlignment = Alignment.Center
            ) { Text("跳过", fontSize = 15.sp, fontFamily = Mono, color = xc.ink) }
            val canStart = !running && !detecting && ProotLinuxEnvironment.state != ProotLinuxEnvironment.State.SETTING_UP
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (canStart) xc.green else xc.green.copy(alpha = 0.5f))
                    .clickable(enabled = canStart, indication = null, interactionSource = remember { MutableInteractionSource() }) { startInstall() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        running -> "配置中…"
                        detecting -> "检测中…"
                        !linuxReady -> "部署 Ubuntu"
                        else -> "开始配置"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color.White
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    cat: EnvCategory,
    xc: HsuColors,
    isExpanded: Boolean,
    allSelected: Boolean,
    installed: Map<String, Boolean>,
    selected: Map<String, Boolean>,
    installState: Map<String, InstallState>,
    onToggleExpand: () -> Unit,
    onToggleAll: () -> Unit,
    onToggleTool: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(xc.bgElevated).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = xc.ink, fontFamily = Mono)
                }
                if (cat.required) Text("(必须)", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E),
                    modifier = Modifier.padding(top = 2.dp))
                val selectedCount = cat.tools.count { selected[it.id] == true }
                Text("$selectedCount/${cat.tools.size} 已选择", fontSize = 11.sp, color = xc.green,
                    modifier = Modifier.padding(top = 2.dp))
                Text(cat.subtitle, fontSize = 12.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
            }
            CheckBox(checked = allSelected, color = xc.green, onClick = onToggleAll)
            Spacer(Modifier.width(8.dp))
            Text("全选", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
            Spacer(Modifier.width(8.dp))
            Text(if (isExpanded) "︿" else "﹀", fontSize = 14.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleExpand() })
        }

        if (isExpanded) {
            Spacer(Modifier.height(12.dp))
            cat.tools.forEach { tool ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    CheckBox(checked = selected[tool.id] == true, color = xc.green, onClick = { onToggleTool(tool.id) })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = xc.ink, fontFamily = Mono)
                            Spacer(Modifier.width(8.dp))
                            StatusTag(tool.id, installed, installState, xc)
                        }
                        Text(tool.desc, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(
    xc: HsuColors,
    running: Boolean,
    completedCount: Int,
    totalCount: Int,
    message: String,
    detail: String,
    onOpenTerminal: () -> Unit
) {
    val progress = if (totalCount > 0) (completedCount.toFloat() / totalCount).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xc.activeBg)
            .border(1.dp, xc.green.copy(alpha = .25f), RoundedCornerShape(8.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (running) "环境配置进行中" else "环境配置结果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = xc.ink)
                Text(message.ifBlank { "正在准备…" }, fontSize = 12.sp, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onOpenTerminal) { Text("查看日志") }
        }
        if (totalCount > 0) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = xc.green,
                trackColor = xc.border
            )
            Text("已处理 $completedCount/$totalCount", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 5.dp))
        }
        if (detail.isNotBlank()) {
            Text(detail.takeLast(500), fontSize = 10.sp, fontFamily = Mono, color = xc.red, lineHeight = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun StatusTag(id: String, installed: Map<String, Boolean>, installState: Map<String, InstallState>, xc: HsuColors) {
    val st = installState[id] ?: InstallState.IDLE
    when (st) {
        InstallState.INSTALLING -> Text("(安装中…)", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E))
        InstallState.OK -> Text("(已安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
        InstallState.FAIL -> Text("(失败)", fontSize = 11.sp, fontFamily = Mono, color = xc.red)
        InstallState.IDLE -> if (installed[id] == true)
            Text("(已安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
        else
            Text("(未安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.faint)
    }
}

/** 所有设备均可用的 PRoot Ubuntu 工作区。 */
@Composable
private fun ProotStatusCard(xc: HsuColors, onDeploy: () -> Unit, onOpenTerminal: () -> Unit) {
    val st = ProotLinuxEnvironment.state
    val label = when (st) {
        ProotLinuxEnvironment.State.READY -> "Ubuntu 已就绪"
        ProotLinuxEnvironment.State.SETTING_UP -> "正在下载并安装 Ubuntu…"
        ProotLinuxEnvironment.State.ERROR -> "Ubuntu 安装失败"
        ProotLinuxEnvironment.State.UNSUPPORTED -> "当前设备不支持 PRoot"
        ProotLinuxEnvironment.State.NOT_SETUP -> "尚未安装 Ubuntu"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xc.bgElevated)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(xc.activeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = xc.green, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("免 Root Ubuntu", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = xc.ink)
                Text("$label · PRoot 隔离用户空间", fontSize = 12.sp, color = xc.sub)
            }
            IconButton(onClick = onOpenTerminal, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Terminal, contentDescription = "打开工作区终端", tint = xc.green)
            }
        }
        Text(
            if (st == ProotLinuxEnvironment.State.UNSUPPORTED) ProotLinuxEnvironment.supportDetail()
            else ProotLinuxEnvironment.displayPath(), fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
            maxLines = 1, modifier = Modifier.padding(top = 8.dp)
        )
        if (st == ProotLinuxEnvironment.State.SETTING_UP || (st == ProotLinuxEnvironment.State.ERROR && ProotLinuxEnvironment.setupLog.isNotBlank())) {
            Text(ProotLinuxEnvironment.setupLog.trim().takeLast(360), fontSize = 10.sp, fontFamily = Mono,
                color = xc.faint, lineHeight = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (st != ProotLinuxEnvironment.State.UNSUPPORTED) {
            Text(
                if (st == ProotLinuxEnvironment.State.SETTING_UP) "取消安装" else if (st == ProotLinuxEnvironment.State.READY) "重新安装" else "安装 Ubuntu",
                fontSize = 12.sp, fontFamily = Mono, color = if (st == ProotLinuxEnvironment.State.SETTING_UP) xc.red else xc.green,
                modifier = Modifier.padding(top = 10.dp).clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (st == ProotLinuxEnvironment.State.SETTING_UP) ProotLinuxEnvironment.cancelBootstrap() else onDeploy()
                }
            )
        }
    }
}

/** Root 设备可选的完整 Ubuntu 环境。 */
@Composable
private fun RootEnvStatusCard(xc: HsuColors, onDeploy: () -> Unit, onOpenTerminal: () -> Unit) {
    val st = LinuxEnvironment.state
    val (label, color) = when (st) {
        LinuxEnvironment.State.READY -> "Ubuntu 已就绪" to xc.green
        LinuxEnvironment.State.SETTING_UP -> "正在部署 Ubuntu…" to Color(0xFFF2C14E)
        LinuxEnvironment.State.ERROR -> "Ubuntu 部署失败" to xc.red
        LinuxEnvironment.State.NOT_SETUP -> "尚未部署 Ubuntu" to xc.faint
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Root chroot 增强环境", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = xc.ink)
                Text("$label · 需要 Root", fontSize = 12.sp, color = xc.sub)
            }
            // 查看终端(可视化部署/安装/AI 操作)
            Text("查看终端 ›", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onOpenTerminal() }
                    .padding(end = 12.dp))
            if (st != LinuxEnvironment.State.SETTING_UP) {
                // 入口形状:一个 pill 按钮。
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(xc.green.copy(alpha = 0.15f))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDeploy() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(if (st == LinuxEnvironment.State.READY) "重新部署" else "部署",
                        fontSize = 12.sp, fontFamily = Mono, color = xc.green)
                }
            } else {
                Text("下载/部署中…", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E))
            }
        }
        // 部署进度日志(仅部署中/失败时显示)
        if (st == LinuxEnvironment.State.SETTING_UP || (st == LinuxEnvironment.State.ERROR && LinuxEnvironment.setupLog.isNotBlank())) {
            Spacer(Modifier.height(8.dp))
            Text(LinuxEnvironment.setupLog.trim().takeLast(400), fontSize = 10.sp, fontFamily = Mono,
                color = xc.faint, lineHeight = 14.sp)
        }
    }
}

/** 绿底白勾 / 空框 复选框(仿参考设计)。 */
@Composable
private fun CheckBox(checked: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .then(if (checked) Modifier.background(color) else Modifier.border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
