/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: adds the user interface for encrypted backup and cross-device restore.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(app: HsucodeApplication, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalHsuColors.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordAgain by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var restorePreview by remember { mutableStateOf<BackupManager.RestorePreview?>(null) }

    fun fail(message: String) {
        status = message
        statusError = true
        busy = false
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = exportPassword
        exportPassword = null
        if (uri == null || pass == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在生成加密备份…"; statusError = false
        scope.launch {
            runCatching {
                BackupManager.export(
                    context, app.database, app.keystore, uri, pass,
                    UpdateChecker.currentVersion(context)
                )
            }.onSuccess {
                status = "备份完成 · ${formatBytes(it.bytes)} · ${it.attachmentCount} 个附件"
                statusError = false; busy = false
            }.onFailure { fail(it.message ?: "备份失败") }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri
            password = ""; passwordAgain = ""; passwordMode = "restore"
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BackupAction(
                icon = Icons.Outlined.Backup,
                title = "创建全量备份",
                detail = "会话、配置、记忆、项目、凭据与附件",
                enabled = !busy,
                primary = true,
                onClick = { password = ""; passwordAgain = ""; passwordMode = "export" }
            )
            BackupAction(
                icon = Icons.Outlined.Restore,
                title = "从备份恢复",
                detail = "校验完成后重启应用生效",
                enabled = !busy,
                primary = false,
                onClick = { openDocument.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }
            )
            if (status.isNotBlank()) {
                Surface(color = colors.bgElevated, shape = MaterialTheme.shapes.small) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(
                            if (statusError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (statusError) colors.red else colors.green,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(status, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                "外部工作区与 Ubuntu 环境不包含在备份中",
                style = MaterialTheme.typography.bodySmall,
                color = colors.sub,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    passwordMode?.let { mode ->
        val exporting = mode == "export"
        AlertDialog(
            onDismissRequest = { passwordMode = null },
            title = { Text(if (exporting) "设置备份密码" else "输入备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (exporting) {
                        OutlinedTextField(
                            value = passwordAgain,
                            onValueChange = { passwordAgain = it },
                            label = { Text("再次输入") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.length >= 8 && (!exporting || password == passwordAgain),
                    onClick = {
                        val chars = password.toCharArray()
                        passwordMode = null
                        password = ""; passwordAgain = ""
                        if (exporting) {
                            exportPassword = chars
                            val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            createDocument.launch("HSUCODE-$date.hsucode-backup")
                        } else {
                            val uri = restoreUri ?: return@TextButton
                            busy = true; status = "正在解密并校验备份…"; statusError = false
                            scope.launch {
                                runCatching { BackupManager.stageRestore(context, uri, chars) }
                                    .onSuccess {
                                        restorePreview = it
                                        status = "恢复数据已就绪"
                                        statusError = false; busy = false
                                    }
                                    .onFailure { fail(it.message ?: "恢复失败") }
                            }
                        }
                    }
                ) { Text(if (exporting) "选择保存位置" else "校验并恢复") }
            },
            dismissButton = { TextButton(onClick = { passwordMode = null }) { Text("取消") } }
        )
    }

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { restorePreview = null },
            title = { Text("恢复已准备") },
            text = {
                Text("备份版本 ${preview.appVersion} · ${preview.attachmentCount} 个附件。应用将重启并保留当前数据库副本。")
            },
            confirmButton = {
                Button(onClick = { BackupManager.restartApplication(context) }) { Text("立即重启") }
            },
            dismissButton = { TextButton(onClick = { restorePreview = null }) { Text("稍后") } }
        )
    }
}

@Composable
private fun BackupAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier.fillMaxWidth().height(58.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = null)
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = null)
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
