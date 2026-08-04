package com.hsucode.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.StateCursorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskRecoveryScreen(app: HsucodeApplication, onBack: () -> Unit) {
    val colors = LocalHsuColors.current
    val cursors by app.database.stateCursorDao().observeAll().collectAsState(initial = emptyList())
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(cursors) {
        titles = withContext(Dispatchers.IO) {
            cursors.associate { cursor ->
                cursor.sessionId to when {
                    cursor.sessionId > 0 -> app.database.sessionDao().getById(cursor.sessionId)?.title
                        ?: "会话 ${cursor.sessionId}"
                    cursor.sessionId <= -1_000_000L -> {
                        val taskId = -1_000_000L - cursor.sessionId
                        app.database.kanbanTaskDao().getById(taskId)?.title ?: "看板任务 $taskId"
                    }
                    else -> "后台任务"
                }
            }
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("任务恢复", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (cursors.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = colors.green, modifier = Modifier.size(32.dp))
                Text("没有待恢复任务", Modifier.padding(top = 10.dp), color = colors.sub)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cursors, key = { it.sessionId }) { cursor ->
                    RecoveryRow(
                        cursor = cursor,
                        title = titles[cursor.sessionId] ?: "任务 ${cursor.sessionId}",
                        onResume = { app.resumeRecoveryTask(cursor.sessionId) },
                        onDiscard = { app.discardRecoveryTask(cursor.sessionId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryRow(
    cursor: StateCursorEntity,
    title: String,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val colors = LocalHsuColors.current
    Surface(color = colors.bgElevated, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, tint = colors.sub, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${stateLabel(cursor.state)} · ${formatTime(cursor.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sub
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiscard) { Text("放弃") }
                OutlinedButton(onClick = onResume) { Text("继续") }
            }
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    "Thinking" -> "等待模型"
    "CallingTool" -> "准备调用工具"
    "WaitingConfirm" -> "等待确认"
    "Executing" -> "执行工具"
    else -> "可恢复"
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
