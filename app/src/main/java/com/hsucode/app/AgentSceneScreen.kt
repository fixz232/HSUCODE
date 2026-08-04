package com.hsucode.app

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.data.CommandRoomEventEntity
import com.hsucode.data.CommandRoomRunEntity
import com.hsucode.security.ToolConfirmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

private val PixelSceneBackground = Color(0xFF141621)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSceneScreen(scene: SubAgentSceneState, database: AppDatabase, onBack: () -> Unit) {
    val colors = LocalHsuColors.current
    val state by scene.snapshot.collectAsState()
    var configuredAgents by remember { mutableStateOf<List<String>>(emptyList()) }
    var agentsLoaded by remember { mutableStateOf(false) }
    var agentsLoadFailed by remember { mutableStateOf(false) }
    var sceneCollapsed by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var selectedWorker by remember { mutableStateOf<SubAgentSceneState.Worker?>(null) }
    var selectedRun by remember { mutableStateOf<CommandRoomRunEntity?>(null) }

    val recentRuns by database.commandRoomHistoryDao().observeRecentRuns()
        .collectAsState(initial = emptyList())
    val selectedEventsFlow = remember(selectedRun?.runId) {
        selectedRun?.runId?.let(database.commandRoomHistoryDao()::observeEvents) ?: flowOf(emptyList())
    }
    val selectedEvents by selectedEventsFlow.collectAsState(initial = emptyList())

    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { database.subAgentDao().getAll().map { it.name } } }
        configuredAgents = result.getOrDefault(emptyList())
        agentsLoadFailed = result.isFailure
        agentsLoaded = true
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
        val sceneHeight = (maxHeight * 0.30f).coerceIn(176.dp, 264.dp)
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text("智能体指挥室", style = MaterialTheme.typography.titleMedium)
                        Text(
                            overallStatusText(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.brainBusy) colors.green else colors.sub
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.brainBusy) {
                        IconButton(onClick = scene::requestStopAll, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = "停止全部任务", tint = colors.red)
                        }
                    }
                    IconButton(onClick = { sceneCollapsed = !sceneCollapsed }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (sceneCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            contentDescription = if (sceneCollapsed) "展开像素场景" else "收起像素场景"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.ink,
                    navigationIconContentColor = colors.ink,
                    actionIconContentColor = colors.sub
                )
            )

            if (!sceneCollapsed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(sceneHeight)
                        .background(PixelSceneBackground)
                        .clearAndSetSemantics { }
                ) {
                    if (agentsLoaded && configuredAgents.isEmpty() && state.workers.isEmpty()) {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (agentsLoadFailed) "子智能体加载失败" else "暂无子智能体",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFF3EFE0)
                            )
                            Text(
                                if (agentsLoadFailed) "返回后重试" else "请先在设置中添加子智能体",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFADB4C9)
                            )
                        }
                    } else {
                        OfficeWebView(
                            configuredAgents = configuredAgents,
                            workers = state.workers,
                            brainBusy = state.brainBusy,
                            reducedMotion = reducedMotion,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            RunSummary(state)

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = colors.bg,
                contentColor = colors.green,
                divider = { HorizontalDivider(color = colors.divider) }
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("当前任务 (${state.workers.size})") }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("运行历史 (${recentRuns.size})") },
                    icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (tabIndex == 0) {
                CurrentRunContent(
                    state = state,
                    onSelectWorker = { selectedWorker = it },
                    onCancelWorker = scene::requestCancel,
                    onRetryWorker = scene::requestRetry,
                    onResolveApproval = scene::resolveApproval,
                    modifier = Modifier.weight(1f)
                )
            } else {
                HistoryContent(
                    runs = recentRuns,
                    onSelect = { selectedRun = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    selectedWorker?.let { worker ->
        WorkerDetailSheet(
            worker = state.workers.firstOrNull { it.workerRunId == worker.workerRunId } ?: worker,
            onDismiss = { selectedWorker = null },
            onCancel = {
                scene.requestCancel(worker.workerRunId)
                selectedWorker = null
            },
            onRetry = {
                scene.requestRetry(worker.workerRunId)
                selectedWorker = null
            }
        )
    }

    selectedRun?.let { run ->
        HistoryDetailSheet(run, selectedEvents, onDismiss = { selectedRun = null })
    }
}

@Composable
private fun RunSummary(state: SubAgentSceneState.Snapshot) {
    val colors = LocalHsuColors.current
    val now by tickingNow(state.brainBusy)
    val succeeded = state.workers.count { it.status == SubAgentSceneState.Status.SUCCEEDED }
    val failed = state.workers.count { it.status == SubAgentSceneState.Status.FAILED || it.status == SubAgentSceneState.Status.TIMED_OUT }
    Surface(color = colors.bgElevated, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryMetric("活动", state.workers.count { it.status.isActive }.toString(), colors.green)
            SummaryMetric("完成", succeeded.toString(), colors.ink)
            SummaryMetric("异常", failed.toString(), if (failed > 0) colors.red else colors.sub)
            SummaryMetric("待审批", state.approvals.size.toString(), if (state.approvals.isNotEmpty()) colors.yellow else colors.sub)
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration((if (state.endedAt > 0) state.endedAt else now) - state.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = colors.sub
            )
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalHsuColors.current.sub)
    }
}

@Composable
private fun CurrentRunContent(
    state: SubAgentSceneState.Snapshot,
    onSelectWorker: (SubAgentSceneState.Worker) -> Unit,
    onCancelWorker: (String) -> Unit,
    onRetryWorker: (String) -> Unit,
    onResolveApproval: (String, ToolConfirmResult) -> Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalHsuColors.current
    LazyColumn(modifier.fillMaxWidth()) {
        if (state.approvals.isNotEmpty()) {
            item {
                SectionLabel("权限审批", "${state.approvals.size} 项等待处理")
            }
            items(state.approvals, key = { it.requestId }) { request ->
                ApprovalPanel(request, onResolveApproval)
            }
        }
        item { SectionLabel("执行队列", if (state.workers.isEmpty()) "暂无运行" else "按最近更新排序") }
        if (state.workers.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无运行任务", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text("等待新的任务分派", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
            }
        } else {
            items(state.workers.sortedByDescending { it.updatedAt }, key = { it.workerRunId }) { worker ->
                WorkerRow(worker, onSelectWorker, onCancelWorker, onRetryWorker)
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun ApprovalPanel(
    request: SubAgentSceneState.ApprovalRequest,
    onResolve: (String, ToolConfirmResult) -> Boolean
) {
    val colors = LocalHsuColors.current
    Surface(
        color = if (request.isIrreversible) colors.red.copy(alpha = 0.08f) else colors.yellow.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (request.isIrreversible) Icons.Outlined.WarningAmber else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = if (request.isIrreversible) colors.red else colors.yellow,
                    modifier = Modifier.size(20.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(request.toolName, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text("${request.agent} · ${if (request.isIrreversible) "不可逆操作" else "需要授权"}", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
            }
            Text(request.preview, style = MaterialTheme.typography.bodyMedium, color = colors.ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { onResolve(request.requestId, ToolConfirmResult.DENY) }) { Text("拒绝", color = colors.red) }
                if (!request.isIrreversible) {
                    TextButton(onClick = { onResolve(request.requestId, ToolConfirmResult.ALWAYS_ALLOW) }) { Text("始终允许") }
                }
                Button(onClick = { onResolve(request.requestId, ToolConfirmResult.ALLOW_ONCE) }) { Text("仅本次允许") }
            }
        }
    }
}

@Composable
private fun WorkerRow(
    worker: SubAgentSceneState.Worker,
    onSelect: (SubAgentSceneState.Worker) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    val colors = LocalHsuColors.current
    val now by tickingNow(worker.status.isActive)
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(worker) }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusIcon(worker.status)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(worker.agent, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 1)
                Text("#${worker.attempt}", style = MaterialTheme.typography.labelMedium, color = colors.faint)
                Text(statusText(worker.status), style = MaterialTheme.typography.labelMedium, color = statusColor(worker.status, colors))
            }
            Text(worker.task, style = MaterialTheme.typography.bodyMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                worker.currentTool.ifBlank { worker.activity.ifBlank { worker.error.ifBlank { "等待状态更新" } } },
                style = MaterialTheme.typography.bodySmall,
                color = colors.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatDuration(workerElapsed(worker, now))} · ${formatRelativeTime(now - worker.updatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint
            )
        }
        if (worker.status.isActive) {
            IconButton(onClick = { onCancel(worker.workerRunId) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Cancel, contentDescription = "停止 ${worker.agent}", tint = colors.red)
            }
        } else if (worker.status != SubAgentSceneState.Status.SUCCEEDED) {
            IconButton(onClick = { onRetry(worker.workerRunId) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重试 ${worker.agent}", tint = colors.green)
            }
        }
    }
}

@Composable
private fun StatusIcon(status: SubAgentSceneState.Status) {
    val colors = LocalHsuColors.current
    val icon = when (status) {
        SubAgentSceneState.Status.SUCCEEDED -> Icons.Outlined.CheckCircle
        SubAgentSceneState.Status.FAILED, SubAgentSceneState.Status.UNKNOWN -> Icons.Outlined.ErrorOutline
        SubAgentSceneState.Status.CANCELLED -> Icons.Outlined.Cancel
        SubAgentSceneState.Status.TIMED_OUT -> Icons.Outlined.Schedule
        SubAgentSceneState.Status.WAITING_PERMISSION -> Icons.Outlined.Lock
        else -> Icons.Outlined.Schedule
    }
    Icon(icon, contentDescription = statusText(status), tint = statusColor(status, colors), modifier = Modifier.size(24.dp))
}

@Composable
private fun HistoryContent(
    runs: List<CommandRoomRunEntity>,
    onSelect: (CommandRoomRunEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalHsuColors.current
    LazyColumn(modifier.fillMaxWidth()) {
        if (runs.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无运行历史", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text("尚无可回看的记录", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
            }
        }
        items(runs, key = { it.runId }) { run ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(run) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = historyOutcomeColor(run.outcome, colors))
                Column(Modifier.weight(1f)) {
                    Text("${run.assignmentCount} 个子任务", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text(
                        "${historyOutcomeText(run.outcome)} · ${formatDuration((if (run.endedAt > 0) run.endedAt else System.currentTimeMillis()) - run.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sub
                    )
                }
                Text(formatClock(run.startedAt), style = MaterialTheme.typography.labelMedium, color = colors.faint)
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun SectionLabel(title: String, trailing: String) {
    val colors = LocalHsuColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.ink)
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = colors.sub)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerDetailSheet(
    worker: SubAgentSceneState.Worker,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val colors = LocalHsuColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgElevated) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusIcon(worker.status)
                Column {
                    Text(worker.agent, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(statusText(worker.status), style = MaterialTheme.typography.bodySmall, color = statusColor(worker.status, colors))
                }
            }
            DetailBlock("任务", worker.task)
            if (worker.currentTool.isNotBlank()) DetailBlock("当前工具", worker.currentTool)
            if (worker.activity.isNotBlank()) DetailBlock("最近活动", worker.activity)
            if (worker.result.isNotBlank()) DetailBlock("结果", worker.result)
            if (worker.error.isNotBlank()) DetailBlock("错误", worker.error, colors.red)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                if (worker.status.isActive) {
                    Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = colors.red)) { Text("停止任务") }
                } else if (worker.status != SubAgentSceneState.Status.SUCCEEDED) {
                    Button(onClick = onRetry) { Text("重新执行") }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, content: String, contentColor: Color = LocalHsuColors.current.ink) {
    val colors = LocalHsuColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.sub)
        Text(content, style = MaterialTheme.typography.bodyMedium, color = contentColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetailSheet(
    run: CommandRoomRunEntity,
    events: List<CommandRoomEventEntity>,
    onDismiss: () -> Unit
) {
    val colors = LocalHsuColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("运行记录", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(
                "${historyOutcomeText(run.outcome)} · ${run.assignmentCount} 个子任务 · ${formatClock(run.startedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.sub
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(events, key = { it.id }) { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(formatClock(event.createdAt), style = MaterialTheme.typography.labelMedium, color = colors.faint)
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(event.agent, eventTypeText(event.type)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.ink
                            )
                            Text(event.content, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
        }
    }
}

@Composable
private fun tickingNow(active: Boolean) = produceState(System.currentTimeMillis(), active) {
    while (active) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

private fun overallStatusText(state: SubAgentSceneState.Snapshot): String = when {
    state.workers.isEmpty() -> "待命"
    state.approvals.isNotEmpty() -> "等待 ${state.approvals.size} 项权限审批"
    state.brainBusy -> "${state.workers.count { it.status.isActive }} 个任务执行中"
    state.workers.any { it.status == SubAgentSceneState.Status.FAILED || it.status == SubAgentSceneState.Status.TIMED_OUT } -> "运行结束，存在异常"
    state.workers.all { it.status == SubAgentSceneState.Status.SUCCEEDED } -> "全部完成"
    else -> "运行已结束"
}

private fun statusText(status: SubAgentSceneState.Status): String = when (status) {
    SubAgentSceneState.Status.QUEUED -> "排队中"
    SubAgentSceneState.Status.PREPARING -> "准备中"
    SubAgentSceneState.Status.RUNNING -> "执行中"
    SubAgentSceneState.Status.WAITING_PERMISSION -> "等待审批"
    SubAgentSceneState.Status.SUCCEEDED -> "已完成"
    SubAgentSceneState.Status.FAILED -> "失败"
    SubAgentSceneState.Status.CANCELLED -> "已取消"
    SubAgentSceneState.Status.TIMED_OUT -> "已超时"
    SubAgentSceneState.Status.UNKNOWN -> "状态未知"
}

private fun statusColor(status: SubAgentSceneState.Status, colors: HsuColors): Color = when (status) {
    SubAgentSceneState.Status.RUNNING, SubAgentSceneState.Status.SUCCEEDED -> colors.green
    SubAgentSceneState.Status.WAITING_PERMISSION, SubAgentSceneState.Status.QUEUED, SubAgentSceneState.Status.PREPARING -> colors.yellow
    SubAgentSceneState.Status.FAILED, SubAgentSceneState.Status.TIMED_OUT, SubAgentSceneState.Status.UNKNOWN -> colors.red
    SubAgentSceneState.Status.CANCELLED -> colors.sub
}

private fun workerElapsed(worker: SubAgentSceneState.Worker, now: Long): Long {
    val start = worker.startedAt.takeIf { it > 0 } ?: worker.createdAt
    val end = worker.finishedAt.takeIf { it > 0 } ?: now
    return (end - start).coerceAtLeast(0)
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0秒"
    val seconds = durationMs / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
        minutes > 0 -> "%d:%02d".format(minutes, seconds % 60)
        else -> "${seconds}秒"
    }
}

private fun formatRelativeTime(deltaMs: Long): String = when {
    deltaMs < 5_000 -> "刚刚更新"
    deltaMs < 60_000 -> "${deltaMs / 1_000}秒前更新"
    else -> "${deltaMs / 60_000}分钟前更新"
}

private fun formatClock(timeMs: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timeMs))

private fun historyOutcomeText(outcome: String): String = when (outcome) {
    "running" -> "执行中"
    "completed" -> "已完成"
    "failed" -> "存在失败"
    "cancelled" -> "已取消"
    "interrupted" -> "意外中断"
    "timed_out" -> "已超时"
    else -> outcome.ifBlank { "未知结果" }
}

private fun historyOutcomeColor(outcome: String, colors: HsuColors): Color = when (outcome) {
    "completed" -> colors.green
    "failed", "timed_out" -> colors.red
    "running" -> colors.yellow
    else -> colors.sub
}

private fun eventTypeText(type: String): String = when (type) {
    "assigned" -> "已分派"
    "started" -> "开始执行"
    "tool_call" -> "调用工具"
    "approval_requested" -> "请求权限"
    "approval_resolved" -> "权限已处理"
    "completed" -> "完成"
    "failed" -> "失败"
    "cancel_requested" -> "请求停止"
    "cancelled" -> "已取消"
    "timed_out" -> "超时"
    "retry" -> "重新执行"
    else -> type
}
