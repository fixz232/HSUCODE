package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import com.hsucode.data.AppDatabase
import com.hsucode.data.GlobalSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PromptTemplate(val title: String, val summary: String, val content: String)

private val promptTemplates = listOf(
    PromptTemplate("严谨开发", "强调读取上下文、最小变更与验证。", "处理开发任务时，先读取相关代码与约束；仅改动任务范围内的文件；完成后执行可用的最小验证。遇到不确定信息时明确说明，不要假定成功。"),
    PromptTemplate("代码审查", "优先发现风险、回归与缺失测试。", "执行代码审查时先列出按严重性排序的问题，并给出文件和行号。重点检查行为回归、数据安全、并发、权限和缺失测试；无问题时明确残余风险。"),
    PromptTemplate("产品交付", "面向可用结果而非泛泛建议。", "处理产品功能时保持界面清晰、触控区域足够大、状态可见。实现后验证空状态、错误状态和关键交互；不要使用无法执行的占位能力。")
)

@Composable
fun PromptLibraryScreen(database: AppDatabase, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = LocalHsuColors.current
    var prompt by remember { mutableStateOf("") }
    var savedSettings by remember { mutableStateOf(GlobalSettingsEntity()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        savedSettings = withContext(Dispatchers.IO) { database.globalSettingsDao().get() ?: GlobalSettingsEntity() }
        prompt = savedSettings.globalSystemPrompt
        loading = false
    }
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        WorkbenchTopBar("提示词库", onBack)
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("全局提示词", style = MaterialTheme.typography.titleMedium)
                Text("会在新一轮对话中叠加到系统指令。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
            }
            item {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    enabled = !loading,
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("添加跨会话都需要遵守的指令") }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { database.globalSettingsDao().upsert(savedSettings.copy(globalSystemPrompt = prompt.trim())) }
                            onSaved()
                            message = "已保存，下一轮对话会使用新提示词"
                        }
                    }, enabled = !loading, modifier = Modifier.height(50.dp)) { Text("保存全局提示词") }
                    if (prompt.isNotBlank()) TextButton(onClick = { prompt = "" }, modifier = Modifier.height(50.dp)) { Text("清空") }
                }
            }
            message?.let { text -> item { InlineNotice(text, false) { message = null } } }
            item { Text("模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(promptTemplates, key = { it.title }) { item ->
                ElevatedCard(onClick = { prompt = item.content }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, tint = colors.green)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(item.summary, style = MaterialTheme.typography.bodySmall, color = colors.sub)
                        }
                        Text("使用", style = MaterialTheme.typography.labelLarge, color = colors.green)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
