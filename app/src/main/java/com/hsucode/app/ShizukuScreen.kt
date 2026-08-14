package com.hsucode.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(
    enabled: Boolean,
    terminalEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTerminalEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by ShizukuManager.state.collectAsState()
    val sessions by ShizukuManager.sessions.collectAsState()
    val processes by ShizukuManager.processes.collectAsState()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ShizukuManager.refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Shizuku 增强调试") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { IconButton(onClick = { ShizukuManager.refresh() }) { Icon(Icons.Outlined.Refresh, "刷新状态") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            item {
                StatusCard(state)
            }
            item {
                WizardCard(
                    number = "1",
                    title = "安装 Shizuku",
                    detail = ShizukuManager.installedVersion(context)?.let { "已安装版本 $it" } ?: "尚未检测到 Shizuku 应用",
                    complete = state.installed,
                    action = if (state.installed) "打开应用" else "下载 Shizuku",
                    onAction = {
                        val opened = if (state.installed) ShizukuManager.openShizuku(context) else ShizukuManager.openShizukuDownload(context)
                        if (!opened) result = "无法打开目标应用或浏览器"
                    }
                )
            }
            item {
                WizardCard(
                    number = "2",
                    title = "启动服务",
                    detail = if (state.binderConnected) "Binder 已连接，后端 ${state.backendUid ?: "未知"}" else "在 Shizuku 中使用无线调试、USB 调试或 Root 后端启动服务",
                    complete = state.binderConnected,
                    action = "打开 Shizuku",
                    onAction = { if (!ShizukuManager.openShizuku(context)) result = "未找到 Shizuku 应用" }
                )
            }
            item {
                WizardCard(
                    number = "3",
                    title = "授予 HSUCODE 权限",
                    detail = if (state.permissionGranted) "已获得 Shizuku 授权" else state.lastError ?: "启动服务后请求授权",
                    complete = state.permissionGranted,
                    action = "请求授权",
                    enabled = state.binderConnected && !state.permissionGranted,
                    onAction = { result = ShizukuManager.requestPermission().fold({ it }, { "失败：${it.message}" }) }
                )
            }
            item {
                CapabilityCard(
                    enabled = enabled,
                    terminalEnabled = terminalEnabled,
                    state = state,
                    onEnabledChange = onEnabledChange,
                    onTerminalEnabledChange = onTerminalEnabledChange,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                runCatching { ShizukuManager.execute("id; printf 'server=%s\\n' \"${'$'}(getprop ro.build.version.release)\"") }
                            }.fold(
                                { "连接测试成功：\n${it.stdout.ifBlank { "无输出" }}" },
                                { "测试失败：${it.message}" }
                            )
                            busy = false
                        }
                    },
                    enabled = state.usable && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else { Icon(Icons.Outlined.Terminal, null); Text("运行 Shell UID 连接测试", Modifier.padding(start = 8.dp)) }
                }
            }
            if (result.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(result, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            item {
                Text("交互会话与后台进程", style = MaterialTheme.typography.titleSmall)
            }
            if (sessions.isEmpty() && processes.isEmpty()) {
                item { Text("当前没有运行中的 Shizuku 会话或后台进程", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            items(sessions, key = { "session-${it.id}" }) { session ->
                SessionRow(session)
            }
            items(processes, key = { "process-${it.id}" }) { process ->
                ProcessRow(process)
            }
            if (processes.any { !it.running }) item {
                TextButton(onClick = { ShizukuManager.clearFinishedProcesses() }, modifier = Modifier.fillMaxWidth()) { Text("清除已结束进程") }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shizuku 授予的是 ADB shell 或 Root 后端身份，不等同于绕过 Android 的所有系统限制。HSUCODE 会继续拦截致命分区操作，并将系统、文件、进程和屏幕操作交给权限审批与审计日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: ShizukuState) {
    val success = state.usable
    Card(colors = CardDefaults.cardColors(containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(state.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        state.backendUid?.let { "uid=$it" },
                        state.serverVersion?.let { "API $it" },
                        state.lastError,
                    ).joinToString(" · ").ifBlank { "等待连接" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WizardCard(number: String, title: String, detail: String, complete: Boolean, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (complete) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onAction, enabled = enabled) { Text(action) }
        }
    }
}

@Composable
private fun CapabilityCard(enabled: Boolean, terminalEnabled: Boolean, state: ShizukuState, onEnabledChange: (Boolean) -> Unit, onTerminalEnabledChange: (Boolean) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用增强工具", style = MaterialTheme.typography.titleSmall)
                    Text("开放 Shizuku Shell、文件、系统、UI 与后台进程工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("作为终端后端", style = MaterialTheme.typography.titleSmall)
                    Text("终端和环境命令改用 Shizuku 交互 Shell；关闭后继续使用 PRoot Ubuntu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = terminalEnabled, onCheckedChange = onTerminalEnabledChange, enabled = enabled && state.usable)
            }
        }
    }
}

@Composable
private fun SessionRow(session: ShizukuSessionSnapshot) {
    Card {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("交互会话 ${session.id}", style = MaterialTheme.typography.bodyMedium)
                Text(if (session.running) "运行中" else "已结束", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { ShizukuManager.stopInteractiveSession(session.id) }) { Icon(Icons.Outlined.Close, "关闭会话") }
        }
    }
}

@Composable
private fun ProcessRow(process: ShizukuProcessSnapshot) {
    Card {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(process.id, style = MaterialTheme.typography.bodyMedium)
                Text(process.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (process.running) "运行中" else "已结束 · exit ${process.exitCode ?: "-"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (process.running) IconButton(onClick = { ShizukuManager.stopBackgroundProcess(process.id) }) { Icon(Icons.Outlined.Close, "停止进程") }
        }
    }
}
