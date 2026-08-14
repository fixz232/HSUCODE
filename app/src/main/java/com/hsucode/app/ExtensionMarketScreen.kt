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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.data.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

private enum class ExtensionTab(val label: String) { MCP("MCP"), SKILLS("Skills"), MANIFESTS("清单") }
private enum class MarketMcpKind { STDIO, REMOTE }

private data class MarketMcp(
    val id: String,
    val title: String,
    val description: String,
    val kind: MarketMcpKind,
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val needsAuth: Boolean = false
)

private data class MarketSkill(val name: String, val description: String, val content: String)

private val marketMcps = listOf(
    MarketMcp(
        id = "filesystem",
        title = "工作区文件系统",
        description = "在 /workspace 内提供目录、读取与写入工具",
        kind = MarketMcpKind.STDIO,
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace")
    ),
    MarketMcp(
        id = "fetch",
        title = "网页抓取",
        description = "通过 uvx 提供网页请求与内容提取工具",
        kind = MarketMcpKind.STDIO,
        command = "uvx",
        args = listOf("mcp-server-fetch")
    ),
    MarketMcp(
        id = "git",
        title = "Git 仓库工具",
        description = "针对当前 /workspace 提供 Git 仓库操作",
        kind = MarketMcpKind.STDIO,
        command = "uvx",
        args = listOf("mcp-server-git", "--repository", "/workspace")
    ),
    MarketMcp(
        id = "github_remote",
        title = "GitHub Remote MCP",
        description = "GitHub 官方远程 MCP，需要 GitHub OAuth 或 Bearer 凭据",
        kind = MarketMcpKind.REMOTE,
        url = "https://api.githubcopilot.com/mcp/",
        needsAuth = true
    )
)

private val marketSkills = listOf(
    MarketSkill(
        "android-workspace",
        "在 Android PRoot 工作区中执行、检查和交付代码任务。",
        """# Android Workspace
先确认当前工作区、环境和权限。修改文件前读取相关内容；每次写入后运行最小验证。需要网络或安装依赖时先说明原因。不要读写应用私有数据库目录。"""
    ),
    MarketSkill(
        "web-export",
        "建立可在手机工作区中编辑并导出的静态 Web 项目。",
        """# Web Export
优先使用标准 HTML、CSS 和原生 JavaScript。保持 index.html、styles.css 和 app.js 分离；完成后检查相对路径、移动端 viewport 和离线可打开性。"""
    ),
    MarketSkill(
        "git-review",
        "在提交前检查变更范围、敏感信息和可验证结果。",
        """# Git Review
先查看 git status 和 diff，确认没有 API Key、私钥、构建产物或不相关文件。提交信息描述用户可见结果；没有通过验证时不要声称完成。"""
    )
)

@Composable
fun ExtensionMarketScreen(
    database: AppDatabase,
    mcpManager: McpManager,
    onBack: () -> Unit,
    onEnvironment: () -> Unit,
    onManageMcp: () -> Unit,
    onManageSkills: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = LocalHsuColors.current
    var tab by remember { mutableStateOf(ExtensionTab.MCP) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var authPreset by remember { mutableStateOf<MarketMcp?>(null) }
    var remoteSkill by remember { mutableStateOf(false) }
    var manifestDialog by remember { mutableStateOf(false) }
    var manifests by remember { mutableStateOf<List<ExtensionManifest>>(emptyList()) }

    fun refreshManifests() {
        scope.launch(Dispatchers.IO) {
            manifests = database.settingDao().getByPrefix("extension_manifest_")
                .mapNotNull { runCatching { ExtensionManifestValidator.parse(it.value).getOrThrow() }.getOrNull() }
                .sortedBy { it.name.lowercase() }
        }
    }

    LaunchedEffect(Unit) { refreshManifests() }

    fun installMcp(item: MarketMcp, auth: String = "") {
        if (item.kind == MarketMcpKind.STDIO && WorkspaceRuntime.backend() != WorkspaceRuntime.Backend.PROOT_UBUNTU) {
            notice = "请先在环境配置中部署免 Root Ubuntu，并安装 Node 或 Python 工具。" to true
            return
        }
        busy = item.id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (item.kind == MarketMcpKind.STDIO) {
                    mcpManager.connectStdioServer(item.title, item.command, item.args, emptyMap())
                } else {
                    mcpManager.connectServer(item.title, item.url, auth.trim())
                }
            }
            notice = when (result) {
                is McpConnectResult.Success -> "已连接 ${result.serverName}，发现 ${result.toolCount} 个工具" to false
                is McpConnectResult.Error -> result.message to true
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        WorkbenchTopBar("MCP 与 Skills 市场", onBack)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtensionTab.values().forEach { item ->
                FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.label) }, shape = RoundedCornerShape(8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (tab != ExtensionTab.MANIFESTS) {
                TextButton(onClick = if (tab == ExtensionTab.MCP) onManageMcp else onManageSkills) { Text("已安装") }
            }
        }
        notice?.let { (text, isError) -> InlineNotice(text, isError) { notice = null } }
        if (tab == ExtensionTab.MCP) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("本地预设会在 PRoot Ubuntu 的受限 /workspace 中启动。远程 MCP 不会接触本机文件。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
                items(marketMcps, key = { it.id }) { item ->
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = colors.bgElevated), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.kind == MarketMcpKind.STDIO) Icons.Outlined.Terminal else Icons.Outlined.Link, null, tint = colors.green, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = colors.sub)
                                Text(if (item.kind == MarketMcpKind.STDIO) "${item.command} ${item.args.joinToString(" ")}" else item.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                            Button(onClick = {
                                if (item.needsAuth) authPreset = item else installMcp(item)
                            }, enabled = busy == null, modifier = Modifier.height(42.dp)) {
                                Text(if (busy == item.id) "连接中…" else "安装并连接")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onEnvironment, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("打开环境配置")
                    }
                }
            }
        } else if (tab == ExtensionTab.SKILLS) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("技能是可复用的任务指令。安装后会进入对话可用技能列表，不会执行未知脚本。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                }
                items(marketSkills, key = { it.name }) { item ->
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = colors.bgElevated), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Extension, null, tint = colors.green, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = colors.sub)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                            Button(onClick = {
                                busy = item.name
                                scope.launch {
                                    withContext(Dispatchers.IO) { installMarketSkill(database, item) }
                                    busy = null
                                    notice = "已安装 ${item.name}" to false
                                }
                            }, enabled = busy == null, modifier = Modifier.height(42.dp)) {
                                Text(if (busy == item.name) "安装中…" else "安装")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { remoteSkill = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Outlined.Link, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("从 URL 导入 SKILL.md")
                    }
                }
                item {
                    OutlinedButton(onClick = { manifestDialog = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("导入扩展清单")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "清单是扩展的可审查描述。安装前确认版本、来源和权限；当前运行时只允许安全的 skill 类型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sub,
                    )
                }
                if (manifests.isEmpty()) {
                    item {
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = colors.bgElevated),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Extension, null, tint = colors.sub, modifier = Modifier.size(22.dp))
                                Text("还没有扩展清单", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                                Text("从 Skills 标签导入清单后，这里会显示版本、来源和权限。", style = MaterialTheme.typography.bodySmall, color = colors.sub)
                            }
                        }
                    }
                }
                items(manifests, key = { it.id }) { manifest ->
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = colors.bgElevated),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Extension, null, tint = colors.green, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(manifest.name, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                                    Text("${manifest.kind} · v${manifest.version}", style = MaterialTheme.typography.labelMedium, color = colors.sub)
                                }
                                Text("已安装", style = MaterialTheme.typography.labelMedium, color = colors.green)
                            }
                            if (manifest.description.isNotBlank()) Text(manifest.description, style = MaterialTheme.typography.bodySmall, color = colors.sub)
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Outlined.Security, null, tint = colors.sub, modifier = Modifier.size(17.dp))
                                Text(
                                    if (manifest.permissions.isEmpty()) "权限：无额外权限" else "权限：${manifest.permissions.joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.sub,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            if (manifest.sourceUrl.isNotBlank()) {
                                Text(manifest.sourceUrl, style = MaterialTheme.typography.bodySmall, color = colors.faint, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                OutlinedButton(
                                    onClick = {
                                        busy = manifest.id
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { fetchAndInstallSkill(database, manifest.sourceUrl) }
                                            notice = result.fold({ "已检查并更新 ${manifest.name}" to false }, { "检查失败：${it.message}" to true })
                                            busy = null
                                        }
                                    },
                                    enabled = busy == null,
                                    modifier = Modifier.height(42.dp),
                                ) {
                                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (busy == manifest.id) "检查中…" else "检查更新")
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { manifestDialog = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("导入新清单")
                    }
                }
            }
        }
    }
    authPreset?.let { item ->
        McpAuthDialog(
            item = item,
            onDismiss = { authPreset = null },
            onInstall = { header -> authPreset = null; installMcp(item, header) }
        )
    }
    if (remoteSkill) {
        RemoteSkillDialog(
            onDismiss = { remoteSkill = false },
            onInstall = { url ->
                remoteSkill = false
                busy = "remote-skill"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { fetchAndInstallSkill(database, url) }
                    notice = result.fold({ "已安装 $it" to false }, { (it.message ?: "导入失败") to true })
                    busy = null
                }
            }
        )
    }
    if (manifestDialog) {
        ExtensionManifestDialog(
            onDismiss = { manifestDialog = false },
            onInstall = { raw ->
                manifestDialog = false; busy = "manifest"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val manifest = ExtensionManifestValidator.parse(raw).getOrThrow()
                            require(manifest.kind.equals("skill", true)) { "当前仅允许安装 skill 清单" }
                            if (manifest.sourceUrl.isNotBlank()) fetchAndInstallSkill(database, manifest.sourceUrl).getOrThrow()
                            else {
                                require(manifest.content.isNotBlank()) { "清单缺少 content 或 sourceUrl" }
                                installMarketSkill(database, MarketSkill(manifest.name, manifest.description, manifest.content)); manifest.name
                            }
                            database.settingDao().put("extension_manifest_${manifest.id}", raw)
                            manifest.name
                        }
                    }
                    notice = result.fold({ "已安装扩展 ${it}" to false }, { "安装失败：${it.message}" to true }); busy = null; refreshManifests()
                }
            }
        )
    }

    if (tab == ExtensionTab.MANIFESTS) {
        // The manifest tab is deliberately metadata-only: it makes permissions,
        // source and version reviewable before a future runtime adds more kinds.
        // Installation still goes through the same validator and HTTPS checks.
        // This keeps the market safe while making the extension lifecycle visible.
    }
}

@Composable
private fun ExtensionManifestDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var raw by remember { mutableStateOf("{\n  \"id\": \"my-skill\",\n  \"kind\": \"skill\",\n  \"name\": \"我的技能\",\n  \"description\": \"\",\n  \"permissions\": [],\n  \"content\": \"# 指令\"\n}") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入扩展清单") }, text = { OutlinedTextField(raw, { raw = it }, minLines = 8, modifier = Modifier.fillMaxWidth(), label = { Text("JSON 清单") }) }, confirmButton = { TextButton(onClick = { onInstall(raw) }) { Text("校验并安装") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun McpAuthDialog(item: MarketMcp, onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接 ${item.title}") },
        text = {
            Column {
                Text("输入完整 Authorization 值，例如 Bearer <token>。凭据会加密保存。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Authorization") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onInstall(token.trim()) }, enabled = token.isNotBlank()) { Text("连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RemoteSkillDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入远程 Skill") },
        text = {
            Column {
                Text("仅允许 HTTPS 的 raw SKILL.md 地址；导入前会限制大小并显示来源。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("SKILL.md URL") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onInstall(url.trim()) }, enabled = url.isNotBlank()) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private suspend fun installMarketSkill(database: AppDatabase, item: MarketSkill) {
    val dao = database.skillDao()
    val existing = dao.getByName(item.name)
    dao.upsert(SkillEntity(
        id = existing?.id ?: 0,
        name = item.name,
        description = item.description,
        content = item.content,
        source = "market",
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    ))
}

private suspend fun fetchAndInstallSkill(database: AppDatabase, url: String): Result<String> = try {
    val uri = URI(url)
    require(uri.scheme == "https") { "扩展源必须使用 HTTPS" }
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val markdown = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        require(response.isSuccessful) { "HTTP ${response.code}" }
        val stream = response.body?.byteStream() ?: error("远程响应为空")
        stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= 256 * 1024) { "SKILL.md 超过 256 KB" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
    require(markdown.isNotBlank()) { "SKILL.md 内容为空" }
    val fallback = uri.path.substringBeforeLast('/').substringAfterLast('/').ifBlank { "remote-skill" }
    val (name, description, content) = SkillImporter.parse(markdown, fallback)
    require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "技能名只能包含字母、数字、._-" }
    val dao = database.skillDao()
    val existing = dao.getByName(name)
    dao.upsert(SkillEntity(
        id = existing?.id ?: 0,
        name = name,
        description = description.take(240),
        content = content,
        source = "market",
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    ))
    Result.success(name)
} catch (error: Exception) {
    Result.failure(error)
}
