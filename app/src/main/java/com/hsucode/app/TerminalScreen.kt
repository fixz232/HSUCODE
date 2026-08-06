package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
private val TBg = Color(0xFF0F1117)
private val TInk = Color(0xFFD7DAE0)
private val TGreen = Color(0xFF7BE0A4)
private val TSub = Color(0xFF6B7089)

/** Terminal page. PRoot Ubuntu uses the real Termux PTY; other backends keep the fallback shell. */
@Composable
fun TerminalScreen(
    terminal: TerminalState,
    onBack: () -> Unit,
    onDeployEnvironment: () -> Unit = {},
) {
    var ptyRestartToken by remember { mutableStateOf(0) }
    var showCwdDialog by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(UserWorkspaceShell.relativeCwd()) }
    var cwdInput by remember { mutableStateOf(cwd) }
    var ptySessionId by remember { mutableStateOf(TermuxPtySessionManager.activeId()) }
    var ptyTabsVersion by remember { mutableStateOf(0) }
    val backend = WorkspaceRuntime.backend()
    val isPtyBackend = backend == WorkspaceRuntime.Backend.PROOT_UBUNTU
    val prootState = ProotLinuxEnvironment.state

    LaunchedEffect(Unit) {
        if (prootState == ProotLinuxEnvironment.State.NOT_SETUP && ProotLinuxEnvironment.isSupported()) {
            onDeployEnvironment()
        }
    }
    LaunchedEffect(isPtyBackend) {
        if (isPtyBackend) terminal.close()
    }
    LaunchedEffect(isPtyBackend) {
        if (isPtyBackend) {
            while (true) {
                delay(1_000)
                ptyTabsVersion++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(TBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TInk)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    WorkspaceRuntime.title(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono,
                    color = TInk,
                )
                Text(
                    if (isPtyBackend) "PTY · xterm-256color · cwd=${cwd.ifBlank { "/" }}" else "${WorkspaceRuntime.detail()} · cwd=${cwd.ifBlank { "/" }}",
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    color = TSub,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { cwdInput = cwd; showCwdDialog = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "选择工作目录", tint = TSub)
            }
            if (!isPtyBackend) {
                IconButton(onClick = { terminal.clear() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "清屏", tint = TSub)
                }
            }
            IconButton(
                onClick = {
                    if (isPtyBackend) ptyRestartToken++ else terminal.stop()
                },
                enabled = isPtyBackend || terminal.running,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (isPtyBackend) Icons.Outlined.Refresh else Icons.Outlined.StopCircle,
                    contentDescription = if (isPtyBackend) "重启终端" else "停止命令",
                    tint = if (isPtyBackend || terminal.running) Color(0xFFE0685C) else TSub,
                )
            }
        }
        if (showCwdDialog) {
            AlertDialog(
                onDismissRequest = { showCwdDialog = false },
                title = { Text("选择 cwd") },
                text = {
                    Column {
                        Text("相对于 files/ 的目录，留空表示根目录。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        TextField(value = cwdInput, onValueChange = { cwdInput = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), placeholder = { Text("例如 src/project") })
                        Spacer(Modifier.height(6.dp))
                        Text("files/linux/tmp 为独立目录；Linux 和 PTY 会同步使用此 cwd。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = TSub)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        UserWorkspaceShell.setCwd(cwdInput).onSuccess {
                            cwd = UserWorkspaceShell.relativeCwd()
                            showCwdDialog = false
                            // A persistent shell keeps its old process cwd. Rebuild every
                            // backend session so the selected directory applies to the
                            // visible terminal and the agent PTY alike.
                            TermuxPtySessionManager.restartAll()
                            if (isPtyBackend) ptyRestartToken++ else terminal.close()
                        }
                    }) { Text("应用") }
                },
                dismissButton = { TextButton(onClick = { showCwdDialog = false }) { Text("取消") } }
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF20232E)))

        if (!isPtyBackend && backend == WorkspaceRuntime.Backend.ANDROID_SHELL) {
            EnvironmentStatusCard(prootState, onDeployEnvironment)
        }

        if (isPtyBackend) {
            PtySessionTabs(
                selectedId = ptySessionId,
                version = ptyTabsVersion,
                onSelect = { id -> TermuxPtySessionManager.select(id); ptySessionId = id },
                onAdd = {
                    ptySessionId = TermuxPtySessionManager.createSession()
                    ptyTabsVersion++
                },
                onClose = { id ->
                    TermuxPtySessionManager.closeSession(id)
                    ptySessionId = TermuxPtySessionManager.activeId()
                    ptyTabsVersion++
                }
            )
            TermuxPtyTerminal(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                restartToken = ptyRestartToken,
                sessionId = ptySessionId,
            )
        } else {
            LegacyTerminalBody(
                terminal = terminal,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PtySessionTabs(
    selectedId: String,
    version: Int,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: (String) -> Unit
) {
    val sessions = remember(version) { TermuxPtySessionManager.statuses() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF171A21)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { state ->
            val label = if (state.id == "terminal") "终端" else state.id
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(if (state.id == selectedId) Color(0xFF2A493B) else Color(0xFF242934))
                    .clickable { onSelect(state.id) }.padding(start = 10.dp, end = if (state.id == "terminal") 10.dp else 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (state.running) TGreen else TSub))
                Spacer(Modifier.width(6.dp)); Text(label, fontSize = 11.sp, color = TInk, maxLines = 1)
                if (state.id != "terminal") IconButton(onClick = { onClose(state.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭 $label", tint = TSub, modifier = Modifier.size(16.dp))
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "新建终端会话", tint = TInk)
        }
    }
}

@Composable
private fun EnvironmentStatusCard(
    state: ProotLinuxEnvironment.State,
    onDeployEnvironment: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1B202A))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (state == ProotLinuxEnvironment.State.SETTING_UP) "正在部署免 Root Ubuntu…" else "免 Root Ubuntu 尚未部署",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TInk,
            )
            Text(
                if (state == ProotLinuxEnvironment.State.SETTING_UP) "部署日志会持续显示在下方" else "部署后可使用 apt、Python、Node 和 Git",
                fontSize = 10.sp,
                fontFamily = Mono,
                color = TSub,
            )
        }
        Button(
            onClick = onDeployEnvironment,
            enabled = state != ProotLinuxEnvironment.State.SETTING_UP && ProotLinuxEnvironment.isSupported(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (state == ProotLinuxEnvironment.State.ERROR) "重试" else "部署", fontSize = 12.sp)
        }
    }
}

@Composable
private fun LegacyTerminalBody(
    terminal: TerminalState,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(terminal.lines.size) {
        if (terminal.lines.isNotEmpty()) listState.animateScrollToItem(terminal.lines.size - 1)
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            items(terminal.lines) { line ->
                val color = when {
                    line.startsWith("$ ") -> TGreen
                    line.startsWith("[exit 0]") -> TSub
                    line.startsWith("[exit ") || line.startsWith("[错误]") || line.startsWith("[异常]") -> Color(0xFFE0685C)
                    else -> TInk
                }
                Text(line, fontSize = 11.sp, fontFamily = Mono, color = color, lineHeight = 15.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKey("ESC") { terminal.sendControl(byteArrayOf(0x1B)) }
            TerminalKey("TAB") { terminal.sendControl(byteArrayOf(0x09)) }
            TerminalKey("CTRL-C") { terminal.stop() }
            TerminalKey("CTRL-D") { terminal.sendControl(byteArrayOf(0x04)) }
            TerminalIconKey(Icons.Outlined.KeyboardArrowUp, "上方向键") { terminal.sendControl("\u001B[A".toByteArray()) }
            TerminalIconKey(Icons.Outlined.KeyboardArrowDown, "下方向键") { terminal.sendControl("\u001B[B".toByteArray()) }
            TerminalIconKey(Icons.Outlined.KeyboardArrowLeft, "左方向键") { terminal.sendControl("\u001B[D".toByteArray()) }
            TerminalIconKey(Icons.Outlined.KeyboardArrowRight, "右方向键") { terminal.sendControl("\u001B[C".toByteArray()) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("❯", fontSize = 14.sp, fontFamily = Mono, color = TGreen)
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !terminal.running,
                singleLine = true,
                placeholder = { Text(if (terminal.running) "执行中…" else "输入命令,回车执行", fontSize = 12.sp, fontFamily = Mono, color = TSub) },
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = Mono),
                keyboardActions = KeyboardActions(onDone = {
                    val command = input
                    input = ""
                    if (command.isNotBlank()) scope.launch { terminal.run(command) }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF161923),
                    unfocusedContainerColor = Color(0xFF161923),
                    cursorColor = TGreen,
                    focusedTextColor = TInk,
                    unfocusedTextColor = TInk,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (terminal.running) TGreen.copy(alpha = 0.4f) else TGreen)
                    .clickable(enabled = !terminal.running) {
                        val command = input
                        input = ""
                        if (command.isNotBlank()) scope.launch { terminal.run(command) }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("运行", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = TBg)
            }
        }
    }
}

@Composable
private fun TerminalKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF1B202A))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 10.sp, fontFamily = Mono, color = TInk)
    }
}

@Composable
private fun TerminalIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF1B202A)),
    ) {
        Icon(icon, contentDescription = description, tint = TInk, modifier = Modifier.size(20.dp))
    }
}
