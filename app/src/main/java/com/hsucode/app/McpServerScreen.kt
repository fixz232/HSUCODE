package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.McpServerEntity
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import com.hsucode.provider.McpOAuthClient
import com.hsucode.provider.McpSessionRegistry

@Composable
fun McpServerScreen(mcpManager: McpManager, onBack: () -> Unit) {
    val colors = LocalHsuColors.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<McpServerEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showOAuthDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<McpServerEntity?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val sessionStates by mcpManager.sessionRegistry.states.collectAsState()

    fun refresh() {
        scope.launch { servers = mcpManager.getAllServers() }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Text("MCP 服务器", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showOAuthDialog = true }) { Icon(Icons.Outlined.Lock, "OAuth 授权") }
                IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Outlined.Add, "添加 HTTP 服务器") }
            }
        }
        status?.let { (message, isError) ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .background(if (isError) colors.red.copy(alpha = 0.1f) else colors.green.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                        null,
                        tint = if (isError) colors.red else colors.green
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { status = null }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Close, "关闭提示", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (servers.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = colors.faint, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("尚未添加 MCP 服务器", color = colors.sub)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加服务器")
                    }
                }
            }
        }
        items(servers, key = { it.id }) { server ->
            McpServerRow(
                server = server,
                busy = busyId == server.id,
                sessionState = sessionStates[if (server.transport == "stdio") "stdio:${server.name}" else "http:${server.url}"],
                onConnectToggle = {
                    if (busyId != null) return@McpServerRow
                    busyId = server.id
                    scope.launch {
                        if (server.connected) {
                            mcpManager.disconnectServer(server.id)
                            status = "已断开 ${server.name}" to false
                        } else {
                            when (val result = mcpManager.connectStoredServer(server.id)) {
                                is McpConnectResult.Success -> status = "已连接 ${result.serverName}，发现 ${result.toolCount} 个工具" to false
                                is McpConnectResult.Error -> status = result.message to true
                            }
                        }
                        busyId = null
                        refresh()
                    }
                },
                onDelete = { deleteTarget = server }
            )
        }
    }

    if (showAddDialog) {
        McpAddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, auth ->
                showAddDialog = false
                scope.launch {
                    busyId = 0L
                    when (val result = mcpManager.connectServer(name, url, auth)) {
                        is McpConnectResult.Success -> status = "已连接 ${result.serverName}，发现 ${result.toolCount} 个工具" to false
                        is McpConnectResult.Error -> status = "配置已保存：${result.message}" to true
                    }
                    busyId = null
                    refresh()
                }
            }
        )
    }
    if (showOAuthDialog) {
        McpOAuthDialog(
            mcpManager = mcpManager,
            onDismiss = { showOAuthDialog = false },
            onResult = { result ->
                showOAuthDialog = false
                status = when (result) {
                    is McpConnectResult.Success -> "OAuth 已连接 ${result.serverName}，发现 ${result.toolCount} 个工具" to false
                    is McpConnectResult.Error -> result.message to true
                }
                refresh()
            }
        )
    }
    deleteTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("删除 MCP 服务器") },
            text = { Text("将删除“${server.name}”的配置和连接状态。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        mcpManager.deleteServer(server.id)
                        status = "已删除 ${server.name}" to false
                        refresh()
                    }
                }) { Text("删除", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun McpOAuthDialog(
    mcpManager: McpManager,
    onDismiss: () -> Unit,
    onResult: (McpConnectResult) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var deviceEndpoint by remember { mutableStateOf("") }
    var tokenEndpoint by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var scopeText by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<McpOAuthClient.DeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun open(urlText: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlText))) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCP OAuth 设备授权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("服务器名称") })
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("MCP URL") })
                OutlinedTextField(deviceEndpoint, { deviceEndpoint = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("device code endpoint") })
                OutlinedTextField(tokenEndpoint, { tokenEndpoint = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("token endpoint") })
                OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("OAuth client_id") })
                OutlinedTextField(scopeText, { scopeText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("scope（可选）") })
                device?.let { code ->
                    Text("用户码：${code.userCode.ifBlank { "请在浏览器确认" }}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { open(code.verificationUri) }, enabled = code.verificationUri.isNotBlank()) { Text("打开授权页面") }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && url.isNotBlank() && deviceEndpoint.isNotBlank() && tokenEndpoint.isNotBlank() && clientId.isNotBlank(), onClick = {
                if (busy) return@TextButton
                busy = true; error = ""
                scope.launch {
                    if (device == null) {
                        mcpManager.requestOAuthDeviceCode(deviceEndpoint.trim(), clientId.trim(), scopeText.trim()).fold(
                            onSuccess = { code -> device = code; open(code.verificationUri); busy = false },
                            onFailure = { error = it.message.orEmpty(); busy = false }
                        )
                    } else {
                        mcpManager.pollOAuthToken(tokenEndpoint.trim(), clientId.trim(), device!!).fold(
                            onSuccess = { token ->
                                val result = mcpManager.connectServer(name.trim(), url.trim(), "Bearer $token")
                                onResult(result)
                            },
                            onFailure = { error = it.message.orEmpty(); busy = false }
                        )
                    }
                }
            }) { Text(if (device == null) "获取设备码" else if (busy) "连接中…" else "授权并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun McpServerRow(server: McpServerEntity, busy: Boolean, sessionState: McpSessionRegistry.SessionState?, onConnectToggle: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalHsuColors.current
    val endpoint = if (server.transport == "stdio") {
        (server.command + " " + server.argsJson).trim()
    } else server.url
    Surface(
        color = colors.bgElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (server.transport == "stdio") Icons.Outlined.Terminal else Icons.Outlined.Language,
                    null, tint = colors.sub, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        endpoint.ifBlank { "未配置端点" },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(when (sessionState?.status) {
                        McpSessionRegistry.Status.RECONNECTING -> "重连中"
                        McpSessionRegistry.Status.CONNECTING -> "连接中"
                        McpSessionRegistry.Status.ERROR -> "异常"
                        McpSessionRegistry.Status.CONNECTED -> "已连接"
                        else -> if (server.connected) "已连接" else "未连接"
                    }) },
                    leadingIcon = {
                        val connectedNow = sessionState?.status == McpSessionRegistry.Status.CONNECTED || (sessionState == null && server.connected)
                        Icon(
                            if (connectedNow) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            null, modifier = Modifier.size(16.dp),
                            tint = if (connectedNow) colors.green else colors.faint
                        )
                    }
                )
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = colors.sub) }
            }
            if (server.toolNames.isNotBlank()) {
                Text(
                    "${server.toolNames.split(',').size} 个工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.sub,
                    modifier = Modifier.padding(start = 30.dp, top = 4.dp)
                )
            }
            if (sessionState?.message?.isNotBlank() == true) {
                Text(sessionState.message.take(120), style = MaterialTheme.typography.bodySmall, color = colors.sub, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onConnectToggle, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (server.connected) "断开" else "连接")
                }
            }
        }
    }
}

@Composable
private fun McpAddServerDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && runCatching {
        val parsed = java.net.URI(url.trim())
        parsed.scheme == "http" || parsed.scheme == "https"
    }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 HTTP MCP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称") })
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("服务器 URL") })
                OutlinedTextField(
                    auth, { auth = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Authorization（可选）") },
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name.trim(), url.trim(), auth.trim()) }, enabled = valid) { Text("添加并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
