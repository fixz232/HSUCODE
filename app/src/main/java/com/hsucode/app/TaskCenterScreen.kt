package com.hsucode.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterScreen(runtime: TaskRuntimeManager, onBack: () -> Unit) {
    val tasks by runtime.tasks.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("统一任务中心") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        }, actions = { IconButton(onClick = runtime::refresh) { Icon(Icons.Outlined.Refresh, "刷新") } })
    }) { padding ->
        if (tasks.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                Text("暂无任务记录", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tasks, key = { it.id }) { task ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall)
                                Text("${task.type} · ${statusLabel(task.status)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (task.status == TaskRunStatus.RUNNING || task.status == TaskRunStatus.WAITING_PERMISSION) {
                                IconButton(onClick = { runtime.cancel(task.id) }) { Icon(Icons.Outlined.PauseCircleOutline, "取消任务") }
                            } else IconButton(onClick = { runtime.delete(task.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除记录") }
                        }
                        if (task.progress > 0) LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (task.checkpoint.isNotBlank()) Text(task.checkpoint.take(180), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        if (task.error.isNotBlank()) Text(task.error.take(180), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: TaskRunStatus): String = when (status) {
    TaskRunStatus.QUEUED -> "排队中"; TaskRunStatus.RUNNING -> "运行中"; TaskRunStatus.WAITING_PERMISSION -> "等待权限"
    TaskRunStatus.PAUSED -> "已暂停"; TaskRunStatus.SUCCEEDED -> "已完成"; TaskRunStatus.FAILED -> "失败"; TaskRunStatus.CANCELLED -> "已取消"
}
