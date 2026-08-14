package com.hsucode.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceReviewScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); var output by remember { mutableStateOf("点击刷新读取 Git 状态和未提交差异。") }; var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    fun run(command: String) { busy = true; output = ""; scope.launch { val result = WorkspaceRuntime.runStreaming(command) { output += "$it\n" }; output += "\n[exit ${result.exitCode}] ${result.stderr}"; busy = false } }
    Scaffold(topBar = { TopAppBar(title = { Text("工作区变更审查") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = { IconButton(enabled = !busy, onClick = { run("git status --short && git diff --stat && git diff -- .") }) { Icon(Icons.Outlined.Refresh, "刷新变更") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("提交前先审查变更。Git 命令在当前工作区 cwd 执行，提交内容仍须经过权限确认。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(enabled = !busy, onClick = { run("git status --short && git diff --stat && git diff -- .") }) { Text(if (busy) "执行中" else "查看差异") }; TextButton(enabled = !busy, onClick = { run("git init && git status --short") }) { Text("初始化仓库") } }
            OutlinedTextField(message, { message = it }, label = { Text("提交说明") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && message.isNotBlank(), onClick = { run("git add -A && git commit -m ${workspaceShellQuote(message)}") }) { Text("提交当前变更") }
            Text(output, modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace)
        }
    }
}

private fun workspaceShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
