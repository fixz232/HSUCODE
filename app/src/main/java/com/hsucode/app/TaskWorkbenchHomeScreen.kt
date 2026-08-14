package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The task workbench keeps the active task, intervention points and produced
 * files in the first viewport. Lower-frequency runtime controls live below it.
 */
@Composable
fun TaskWorkbenchHomeScreen(
    runtime: TaskRuntimeManager,
    workspaceRoot: String,
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenWorkflow: () -> Unit,
    onOpenRoles: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit = {},
) {
    val colors = LocalHsuColors.current
    val tasks by runtime.tasks.collectAsState()
    val automation by AutomationTaskRunner.state.collectAsState()
    val shizuku by ShizukuManager.state.collectAsState()
    var artifacts by remember(workspaceRoot) { mutableStateOf(emptyList<WorkspaceArtifact>()) }
    LaunchedEffect(workspaceRoot) {
        artifacts = withContext(Dispatchers.IO) { recentArtifacts(workspaceRoot) }
    }

    val attention = tasks.filter { it.status == TaskRunStatus.WAITING_PERMISSION || it.status == TaskRunStatus.FAILED }
    val active = tasks.filter { it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.QUEUED || it.status == TaskRunStatus.PAUSED }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Menu, contentDescription = "打开导航", tint = colors.ink)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("任务", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                    Text("当前工作与交付", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.SettingsSuggest, "打开设置", tint = colors.sub)
                }
            }
        }
        item {
            Surface(
                color = colors.bgElevated,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PlayCircleOutline, null, tint = colors.green, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (active.isEmpty()) "开始一项工作" else active.first().title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (active.isEmpty()) "描述目标，HSUCODE 会保留执行过程与结果。" else "${active.size} 项任务进行中", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                        }
                        if (active.isNotEmpty()) {
                            Text("运行中", style = MaterialTheme.typography.labelMedium, color = colors.green)
                        }
                    }
                    if (active.isNotEmpty()) Text(active.first().checkpoint.ifBlank { "正在准备下一步" }, style = MaterialTheme.typography.bodyMedium, color = colors.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenChat, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (active.isEmpty()) "开始任务" else "继续对话")
                        }
                        OutlinedButton(onClick = onOpenTasks, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Outlined.TaskAlt, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("全部任务")
                        }
                    }
                }
            }
        }
        if (attention.isNotEmpty()) {
            item { WorkbenchSectionTitle("需要处理", "${attention.size}") }
            items(attention.take(3), key = { "attention_${it.id}" }) { task ->
                TaskSummaryRow(task = task, onClick = onOpenTasks)
            }
        }
        item {
            WorkbenchListRow(
                icon = Icons.Outlined.FolderOpen,
                title = "${WorkspaceRuntime.title()} 工作区",
                detail = workspaceRoot.ifBlank { "使用默认工作区" },
                trailing = if (WorkspaceRuntime.hasLinux()) "已就绪" else "Android",
                onClick = onOpenWorkbench
            )
        }
        item { WorkbenchSectionTitle("最近产物", if (artifacts.isEmpty()) "暂无" else "${artifacts.size} 个") }
        if (artifacts.isEmpty()) {
            item { EmptyWorkbenchRow(Icons.Outlined.Description, "聊天生成的文件会显示在这里", "在工作区、文档或对话中创建文件后可直接回到此处查看。") }
        } else {
            items(artifacts.take(3), key = { it.path }) { artifact ->
                WorkbenchListRow(
                    icon = Icons.Outlined.Description,
                    title = artifact.name,
                    detail = artifact.path,
                    trailing = artifact.relativeTime,
                    onClick = onOpenWorkbench
                )
            }
        }
        item { WorkbenchSectionTitle("工具", "按需打开") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactWorkbenchAction(Icons.Outlined.SmartToy, "自动化", Modifier.weight(1f), onOpenAutomation,
                    if (automation.running) "${automation.completed}/${automation.total}" else if (AccessibilityBridge.isEnabled()) "已授权" else "需授权")
                CompactWorkbenchAction(Icons.Outlined.Security, "增强通道", Modifier.weight(1f), onOpenSettings,
                    shizuku.label.ifBlank { "未连接" })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactWorkbenchAction(Icons.Outlined.AccountTree, "工作流", Modifier.weight(1f), onOpenWorkflow, "编排任务")
                CompactWorkbenchAction(Icons.Outlined.Groups, "角色能力", Modifier.weight(1f), onOpenRoles, "智能体配置")
            }
        }
        item { CompactWorkbenchAction(Icons.Outlined.Extension, "扩展市场", Modifier.fillMaxWidth(), onOpenExtensions, "Skills 与 MCP") }
    }
}

@Composable
private fun WorkbenchSectionTitle(title: String, detail: String) {
    val colors = LocalHsuColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.sub)
    }
}

@Composable
private fun TaskSummaryRow(task: TaskRunSnapshot, onClick: () -> Unit) {
    val colors = LocalHsuColors.current
    val tone = when (task.status) {
        TaskRunStatus.WAITING_PERMISSION -> colors.green
        TaskRunStatus.FAILED -> colors.red
        else -> colors.sub
    }
    Surface(onClick = onClick, color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (task.status == TaskRunStatus.FAILED) Icons.Outlined.Security else Icons.Outlined.TaskAlt, null, tint = tone, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (task.status == TaskRunStatus.FAILED) task.error.ifBlank { "任务失败" } else task.checkpoint.ifBlank { "等待权限审批" }, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, if (task.status == TaskRunStatus.FAILED) "查看失败任务" else "审批任务", tint = tone)
        }
    }
}

@Composable
private fun WorkbenchListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    trailing: String,
    onClick: () -> Unit
) {
    val colors = LocalHsuColors.current
    Surface(onClick = onClick, color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.green, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, "打开", tint = colors.faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompactWorkbenchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    detail: String
) {
    val colors = LocalHsuColors.current
    Surface(onClick = onClick, color = colors.bgElevated, shape = RoundedCornerShape(8.dp), modifier = modifier.heightIn(min = 72.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.green, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyWorkbenchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    val colors = LocalHsuColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = colors.sub, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column { Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink); Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.sub) }
    }
}

private data class WorkspaceArtifact(val name: String, val path: String, val relativeTime: String)

private fun recentArtifacts(rootPath: String): List<WorkspaceArtifact> = runCatching {
    if (rootPath.isBlank()) return emptyList()
    val root = File(rootPath)
    if (!root.isDirectory) return emptyList()
    val now = System.currentTimeMillis()
    root.walkTopDown().maxDepth(5).filter { it.isFile && it.length() <= 32L * 1024 * 1024 }
        .sortedByDescending(File::lastModified).take(4).map { file ->
            val minutes = ((now - file.lastModified()).coerceAtLeast(0) / 60_000).toInt()
            val label = when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes} 分钟前"
                minutes < 1_440 -> "${minutes / 60} 小时前"
                else -> "${minutes / 1_440} 天前"
            }
            WorkspaceArtifact(file.name, file.relativeTo(root).path.replace('\\', '/'), label)
        }.toList()
}.getOrDefault(emptyList())
