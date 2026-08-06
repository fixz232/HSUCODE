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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hsucode.tools.WorkspaceContext

/** User-facing document/PPT export workflow used by the Agent and workbench. */
@Composable
fun DocumentWorkbenchScreen(
    workspaceRoot: String,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val colors = LocalHsuColors.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("HSUCODE 工作成果") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun create(format: DocumentExport.Format) {
        if (title.isBlank() || content.isBlank()) {
            status = "请先填写标题和内容"
            return
        }
        busy = true
        status = "正在生成 ${format.name.lowercase()}…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
                    val created = DocumentExport.create(root, format, title, content)
                    "已生成：${created.file.absolutePath}"
                }.getOrElse { "生成失败：${it.message ?: "未知错误"}" }
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.width(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Text("文档与演示", color = colors.ink, fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Text("生成文件会保存到当前工作区的 exports 目录。", color = colors.sub, fontSize = 13.sp)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("内容") },
            placeholder = { Text("支持 Markdown 标题；PPTX 可用单独一行 --- 分隔幻灯片") },
            minLines = 12,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { create(DocumentExport.Format.PPTX) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("PPTX")
            }
            Button(onClick = { create(DocumentExport.Format.DOCX) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("DOCX")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { create(DocumentExport.Format.PDF) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("PDF")
            }
            OutlinedButton(onClick = { create(DocumentExport.Format.HTML) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("HTML")
            }
        }
        if (status.isNotBlank()) Text(status, color = colors.sub, fontSize = 12.sp)
        OutlinedButton(onClick = onOpenFiles, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Text("在文件工作区查看导出文件")
        }
    }
}
