package com.hsucode.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Builds explicit, reviewable UI-automation plans and exposes the task overlay controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityAutomationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val taskState by AutomationTaskRunner.state.collectAsState()
    val overlayAvailable = taskState.overlayAvailable || AutomationTaskRunner.canDrawOverlay()
    val shizuku by ShizukuManager.state.collectAsState()
    val steps = remember { mutableStateListOf<AutomationStep>() }
    var packageName by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var waitSeconds by remember { mutableStateOf("1") }
    var result by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf(emptyList<String>()) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("无障碍自动化") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(if (AccessibilityBridge.isEnabled()) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null)
                        Text(if (AccessibilityBridge.isEnabled()) "无障碍服务已启用" else "无障碍服务未启用", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("执行前检查：无障碍用于页面操作；悬浮窗用于后台控制；Shizuku 仅作为系统增强通道，不会绕过步骤审查。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                            Text("无障碍设置", Modifier.padding(start = 5.dp))
                        }
                        OutlinedButton(onClick = { AutomationTaskRunner.openOverlaySettings() }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                            Text(if (overlayAvailable) "悬浮窗已授权" else "授权悬浮窗", Modifier.padding(start = 5.dp))
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("增强通道", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        Text("Shizuku：${shizuku.label}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    Text(if (overlayAvailable) "悬浮窗就绪" else "需要悬浮窗", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }

            OutlinedTextField(
                packageName,
                { packageName = it.trim() },
                label = { Text("目标应用包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如 com.ss.android.ugc.aweme") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { steps += AutomationStep(AutomationStep.Type.LAUNCH_APP, argument = packageName) },
                    enabled = packageName.isNotBlank(),
                ) { Icon(Icons.Outlined.Add, null); Text("启动应用", Modifier.padding(start = 4.dp)) }
                OutlinedButton(onClick = { steps += AutomationStep(AutomationStep.Type.BACK) }) { Text("返回") }
                OutlinedButton(onClick = { steps += AutomationStep(AutomationStep.Type.HOME) }) { Text("主页") }
            }

            OutlinedTextField(target, { target = it }, label = { Text("控件文本、描述或资源 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { steps += AutomationStep(AutomationStep.Type.CLICK_TEXT, argument = target) },
                    enabled = target.isNotBlank(),
                ) { Icon(Icons.Outlined.Add, null); Text("点击控件", Modifier.padding(start = 4.dp)) }
                TextButton(
                    onClick = { result = AccessibilityBridge.clickText(target).fold({ it }, { "失败：${it.message}" }) },
                    enabled = AccessibilityBridge.isEnabled() && target.isNotBlank(),
                ) { Text("立即点击") }
                OutlinedButton(onClick = { steps += AutomationStep(AutomationStep.Type.DUMP) }) { Text("读取页面") }
            }
            OutlinedTextField(value, { value = it }, label = { Text("输入内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { steps += AutomationStep(AutomationStep.Type.INPUT_TEXT, argument = target, value = value) },
                enabled = target.isNotBlank() && value.isNotBlank(),
            ) { Icon(Icons.Outlined.Add, null); Text("添加输入步骤", Modifier.padding(start = 4.dp)) }
            TextButton(
                onClick = { result = AccessibilityBridge.inputText(target, value).fold({ it }, { "失败：${it.message}" }) },
                enabled = AccessibilityBridge.isEnabled() && target.isNotBlank() && value.isNotBlank(),
            ) { Text("立即输入") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(waitSeconds, { waitSeconds = it }, label = { Text("等待秒数") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val ms = ((waitSeconds.toDoubleOrNull() ?: 1.0) * 1_000).toLong().coerceIn(1_000L, 120_000L)
                    steps += AutomationStep(AutomationStep.Type.WAIT, durationMs = ms)
                }) { Icon(Icons.Outlined.Add, null); Text("等待", Modifier.padding(start = 4.dp)) }
            }

            if (taskState.running || taskState.terminal) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${taskState.taskName.ifBlank { "自动化任务" }} ${taskState.completed}/${taskState.total}")
                        Text(taskState.error ?: taskState.current)
                        if (taskState.lastOutput.isNotBlank()) Text(taskState.lastOutput.take(220))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (taskState.running) {
                                OutlinedButton(onClick = { if (taskState.paused) AutomationTaskRunner.resume() else AutomationTaskRunner.pause() }) {
                                    Icon(if (taskState.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
                                    Text(if (taskState.paused) "继续" else "暂停", Modifier.padding(start = 4.dp))
                                }
                                OutlinedButton(onClick = { AutomationTaskRunner.cancel() }) { Icon(Icons.Outlined.Stop, null); Text("停止", Modifier.padding(start = 4.dp)) }
                            }
                        }
                    }
                }
            }

            Text("线性步骤 (${steps.size})")
            Text("按顺序执行；每步的输出、失败原因和暂停状态均保留在本页与悬浮窗中。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(steps) { index, step ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${index + 1}. ${step.description()}", modifier = Modifier.weight(1f))
                            IconButton(onClick = { if (!taskState.running) steps.removeAt(index) }, enabled = !taskState.running) {
                                Icon(Icons.Outlined.DeleteOutline, "删除步骤")
                            }
                        }
                    }
                }
                if (steps.isEmpty()) item { Text("添加步骤后开始执行。运行时会显示 Phone Agent 悬浮窗进度。") }
                if (nodes.isNotEmpty()) item { Text("当前页面：${nodes.joinToString(" | ").take(1_200)}") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    nodes = AccessibilityBridge.snapshot()
                    result = "已读取 ${nodes.size} 个可见元素"
                }, enabled = AccessibilityBridge.isEnabled()) { Text("读取节点") }
                Button(
                    onClick = { result = AutomationTaskRunner.start("自定义自动化", steps.toList()).fold({ it }, { "无法启动：${it.message}" }) },
                    enabled = steps.isNotEmpty() && !taskState.running,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Outlined.PlayArrow, null); Text("开始执行", Modifier.padding(start = 6.dp)) }
            }
            if (result.isNotBlank()) Text(result)
        }
    }
}
