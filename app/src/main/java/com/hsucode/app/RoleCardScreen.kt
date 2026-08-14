package com.hsucode.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.data.IdentityEntity
import com.hsucode.data.McpServerEntity
import com.hsucode.data.ProviderConfigEntity
import com.hsucode.data.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val ROLE_CAPABILITY_PREFIX = "identity_capability_"

/**
 * Extra role bindings deliberately live in settings rather than IdentityEntity.
 * This keeps the capability centre backward compatible with existing Room databases.
 */
private data class RoleCapabilityConfig(
    val memoryScope: String = MEMORY_SESSION,
    val memoryTags: String = "",
    val skillIds: Set<Long> = emptySet(),
    val mcpServerIds: Set<Long> = emptySet()
) {
    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("memoryScope", memoryScope)
        put("memoryTags", memoryTags.trim())
        put("skillIds", JSONArray(skillIds.sorted()))
        put("mcpServerIds", JSONArray(mcpServerIds.sorted()))
    }.toString()

    companion object {
        const val MEMORY_SESSION = "session"
        const val MEMORY_ROLE = "role"
        const val MEMORY_SHARED = "shared"

        fun fromJson(raw: String?): RoleCapabilityConfig {
            if (raw.isNullOrBlank()) return RoleCapabilityConfig()
            return runCatching {
                val json = JSONObject(raw)
                RoleCapabilityConfig(
                    memoryScope = json.optString("memoryScope", MEMORY_SESSION)
                        .takeIf { it in setOf(MEMORY_SESSION, MEMORY_ROLE, MEMORY_SHARED) }
                        ?: MEMORY_SESSION,
                    memoryTags = json.optString("memoryTags").trim(),
                    skillIds = json.optLongSet("skillIds"),
                    mcpServerIds = json.optLongSet("mcpServerIds")
                )
            }.getOrDefault(RoleCapabilityConfig())
        }
    }
}

private data class RoleCapabilityResources(
    val providers: List<ProviderConfigEntity> = emptyList(),
    val skills: List<SkillEntity> = emptyList(),
    val mcpServers: List<McpServerEntity> = emptyList()
)

private data class RoleCapabilitySnapshot(
    val cards: List<IdentityEntity>,
    val resources: RoleCapabilityResources,
    val capabilities: Map<Long, RoleCapabilityConfig>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleCardScreen(database: AppDatabase, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<IdentityEntity>>(emptyList()) }
    var resources by remember { mutableStateOf(RoleCapabilityResources()) }
    var capabilities by remember { mutableStateOf<Map<Long, RoleCapabilityConfig>>(emptyMap()) }
    var notice by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<IdentityEntity?>(null) }
    var expandedCardId by remember { mutableStateOf<Long?>(null) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val loadedCards = database.identityDao().getAll()
                val loadedResources = RoleCapabilityResources(
                    providers = database.providerConfigDao().getAll(),
                    skills = database.skillDao().getAll(),
                    mcpServers = database.mcpServerDao().getAll()
                )
                RoleCapabilitySnapshot(
                    cards = loadedCards,
                    resources = loadedResources,
                    capabilities = loadedCards.associate { card ->
                        card.id to RoleCapabilityConfig.fromJson(
                            database.settingDao().get("$ROLE_CAPABILITY_PREFIX${card.id}")
                        )
                    }
                )
            }
            cards = snapshot.cards
            resources = snapshot.resources
            capabilities = snapshot.capabilities
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取角色卡")
                    val baseCard = RoleCardCodec.decode(json).getOrThrow()
                    val imported = decodeCapabilityExtension(
                        json = json,
                        card = baseCard,
                        resources = RoleCapabilityResources(
                            providers = database.providerConfigDao().getAll(),
                            skills = database.skillDao().getAll(),
                            mcpServers = database.mcpServerDao().getAll()
                        )
                    )
                    val id = database.identityDao().insert(imported.card)
                    imported.capability?.let { database.settingDao().put("$ROLE_CAPABILITY_PREFIX$id", it.toJson()) }
                    imported.card.name
                }
            }
            notice = result.fold({ "已导入角色卡：$it" }, { "导入失败：${it.message}" })
            refresh()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val card = pendingExport
        pendingExport = null
        if (uri == null || card == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = capabilities[card.id] ?: RoleCapabilityConfig()
                    val json = encodeRoleCardWithCapabilities(card, config, resources)
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: error("无法写入文件")
                }
            }
            notice = result.fold({ "已导出 ${card.name}" }, { "导出失败：${it.message}" })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("角色能力中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json")) }) {
                        Icon(Icons.Outlined.FileUpload, "导入 JSON 角色卡")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
                    Text("为角色绑定模型、工具、记忆、Skills 与 MCP", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "基础角色卡继续兼容 Tavern JSON；HSUCODE 的能力绑定会随导出保存在扩展字段中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (notice.isNotBlank()) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            if (cards.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(
                            "还没有角色卡。可从右上角导入，或先在身份卡页面创建角色。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            items(cards, key = { it.id }) { card ->
                val capability = capabilities[card.id] ?: RoleCapabilityConfig()
                RoleCapabilityCard(
                    card = card,
                    capability = capability,
                    resources = resources,
                    expanded = expandedCardId == card.id,
                    onToggleExpanded = {
                        expandedCardId = if (expandedCardId == card.id) null else card.id
                    },
                    onExport = {
                        pendingExport = card
                        exportLauncher.launch("${card.name}.json")
                    },
                    onSave = { updatedCard, updatedCapability ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    database.identityDao().update(updatedCard)
                                    database.settingDao().put(
                                        "$ROLE_CAPABILITY_PREFIX${updatedCard.id}",
                                        updatedCapability.toJson()
                                    )
                                }
                            }
                            notice = result.fold(
                                { "已保存 ${updatedCard.name} 的能力配置" },
                                { "保存失败：${it.message}" }
                            )
                            if (result.isSuccess) refresh()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RoleCapabilityCard(
    card: IdentityEntity,
    capability: RoleCapabilityConfig,
    resources: RoleCapabilityResources,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onExport: () -> Unit,
    onSave: (IdentityEntity, RoleCapabilityConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        card.description.ifBlank { card.systemPrompt }.ifBlank { "未填写角色说明" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.Tune,
                        if (expanded) "收起能力配置" else "配置角色能力"
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Outlined.FileDownload, "导出角色卡")
                }
            }
            Text(
                roleCapabilitySummary(card, capability, resources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (expanded) {
                RoleCapabilityEditor(
                    card = card,
                    initialCapability = capability,
                    resources = resources,
                    onSave = onSave
                )
            }
        }
    }
}

@Composable
private fun RoleCapabilityEditor(
    card: IdentityEntity,
    initialCapability: RoleCapabilityConfig,
    resources: RoleCapabilityResources,
    onSave: (IdentityEntity, RoleCapabilityConfig) -> Unit
) {
    var providerConfigId by remember(card.id) { mutableStateOf(card.providerConfigId) }
    var modelOverride by remember(card.id) { mutableStateOf(card.modelOverride) }
    var allowedTools by remember(card.id) { mutableStateOf(card.allowedTools) }
    var memoryScope by remember(card.id) { mutableStateOf(initialCapability.memoryScope) }
    var memoryTags by remember(card.id) { mutableStateOf(initialCapability.memoryTags) }
    var skillIds by remember(card.id) { mutableStateOf(initialCapability.skillIds) }
    var mcpServerIds by remember(card.id) { mutableStateOf(initialCapability.mcpServerIds) }
    var providerMenuVisible by remember(card.id) { mutableStateOf(false) }
    var modelMenuVisible by remember(card.id) { mutableStateOf(false) }
    val selectedProvider = resources.providers.firstOrNull { it.id == providerConfigId }
    val modelChoices = selectedProvider?.enabledModelIds.orEmpty().ifEmpty {
        selectedProvider?.model?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }.distinct()

    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CapabilityHeading("模型绑定", "角色可跟随当前模型，也可锁定一个供应商与模型。")
        Box {
            OutlinedButton(
                onClick = { providerMenuVisible = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(
                    selectedProvider?.name ?: "跟随当前活跃供应商",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded = providerMenuVisible, onDismissRequest = { providerMenuVisible = false }) {
                DropdownMenuItem(
                    text = { Text("跟随当前活跃供应商") },
                    onClick = { providerConfigId = 0L; modelOverride = ""; providerMenuVisible = false }
                )
                resources.providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            providerConfigId = provider.id
                            modelOverride = provider.model
                            providerMenuVisible = false
                        }
                    )
                }
            }
        }
        Box {
            OutlinedTextField(
                value = modelOverride,
                onValueChange = { modelOverride = it },
                label = { Text("模型 ID（留空使用供应商默认模型）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = if (modelChoices.isNotEmpty()) {
                    {
                        IconButton(onClick = { modelMenuVisible = true }) {
                            Icon(Icons.Outlined.ExpandMore, "从启用模型中选择")
                        }
                    }
                } else null
            )
            DropdownMenu(expanded = modelMenuVisible, onDismissRequest = { modelMenuVisible = false }) {
                modelChoices.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { modelOverride = model; modelMenuVisible = false }
                    )
                }
            }
        }

        CapabilityHeading("工具与权限", "留空表示不限制；填写后，此角色只声明使用列出的工具。")
        OutlinedTextField(
            value = allowedTools,
            onValueChange = { allowedTools = it },
            label = { Text("允许工具（逗号分隔）") },
            placeholder = { Text("file_read,file_write,web_search") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 2
        )

        CapabilityHeading("记忆范围", "选择角色在任务中请求的记忆空间，并给检索补充标签。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MemoryScopeButton("会话", memoryScope == RoleCapabilityConfig.MEMORY_SESSION, Modifier.weight(1f)) {
                memoryScope = RoleCapabilityConfig.MEMORY_SESSION
            }
            MemoryScopeButton("角色", memoryScope == RoleCapabilityConfig.MEMORY_ROLE, Modifier.weight(1f)) {
                memoryScope = RoleCapabilityConfig.MEMORY_ROLE
            }
            MemoryScopeButton("共享", memoryScope == RoleCapabilityConfig.MEMORY_SHARED, Modifier.weight(1f)) {
                memoryScope = RoleCapabilityConfig.MEMORY_SHARED
            }
        }
        OutlinedTextField(
            value = memoryTags,
            onValueChange = { memoryTags = it },
            label = { Text("记忆标签（逗号分隔）") },
            placeholder = { Text("项目,Android,发布") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        CapabilityHeading("Skill 绑定", "仅作为该角色的可用 Skill 清单。")
        BindingChecklist(
            entries = resources.skills,
            selectedIds = skillIds,
            emptyText = "尚未安装 Skill，可在扩展市场添加。",
            label = { it.name },
            detail = { it.description },
            onToggle = { id -> skillIds = skillIds.toggle(id) }
        )

        CapabilityHeading("MCP 绑定", "仅作为该角色可调用的 MCP 服务清单。")
        BindingChecklist(
            entries = resources.mcpServers,
            selectedIds = mcpServerIds,
            emptyText = "尚未配置 MCP 服务，可在扩展市场添加。",
            label = { it.name },
            detail = { if (it.connected) "已连接" else "未连接" },
            onToggle = { id -> mcpServerIds = mcpServerIds.toggle(id) }
        )

        Button(
            onClick = {
                onSave(
                    card.copy(
                        providerConfigId = providerConfigId,
                        modelOverride = modelOverride.trim(),
                        allowedTools = allowedTools.trim()
                    ),
                    RoleCapabilityConfig(
                        memoryScope = memoryScope,
                        memoryTags = memoryTags.trim(),
                        skillIds = skillIds,
                        mcpServerIds = mcpServerIds
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text("保存能力配置")
        }
    }
}

@Composable
private fun CapabilityHeading(title: String, supporting: String) {
    Column(Modifier.padding(top = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun MemoryScopeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
private fun <T : Any> BindingChecklist(
    entries: List<T>,
    selectedIds: Set<Long>,
    emptyText: String,
    label: (T) -> String,
    detail: (T) -> String,
    onToggle: (Long) -> Unit
) {
    if (entries.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    entries.forEach { entry ->
        val id = when (entry) {
            is SkillEntity -> entry.id
            is McpServerEntity -> entry.id
            else -> return@forEach
        }
        val selected = id in selectedIds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { role = Role.Checkbox },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle(id) })
            Column(Modifier.padding(start = 6.dp).weight(1f)) {
                Text(label(entry), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val secondary = detail(entry)
                if (secondary.isNotBlank()) {
                    Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun roleCapabilitySummary(
    card: IdentityEntity,
    capability: RoleCapabilityConfig,
    resources: RoleCapabilityResources
): String {
    val model = resources.providers.firstOrNull { it.id == card.providerConfigId }?.let { provider ->
        "${provider.name} / ${card.modelOverride.ifBlank { provider.model }}"
    } ?: "跟随当前模型"
    val tools = card.allowedTools.split(',').map { it.trim() }.filter { it.isNotBlank() }
    val memory = when (capability.memoryScope) {
        RoleCapabilityConfig.MEMORY_ROLE -> "角色记忆"
        RoleCapabilityConfig.MEMORY_SHARED -> "共享记忆"
        else -> "会话记忆"
    }
    val parts = buildList {
        add(model)
        add(if (tools.isEmpty()) "工具未限制" else "${tools.size} 项工具")
        if (capability.skillIds.isNotEmpty()) add("${capability.skillIds.size} 个 Skill")
        if (capability.mcpServerIds.isNotEmpty()) add("${capability.mcpServerIds.size} 个 MCP")
        add(memory)
    }
    return parts.joinToString("  ·  ")
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun JSONObject.optLongSet(name: String): Set<Long> {
    val array = optJSONArray(name) ?: return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optLong(index, 0L).takeIf { it > 0 }?.let(::add)
        }
    }
}

private data class ImportedRoleCapability(
    val card: IdentityEntity,
    val capability: RoleCapabilityConfig?
)

private fun encodeRoleCardWithCapabilities(
    card: IdentityEntity,
    capability: RoleCapabilityConfig,
    resources: RoleCapabilityResources
): String {
    val root = JSONObject(RoleCardCodec.encode(card))
    val data = root.optJSONObject("data") ?: JSONObject().also { root.put("data", it) }
    val extensions = data.optJSONObject("extensions") ?: JSONObject().also { data.put("extensions", it) }
    val provider = resources.providers.firstOrNull { it.id == card.providerConfigId }
    extensions.put("hsucode_capability", JSONObject().apply {
        put("version", 1)
        put("providerName", provider?.name.orEmpty())
        put("modelOverride", card.modelOverride)
        put("allowedTools", card.allowedTools)
        put("memoryScope", capability.memoryScope)
        put("memoryTags", capability.memoryTags)
        put("skillNames", JSONArray(resources.skills.filter { it.id in capability.skillIds }.map { it.name }))
        put("mcpServerNames", JSONArray(resources.mcpServers.filter { it.id in capability.mcpServerIds }.map { it.name }))
    })
    return root.toString(2)
}

private fun decodeCapabilityExtension(
    json: String,
    card: IdentityEntity,
    resources: RoleCapabilityResources
): ImportedRoleCapability {
    return runCatching {
        val data = JSONObject(json).optJSONObject("data") ?: JSONObject(json)
        val extension = data.optJSONObject("extensions")?.optJSONObject("hsucode_capability")
            ?: return ImportedRoleCapability(card, null)
        val providerName = extension.optString("providerName").trim()
        val provider = resources.providers.firstOrNull { it.name.equals(providerName, ignoreCase = true) }
        val skillNames = extension.optStringSet("skillNames")
        val mcpNames = extension.optStringSet("mcpServerNames")
        ImportedRoleCapability(
            card = card.copy(
                providerConfigId = provider?.id ?: 0L,
                modelOverride = extension.optString("modelOverride").trim(),
                allowedTools = extension.optString("allowedTools").trim()
            ),
            capability = RoleCapabilityConfig(
                memoryScope = extension.optString("memoryScope", RoleCapabilityConfig.MEMORY_SESSION)
                    .takeIf { it in setOf(RoleCapabilityConfig.MEMORY_SESSION, RoleCapabilityConfig.MEMORY_ROLE, RoleCapabilityConfig.MEMORY_SHARED) }
                    ?: RoleCapabilityConfig.MEMORY_SESSION,
                memoryTags = extension.optString("memoryTags").trim(),
                skillIds = resources.skills.filter { it.name in skillNames }.mapTo(linkedSetOf()) { it.id },
                mcpServerIds = resources.mcpServers.filter { it.name in mcpNames }.mapTo(linkedSetOf()) { it.id }
            )
        )
    }.getOrDefault(ImportedRoleCapability(card, null))
}

private fun JSONObject.optStringSet(name: String): Set<String> {
    val array = optJSONArray(name) ?: return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
