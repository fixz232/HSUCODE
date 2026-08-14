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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.data.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryRelationsScreen(database: AppDatabase, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); var memories by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }; var query by remember { mutableStateOf("") }; var notice by remember { mutableStateOf("") }
    fun refresh() { scope.launch(Dispatchers.IO) { memories = database.memoryDao().getAll() } }
    LaunchedEffect(Unit) { refresh() }
    val related = remember(memories) { MemoryRelations.relatedByTags(memories) }
    val visible = memories.filter { query.isBlank() || it.title.contains(query, true) || it.content.contains(query, true) || it.tags.contains(query, true) }
    Scaffold(topBar = { TopAppBar(title = { Text("记忆关系") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = { IconButton(onClick = ::refresh) { Icon(Icons.Outlined.Refresh, "刷新") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索记忆") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val map = MemoryRelations.relatedByTags(memories)
                        memories.forEach { database.memoryDao().upsert(MemoryRelations.withRelationTags(it, map[it.id].orEmpty())) }
                        withContext(Dispatchers.Main) { notice = "已整理 ${memories.size} 条记忆的标签关系"; refresh() }
                    }
                }) { Icon(Icons.Outlined.AccountTree, null); Text("整理关系", modifier = Modifier.padding(start = 5.dp)) }
                Text("${memories.size} 条", modifier = Modifier.padding(top = 10.dp))
            }
            if (notice.isNotBlank()) Text(notice, modifier = Modifier.padding(bottom = 6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { memory ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(memory.title); Text(memory.content, maxLines = 2, overflow = TextOverflow.Ellipsis, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        val targets = related[memory.id].orEmpty()
                        Text(if (targets.isEmpty()) "未找到共享标签关系" else "关联记忆：${targets.joinToString(", ") { "#$it" }}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
