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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.data.PermissionRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionRulesScreen(database: AppDatabase, onBack: () -> Unit, onChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<PermissionRuleEntity>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    fun refresh() { scope.launch(Dispatchers.IO) { rules = database.permissionRuleDao().getAll() } }
    LaunchedEffect(Unit) { refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("细粒度权限规则") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "添加规则") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text("规则按拒绝优先、精确工具和目标模式匹配。示例：file_write + workspace/**。", modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.id }) { rule ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${rule.action.uppercase()} · ${rule.toolFilter}")
                            Text(rule.pattern.ifBlank { "任意目标" }, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            if (rule.note.isNotBlank()) Text(rule.note, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { database.permissionRuleDao().deleteById(rule.id); withContext(Dispatchers.Main) { onChanged(); refresh() } } }) { Icon(Icons.Outlined.DeleteOutline, "删除规则") }
                    }
                }
            }
        }
    }
    if (showAdd) AddRuleDialog(onDismiss = { showAdd = false }, onSave = { rule -> scope.launch(Dispatchers.IO) { database.permissionRuleDao().insert(rule); withContext(Dispatchers.Main) { onChanged(); showAdd = false; refresh() } } })
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onSave: (PermissionRuleEntity) -> Unit) {
    var action by remember { mutableStateOf("allow") }; var tool by remember { mutableStateOf("file_*") }; var pattern by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加权限规则") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(action, { action = it }, label = { Text("allow 或 deny") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tool, { tool = it }, label = { Text("工具过滤器") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pattern, { pattern = it }, label = { Text("目标模式，可留空") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    } }, confirmButton = { Button(onClick = { onSave(PermissionRuleEntity(action = action.trim().lowercase().let { if (it == "deny") "deny" else "allow" }, toolFilter = tool.trim().ifBlank { "*" }, pattern = pattern.trim(), note = note.trim())) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
