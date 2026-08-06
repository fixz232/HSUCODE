package com.hsucode.app

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hsucode.data.AppDatabase
import com.hsucode.data.ProviderConfigEntity
import com.hsucode.provider.OpenAiClient
import com.hsucode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- palette ---
private val Bg: Color @Composable get() = LocalHsuColors.current.bg
private val Ink: Color @Composable get() = LocalHsuColors.current.ink
private val Sub: Color @Composable get() = LocalHsuColors.current.sub
private val Faint: Color @Composable get() = LocalHsuColors.current.faint
private val Green: Color @Composable get() = LocalHsuColors.current.green
private val Red: Color @Composable get() = LocalHsuColors.current.red
private val Border: Color @Composable get() = LocalHsuColors.current.border

private const val TAG = "HsucodeUI"

// ── known supplier catalog (fallback models; live fetch preferred via fetchModels()) ──

// --- supplier catalog ---
private data class Supplier(
    val id: String, val name: String, val baseUrl: String,
    val defaultModel: String, val models: List<String>,
    val apiPathType: String = "openai"
)

/** The market is the single catalog source; configuration only adds the custom endpoint option. */
private val knownSuppliers: List<Supplier> = ProviderPresets.ALL
    .map { Supplier(it.supplierId, it.name, it.baseUrl, it.defaultModel, it.models, it.apiPathType) }
    .distinctBy { it.id }
    .plus(Supplier("custom", "自定义", "", "", emptyList(), apiPathType = "openai"))

// ── SupplierConfigScreen ──
// Model list via fetchModels() only; never from hardcoded list or Room entity.
@Composable
fun SupplierConfigScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    onBack: () -> Unit,
    showHeader: Boolean = true
) {
    val configDao = database.providerConfigDao()
    val scope = rememberCoroutineScope()

    var savedConfigs by remember { mutableStateOf<List<ProviderConfigEntity>>(emptyList()) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var status by remember { mutableStateOf("") }

    // Form state
    var configName by remember { mutableStateOf("") }
    var selectedSupplierId by remember { mutableStateOf("deepseek") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var checkedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }  // multi-select state
    var showSupplierDropdown by remember { mutableStateOf(false) }
    var selectedApiPathType by remember { mutableStateOf("openai") }
    var showApiPathDropdown by remember { mutableStateOf(false) }
    // 能力声明。ToolCall 默认开:老配置迁移后也是 1,不能让人升级完 agent 就不动了。
    var capVision by remember { mutableStateOf(false) }
    var capAudio by remember { mutableStateOf(false) }
    var capVideo by remember { mutableStateOf(false) }
    var capToolCall by remember { mutableStateOf(true) }
    var contextWindowText by remember { mutableStateOf("") }
    var compactThresholdText by remember { mutableStateOf("85") }
    var extraHeadersText by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    /**
     * 手动填写的模型 ID。
     *
     * 不少中转站不提供 /models 列表接口(或返回的列表跟实际可用的对不上),拉取拿不到东西,
     * 配置就彻底走不下去。这里单独存一份手填集合,不跟拉取结果混在一起,原因有二:
     *  1. 再次点「↻ 刷新」会整体覆盖 models,混在一起的话手填的会被冲掉;
     *  2. 编辑已有配置时 models 是空的(等用户现拉),手填的必须仍然看得见。
     */
    var manualModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newModelId by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var pendingActivateId by remember { mutableStateOf<Long?>(null) }  // warn before activating
    var pendingDelete by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var editingApiKey by remember { mutableStateOf("") }  // decrypted stored key for live fetch during edit

    val selectedSupplier = knownSuppliers.find { it.id == selectedSupplierId } ?: knownSuppliers.last()
    val isCustom = selectedSupplierId == "custom"
    val effectiveBaseUrl = if (isCustom) baseUrl else selectedSupplier.baseUrl
    val localNoKeyAllowed = isLocalEndpoint(effectiveBaseUrl)
    // 手填的排在拉取结果前面:刚添加的一眼就能看到,不用在几百个模型里翻。
    val displayModels = (manualModelIds.toList() + models).distinct()

    fun loadConfigs() {
        scope.launch {
            savedConfigs = withContext(Dispatchers.IO) { configDao.getAll() }
            activeId = savedConfigs.firstOrNull { it.isActive }?.id
        }
    }

    fun activateConfig(id: Long) {
        if (id != activeId) {
            pendingActivateId = id  // show warning first
        }
    }

    fun confirmActivate() {
        val id = pendingActivateId ?: return
        pendingActivateId = null
        scope.launch {
            withContext(Dispatchers.IO) { configDao.deactivateAll(); configDao.setActive(id) }
            activeId = id; status = "✓ 已切换配置"
        }
    }

    fun deleteConfig(cfg: ProviderConfigEntity) {
        pendingDelete = cfg
    }

    fun confirmDelete() {
        val cfg = pendingDelete ?: return
        pendingDelete = null
        scope.launch {
            val replacement = withContext(Dispatchers.IO) {
                configDao.delete(cfg)
                configDao.getAll().firstOrNull()
            }
            if (activeId == cfg.id) {
                withContext(Dispatchers.IO) {
                    configDao.deactivateAll()
                    replacement?.let { configDao.setActive(it.id) }
                }
                activeId = replacement?.id
            }
            loadConfigs(); status = if (replacement == null) "✓ 已删除，暂无主模型" else "✓ 已删除，已切换到 ${replacement.name}"
        }
    }

    fun startNew() {
        editingConfig = null; configName = ""; selectedSupplierId = "deepseek"
        baseUrl = ""; apiKey = ""; model = ""; models = emptyList()
        editingApiKey = ""; checkedModelIds = emptySet(); selectedApiPathType = "openai"
        manualModelIds = emptySet(); newModelId = ""
        capVision = false; capAudio = false; capVideo = false; capToolCall = true
        contextWindowText = ""; compactThresholdText = "85"; extraHeadersText = ""
        showForm = true; status = ""
    }

    fun startEdit(cfg: ProviderConfigEntity) {
        editingConfig = cfg; configName = cfg.name; selectedSupplierId = cfg.supplierId
        baseUrl = cfg.baseUrl; apiKey = ""; model = cfg.model
        models = emptyList()  // will be fetched live, same as startNew
        checkedModelIds = cfg.enabledModelIds.toSet()
        // 已保存的模型直接进手填集合:models 此刻是空的(等用户现拉),不这样做的话
        // 手填保存过的模型再进来编辑就整个不见了,只能重填一遍。
        manualModelIds = cfg.enabledModelIds.toSet()
        newModelId = ""
        capVision = cfg.supportsVision; capAudio = cfg.supportsAudio
        capVideo = cfg.supportsVideo; capToolCall = cfg.supportsToolCall
        contextWindowText = if (cfg.contextWindow > 0) cfg.contextWindow.toString() else ""
        compactThresholdText = cfg.autoCompactThresholdPercent.toString()
        extraHeadersText = cfg.extraHeadersJson
        selectedApiPathType = cfg.apiPathType
        // Decrypt stored key so user can refresh models without re-entering
        editingApiKey = try {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        } catch (_: Exception) { "" }
        showForm = true; status = ""
    }

    fun fetchModels() {
        val key = apiKey.ifBlank { editingApiKey }
        if ((key.isBlank() && !localNoKeyAllowed) || effectiveBaseUrl.isBlank()) {
            status = "✗ 需要 api_key 才能拉取模型列表"; return
        }
        modelsLoading = true; status = ""
        scope.launch {
            val r = openAiClient.listModels(effectiveBaseUrl, key)
            models = r.getOrDefault(emptyList())
            modelsLoading = false
            if (models.isEmpty()) status = "✗ 拉取失败或无可用模型，可在下方手动填写模型 ID"
            else { showModelDropdown = true; status = "✓ 已加载 ${models.size} 个模型" }
        }
    }

    /**
     * 手动加一个模型 ID(给不支持 /models 列表接口的中转站用)。
     * 加完直接勾选;若当前还没有活动模型,顺带设为活动,省得用户再点一次。
     */
    fun addManualModel() {
        val id = newModelId.trim()
        if (id.isBlank()) return
        if (id in manualModelIds || id in models) {
            status = "✗ 「$id」已在列表中"; newModelId = ""; return
        }
        manualModelIds = manualModelIds + id
        checkedModelIds = checkedModelIds + id
        if (model.isBlank()) model = id
        newModelId = ""
        status = "✓ 已添加 $id"
    }

    fun saveConfig() {
        val url = effectiveBaseUrl.ifBlank {
            status = "✗ base_url 不能为空"; return
        }
        if (apiKey.isBlank() && editingConfig == null && !localNoKeyAllowed) {
            status = "✗ api_key 不能为空"; return
        }
        if (checkedModelIds.isEmpty()) { status = "✗ 至少勾选一个模型"; return }
        val contextWindowValue = contextWindowText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val compactThresholdValue = compactThresholdText.trim().toIntOrNull()?.coerceIn(50, 100) ?: 85
        if (extraHeadersText.isNotBlank() && runCatching { org.json.JSONObject(extraHeadersText) }.isFailure) {
            status = "✗ 自定义 Header 必须是 JSON 对象"; return
        }
        if (model.isBlank() && checkedModelIds.isNotEmpty()) {
            model = checkedModelIds.first()  // auto-select first if none active
        }
        val name = configName.ifBlank { selectedSupplier.name }
        val keyEnc = if (apiKey.isNotBlank()) {
            Base64.encodeToString(keystore.encrypt(apiKey), Base64.NO_WRAP)
        } else {
            editingConfig?.apiKeyEnc ?: ""
        }
        scope.launch {
            val entity = ProviderConfigEntity(
                id = editingConfig?.id ?: 0,
                name = name, supplierId = selectedSupplierId,
                baseUrl = url, apiKeyEnc = keyEnc, model = model,
                enabledModelIds = checkedModelIds.toList(),
                isActive = editingConfig?.isActive ?: savedConfigs.isEmpty(),
                apiPathType = selectedApiPathType,
                supportsVision = capVision, supportsAudio = capAudio,
                supportsVideo = capVideo, supportsToolCall = capToolCall,
                // 编辑时保留原有的上下文窗口/压缩阈值,别被默认值悄悄清掉
                contextWindow = contextWindowValue,
                autoCompactThresholdPercent = compactThresholdValue,
                extraHeadersJson = extraHeadersText.trim()
            )
            if (editingConfig != null) {
                withContext(Dispatchers.IO) { configDao.update(entity) }
            } else {
                val newId = withContext(Dispatchers.IO) { configDao.insert(entity) }
                if (entity.isActive) {
                    withContext(Dispatchers.IO) { configDao.deactivateAll(); configDao.setActive(newId) }
                    activeId = newId
                }
            }
            showForm = false; loadConfigs()
            status = "✓ 配置已保存"
        }
    }

    LaunchedEffect(Unit) { loadConfigs() }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink) }
                Text("供应商配置", fontSize = 17.sp, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { startNew() }) { Text("新建", color = Green) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { startNew() }) { Text("新建供应商", color = Green) }
            }
        }

        // Config list
        if (savedConfigs.isEmpty()) {
            Text("暂无配置，点「+ 新建」创建", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = Faint, modifier = Modifier.padding(vertical = 24.dp))
        } else {
                savedConfigs.forEach { cfg ->
                    val isActive = cfg.id == activeId
                Row(Modifier.fillMaxWidth()
                    .background(if (isActive) LocalHsuColors.current.activeBg else LocalHsuColors.current.bgElevated)
                    .border(1.dp, if (isActive) Green else Border, RoundedCornerShape(10.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { activateConfig(cfg.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(cfg.supplierId, cfg.name, logoSize = 56.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cfg.name, fontSize = 14.sp,
                            color = if (isActive) Ink else Sub)
                        Text("${knownSuppliers.find{it.id==cfg.supplierId}?.name ?: cfg.supplierId} · ${cfg.model}",
                            fontSize = 11.sp, color = Faint, maxLines = 1)
                    }
                    if (isActive) Icon(Icons.Outlined.CheckCircle, contentDescription = "当前主模型", tint = Green, modifier = Modifier.size(22.dp).padding(end = 2.dp))
                    IconButton(onClick = { startEdit(cfg) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑 ${cfg.name}", tint = Sub)
                    }
                    IconButton(onClick = { deleteConfig(cfg) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除 ${cfg.name}", tint = Red)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            }
        }

        // Form
        if (showForm) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            Spacer(Modifier.height(16.dp))

            Text(if (editingConfig != null) "编辑配置" else "新建配置", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink)
            Spacer(Modifier.height(12.dp))

            Label("名称")
            TextField(value = configName, onValueChange = { configName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                placeholder = { Text("例如: 我的DeepSeek", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
            Spacer(Modifier.height(12.dp))

            // Supplier selector
            Label("供应商")
            Box(Modifier.fillMaxWidth().zIndex(10f)) {
                Row(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(selectedSupplier.id, selectedSupplier.name, logoSize = 44.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedSupplier.name, fontSize = 13.sp, color = Ink,
                        modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            showSupplierDropdown = !showSupplierDropdown
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                    Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                }
                if (showSupplierDropdown) {
                    Column(Modifier.fillMaxWidth().offset(y = 48.dp).background(Bg).border(1.dp, Faint)
                        .padding(vertical = 4.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        knownSuppliers.forEach { sup ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (sup.id == selectedSupplierId) LocalHsuColors.current.activeBg else Bg)
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        selectedSupplierId = sup.id; showSupplierDropdown = false
                                        model = sup.defaultModel; models = emptyList()
                                        selectedApiPathType = sup.apiPathType
                                        if (sup.id != "custom") baseUrl = sup.baseUrl else baseUrl = ""
                                    }.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderLogo(sup.id, sup.name, logoSize = 40.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(sup.name, fontSize = 12.sp,
                                    color = if (sup.id == selectedSupplierId) Ink else Sub,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (effectiveBaseUrl.isNotBlank()) {
                // 预览必须与 OpenAiClient.chatEndpoint 的拼法一致:先剥掉用户可能自带的 /v1,再补全,
                // 否则界面显示的地址和实际请求的地址不一样,排查问题时更误导。
                val shown = if (selectedApiPathType == "custom") {
                    effectiveBaseUrl.trim().trimEnd('/')
                } else {
                    val base = effectiveBaseUrl.trim().trimEnd('/')
                    // 与 OpenAiClient.chatEndpoint 完全同一套规则:base_url 自带版本段(/v1、/v4…)时只接资源路径。
                    val versioned = Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(base)
                    base + when (selectedApiPathType) {
                        "anthropic" -> if (versioned) "/messages" else "/v1/messages"
                        else -> if (versioned) "/chat/completions" else "/v1/chat/completions"
                    }
                }
                Text(shown, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (isCustom) {
                Label("base_url")
                TextField(value = baseUrl, onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                    placeholder = { Text("https://api.xxx.com", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
                Spacer(Modifier.height(12.dp))

                Label("API 路径类型")
                Box(Modifier.fillMaxWidth().zIndex(9f)) {
                    val apiPathLabel = when (selectedApiPathType) {
                        "openai" -> "OpenAI 兼容 (自动追加 /v1/chat/completions)"
                        "anthropic" -> "Anthropic 兼容 (自动追加 /v1/messages)"
                        else -> "自定义 (完整 URL，不追加)"
                    }
                    Row(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(apiPathLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ink,
                            modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showApiPathDropdown = !showApiPathDropdown
                            }.padding(horizontal = 8.dp, vertical = 4.dp))
                        Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (showApiPathDropdown) {
                        Column(Modifier.fillMaxWidth().offset(y = 42.dp).background(Bg).border(1.dp, Faint)
                            .padding(vertical = 4.dp)) {
                            listOf("openai" to "OpenAI 兼容\n自动追加 /v1/chat/completions",
                                   "anthropic" to "Anthropic 兼容\n自动追加 /v1/messages",
                                   "custom" to "自定义\n完整 URL，不追加").forEach { (id, label) ->
                                Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = if (id == selectedApiPathType) Ink else Sub,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (id == selectedApiPathType) LocalHsuColors.current.activeBg else Bg)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            selectedApiPathType = id; showApiPathDropdown = false
                                        }.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Label(if (localNoKeyAllowed) "api_key（本地服务可留空）" else "api_key")
            TextField(value = apiKey, onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(if (localNoKeyAllowed) "本地 Ollama / LM Studio 通常无需 Key" else if (editingConfig != null && apiKey.isEmpty()) "留空则保留原 Key" else "sk-...", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
            Spacer(Modifier.height(12.dp))

            Label("启用模型（多选）")
            // 手填入口:中转站不给 /models 时的唯一出路,所以放在列表【上方】常驻,
            // 而不是藏在「拉取失败」之后才出现——拉取成功但列表不全的情况同样需要它。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = newModelId,
                    onValueChange = { newModelId = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = fieldColors(),
                    textStyle = fieldTextStyle(),
                    placeholder = {
                        Text("拉不到列表？直接填模型 ID，如 gpt-4o", color = Faint,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addManualModel() })
                )
                Text("+ 添加", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = if (newModelId.isBlank()) Faint else Green,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { addManualModel() }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (modelsLoading) {
                        Text("加载中…", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Faint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    } else if (displayModels.isEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("点 ↻ 拉取，或在上方直接填模型 ID", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Faint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            Text("↻", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    } else {
                        // Refresh header
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("↻ 刷新", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() })
                            Text(if (model.isNotEmpty()) "当前: $model" else "未选", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                        // Checkbox list
                        displayModels.forEach { m ->
                            val isChecked = m in checkedModelIds
                            val isManual = m in manualModelIds
                            // 只有「确实拉到过列表」且该模型不在其中、又不是手填的,才算供应商已下线。
                            // 手填的模型本来就不在拉取结果里,不能因此标红。
                            val isUnavailable = models.isNotEmpty() && !isManual && m !in models
                            Row(Modifier.fillMaxWidth()
                                .background(if (m == model) LocalHsuColors.current.activeBg else Bg)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    val newSet = if (isChecked) checkedModelIds - m else checkedModelIds + m
                                    if (newSet.isEmpty()) { status = "✗ 至少保留一个启用模型"; return@clickable }
                                    checkedModelIds = newSet
                                    if (m == model && !isChecked) model = newSet.first()
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                // Checkbox indicator
                                Text(if (isChecked) "☑" else "☐", fontSize = 13.sp, color = if (isChecked) Green else Faint,
                                    modifier = Modifier.padding(end = 8.dp))
                                // Model name
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(m, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                            color = if (m == model) Ink else Sub)
                                        // 标出哪些是自己填的,便于跟拉取来的区分(手填写错了才好找回来改)
                                        if (isManual) {
                                            Text("手填", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Faint,
                                                modifier = Modifier.padding(start = 6.dp))
                                        }
                                    }
                                    if (isUnavailable) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.ErrorOutline,
                                                contentDescription = "不可用",
                                                modifier = Modifier.size(12.dp).padding(end = 2.dp),
                                                tint = Red
                                            )
                                            Text("该模型已不可用，保存前请确认是否移除", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Red)
                                        }
                                    }
                                }
                                // Active marker
                                if (m == model) Text("←", fontSize = 10.sp, color = Sub, modifier = Modifier.padding(start = 4.dp))
                                // 手填的可以删掉(拉取来的不给删,刷新一下就回来了,给了反而误导)
                                if (isManual) {
                                    Text("✕", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Faint,
                                        modifier = Modifier
                                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                manualModelIds = manualModelIds - m
                                                val remaining = checkedModelIds - m
                                                checkedModelIds = remaining
                                                // 删掉的正好是活动模型 → 顺延到还勾着的第一个,别留空
                                                if (model == m) model = remaining.firstOrNull().orEmpty()
                                                status = "✓ 已移除 $m"
                                            }
                                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Label("高级连接设置")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = contextWindowText, onValueChange = { contextWindowText = it }, modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("上下文 tokens", fontSize = 11.sp) }, colors = fieldColors(), textStyle = fieldTextStyle(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                TextField(
                    value = compactThresholdText, onValueChange = { compactThresholdText = it }, modifier = Modifier.width(150.dp), singleLine = true,
                    label = { Text("压缩阈值 %", fontSize = 11.sp) }, colors = fieldColors(), textStyle = fieldTextStyle(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = extraHeadersText, onValueChange = { extraHeadersText = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 5, label = { Text("自定义请求 Header（JSON，可选）", fontSize = 11.sp) },
                placeholder = { Text("例如 {\"X-Title\":\"HSUCODE\"}", fontSize = 11.sp, color = Faint) },
                colors = fieldColors(), textStyle = fieldTextStyle()
            )
            Text("Header 会原样发送，保存前会校验 JSON；不要把密钥写入这里。", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(16.dp))

            // ---- 能力声明 ----
            Label("模型能力")
            CapabilityRow(
                "模型支持识图", "启用后图片直接发给该模型;关闭则交由 describe_image 转给视觉副模型",
                capVision
            ) { capVision = it }
            CapabilityRow(
                "模型支持音频解析", "启用后音频直接发给该模型;关闭则走 transcribe_audio 转写",
                capAudio
            ) { capAudio = it }
            CapabilityRow(
                "模型支持视频解析", "声明用,当前暂无视频直传链路,勾选仅用于功能分配时的匹配提示",
                capVideo
            ) { capVideo = it }
            CapabilityRow(
                "模型支持 ToolCall",
                if (capToolCall) "使用 API 原生工具调用接口(需要模型支持)"
                else "关闭后不会发送 tools 字段,该模型将无法调用任何工具,只能纯聊天",
                capToolCall
            ) { capToolCall = it }
            if (!capToolCall) {
                Text(
                    "注意:关闭 ToolCall 后这套配置只能对话,不能执行工具。",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Red,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Sub,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showForm = false }.padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.width(12.dp))
                Text("保存配置", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { saveConfig() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(64.dp))  // 底部留白:配合整页可滚动,保存按钮完整露出、不贴屏幕/导航栏底边
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (status.contains("✓")) Green else if (status.contains("✗")) Red else Sub)
        }

        // ---- model switch warning dialog ----
        if (pendingActivateId != null) {
            val targetCfg = savedConfigs.find { it.id == pendingActivateId }
            AlertDialog(
                onDismissRequest = { pendingActivateId = null },
                title = { Text("切换模型", fontFamily = FontFamily.Monospace, color = Ink) },
                text = {
                    Text(
                        "将切换到「${targetCfg?.name ?: "新配置"}」(${targetCfg?.model ?: "?"})。\n\n" +
                        "新模型可能无法解析当前对话中的历史工具调用记录，强烈建议开启新会话后再切换。\n\n" +
                        "是否继续切换？",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmActivate() }) {
                        Text("切换", fontFamily = FontFamily.Monospace, color = Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingActivateId = null }) {
                        Text("取消", fontFamily = FontFamily.Monospace, color = Sub)
                    }
                },
                containerColor = Bg
            )
        }
        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除供应商配置", color = Ink) },
                text = { Text("确定删除「${target.name}」吗？其 API Key 和模型列表会从本机配置中移除。已有会话不会被删除。", color = Ink, lineHeight = 18.sp) },
                confirmButton = { TextButton(onClick = { confirmDelete() }) { Text("删除", color = Red) } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消", color = Sub) } },
                containerColor = Bg
            )
        }
    }
}

/** 能力开关行:左侧标题+说明,右侧开关。说明文字随开关状态变化,让后果当场可见。 */
@Composable
private fun CapabilityRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ink)
            Text(hint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint,
                lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Bg,
                checkedTrackColor = Green,
                uncheckedThumbColor = Sub,
                uncheckedTrackColor = Bg,
                uncheckedBorderColor = Faint
            )
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Sub, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Bg, unfocusedContainerColor = Bg,
    focusedIndicatorColor = Ink, unfocusedIndicatorColor = Faint,
    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
)

@Composable
private fun fieldTextStyle() = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)

private fun isLocalEndpoint(url: String): Boolean {
    val value = url.trim().lowercase()
    return value.startsWith("http://localhost") || value.startsWith("http://127.") ||
        value.startsWith("http://10.") || value.startsWith("http://192.168.") ||
        Regex("^http://172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(value)
}
