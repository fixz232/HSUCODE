package com.hsucode.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.data.AppDatabase
import com.hsucode.data.ProviderConfigEntity
import com.hsucode.provider.OpenAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RouteRole(val label: String, val hint: String) {
    PRIMARY("主模型", "日常对话和未单独分配的任务"),
    FALLBACK("失败备用", "限流、5xx 或模型不存在时重试"),
    LOW_COST("低成本总结", "上下文压缩、后台复盘和轻量判断"),
    LONG_CONTEXT("长上下文", "长文档、子智能体和复杂上下文"),
    VISION("视觉模型", "检测到图片或视觉能力不匹配时使用")
}

@Composable
fun ModelHealthScreen(
    database: AppDatabase,
    openAiClient: OpenAiClient,
    onBack: () -> Unit,
    showHeader: Boolean = true
) {
    val xc = LocalHsuColors.current
    val context = LocalContext.current
    val app = context.applicationContext as? HsucodeApplication
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf<List<ProviderConfigEntity>>(emptyList()) }
    val health = remember { mutableStateMapOf<Long, ProviderHealth>() }
    var route by remember { mutableStateOf(ModelRouteRule()) }
    var testingId by remember { mutableStateOf<Long?>(null) }
    var probingId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("") }
    var pickerRole by remember { mutableStateOf<RouteRole?>(null) }
    var localUrl by remember { mutableStateOf("http://192.168.1.100:11434/v1") }
    var localMessage by remember { mutableStateOf("") }
    var usageSummary by remember { mutableStateOf(longArrayOf(0, 0, 0, 0)) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                configs = database.providerConfigDao().getAll()
                route = ModelRoutingStore.get(database)
                configs.forEach { health[it.id] = ProviderHealthStore.get(database, it.id) }
                val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                usageSummary = longArrayOf(
                    database.usageRecordDao().callCountSince(since),
                    database.usageRecordDao().totalInputSince(since) ?: 0,
                    database.usageRecordDao().totalOutputSince(since) ?: 0,
                    database.usageRecordDao().totalCacheReadSince(since) ?: 0
                )
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun test(config: ProviderConfigEntity) {
        if (testingId != null) return
        testingId = config.id
        scope.launch {
            val result = openAiClient.testConfig(config)
            val balance = openAiClient.fetchBalance(config).getOrNull()
            val value = ProviderHealth(
                testedAt = System.currentTimeMillis(), latencyMs = result.latencyMs,
                modelListStatus = result.modelListStatus, protocol = when (config.apiPathType) {
                    "anthropic" -> "Anthropic"; "responses" -> "OpenAI Responses"; else -> "OpenAI 兼容"
                }, ok = result.ok, statusCode = result.statusCode, message = result.message,
                balanceAmount = balance?.amount, balanceCurrency = balance?.currency.orEmpty(),
                balanceCheckedAt = balance?.checkedAt ?: 0L
            )
            withContext(Dispatchers.IO) { ProviderHealthStore.put(database, config.id, value) }
            health[config.id] = value
            message = if (result.ok) "${config.name} 测试通过" else "${config.name} 测试失败"
            testingId = null
        }
    }

    fun saveRoute(updated: ModelRouteRule) {
        route = updated
        scope.launch {
            val visibleModel = withContext(Dispatchers.IO) {
                ModelRoutingStore.put(database, updated)
                val config = if (updated.primaryConfigId > 0) database.providerConfigDao().getById(updated.primaryConfigId)
                    else database.providerConfigDao().getActive()
                updated.primaryModel.ifBlank { config?.model.orEmpty() }
            }
            app?.updateCurrentModelLabel(visibleModel)
            message = "路由规则已保存"
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { exportConfig(context, database, uri).also { message = it } }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = importConfig(context, database, uri); reload() }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        if (showHeader) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink) }
            Text("模型中心 · 用量与健康", fontSize = 17.sp, color = xc.ink, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("近 30 天用量", fontSize = 14.sp, color = xc.ink)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("调用", usageSummary[0].toString(), xc)
                    UsageMetric("输入 Token", compactNumber(usageSummary[1]), xc)
                    UsageMetric("输出 Token", compactNumber(usageSummary[2]), xc)
                    UsageMetric("缓存读取", compactNumber(usageSummary[3]), xc)
                }
            }
            item {
                Text("连接诊断", fontSize = 14.sp, color = xc.ink)
                Text("测试会发送真实的最小请求，并记录延迟、协议、鉴权原因和模型列表状态。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = if (message.contains("失败")) xc.red else xc.green, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (configs.isEmpty()) item { EmptyHealthState(xc) }
            items(configs, key = { it.id }) { config ->
                val itemHealth = health[config.id] ?: ProviderHealth()
                ProviderHealthCard(config, itemHealth, testingId == config.id, probingId == config.id, xc,
                    onTest = { test(config) }, onProbe = {
                        if (probingId == null) {
                            probingId = config.id
                            scope.launch {
                                try {
                                    val result = openAiClient.probeModel(config).getOrNull()
                                    if (result != null) {
                                        withContext(Dispatchers.IO) {
                                            ModelMetadataStore.upsert(database, ModelMetadata(
                                                configId = config.id, modelId = config.model,
                                                supportsToolCall = result.toolCall, supportsVision = result.vision,
                                                supportsAudio = result.audio, supportsReasoning = result.reasoning,
                                                lastProbedAt = System.currentTimeMillis(), capabilitySource = result.details,
                                                status = "已探测"
                                            ))
                                        }
                                        message = "${config.name} 能力探测完成：ToolCall=${result.toolCall}，视觉=${result.vision}"
                                    } else message = "${config.name} 能力探测失败"
                                } catch (error: Exception) {
                                    message = "${config.name} 能力探测失败：${error.message?.take(100).orEmpty()}"
                                } finally {
                                    probingId = null
                                }
                            }
                        }
                    })
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("智能路由", fontSize = 14.sp, color = xc.ink)
                Text("只按明确失败条件切换，不会悄悄改变已保存的主配置。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                RouteEditor(route, configs, xc, onPick = { pickerRole = it }, onChange = ::saveRoute)
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("本地模型助手", fontSize = 14.sp, color = xc.ink)
                Text("手机上的 localhost 指向手机本身；请填写电脑在同一局域网的地址。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(localUrl, { localUrl = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Ollama / LM Studio 地址") })
                    IconButton(onClick = {
                        scope.launch {
                            val result = openAiClient.listModels(localUrl.trim(), "")
                            localMessage = result.fold({ "✓ 发现 ${it.size} 个模型" }, { "✗ ${it.message?.take(120).orEmpty()}" })
                        }
                    }) { Icon(Icons.Outlined.Refresh, contentDescription = "检测地址", tint = xc.green) }
                }
                if (localMessage.isNotBlank()) Text(localMessage, fontSize = 11.sp, color = if (localMessage.startsWith("✓")) xc.green else xc.red, modifier = Modifier.padding(top = 5.dp))
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("配置文件", fontSize = 14.sp, color = xc.ink)
                Text("只导出供应商、端点、模型、能力和路由，不包含 API Key。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { createLauncher.launch("HSUCODE-model-config.json") }) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(4.dp)); Text("导出非敏感配置") }
                    TextButton(onClick = { openLauncher.launch(arrayOf("application/json", "text/plain")) }) { Icon(Icons.Outlined.Upload, null); Spacer(Modifier.width(4.dp)); Text("导入配置") }
                }
            }
        }
    }

    pickerRole?.let { role ->
        RoutePickerDialog(role, configs, route, xc, onDismiss = { pickerRole = null }, onSelect = { updated ->
            pickerRole = null
            saveRoute(updated)
        })
    }
}

@Composable
private fun ProviderHealthCard(config: ProviderConfigEntity, health: ProviderHealth, testing: Boolean, probing: Boolean, xc: HsuColors, onTest: () -> Unit, onProbe: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (health.ok) Icons.Outlined.CheckCircle else if (health.testedAt > 0) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudOff,
                contentDescription = null, tint = if (health.ok) xc.green else if (health.testedAt > 0) xc.red else xc.faint)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(config.name, fontSize = 13.sp, color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${config.model} · ${health.protocol}", fontSize = 10.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onTest, enabled = !testing && !probing) { Text(if (testing) "测试中…" else "测试", color = if (testing) xc.faint else xc.green) }
        }
        if (health.testedAt > 0) {
            Text("${if (health.ok) "连接正常" else "连接失败"} · ${health.latencyMs}ms · ${health.message}", fontSize = 10.sp, color = if (health.ok) xc.green else xc.red, modifier = Modifier.padding(top = 6.dp))
            Text("模型列表：${health.modelListStatus} · ${formatTime(health.testedAt)}", fontSize = 10.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp))
            health.balanceAmount?.let { Text("余额：${"%.4f".format(Locale.US, it)} ${health.balanceCurrency}" + if (health.balanceCheckedAt > 0) " · ${formatTime(health.balanceCheckedAt)}" else "", fontSize = 10.sp, color = xc.sub, modifier = Modifier.padding(top = 3.dp)) }
        } else Text("尚未测试", fontSize = 10.sp, color = xc.faint, modifier = Modifier.padding(top = 6.dp))
        TextButton(onClick = onProbe, enabled = !testing && !probing, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Outlined.Refresh, contentDescription = "真实探测模型能力", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text(if (probing) "探测中…" else "真实探测能力")
        }
    }
}

@Composable
private fun RouteEditor(rule: ModelRouteRule, configs: List<ProviderConfigEntity>, xc: HsuColors, onPick: (RouteRole) -> Unit, onChange: (ModelRouteRule) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(12.dp)) {
        RouteRole.values().forEach { role ->
            val (id, model) = routeValue(rule, role)
            val label = configs.firstOrNull { it.id == id }?.name?.let { "$it / ${model.ifBlank { configs.firstOrNull { c -> c.id == id }?.model.orEmpty() }}" } ?: "跟随当前主模型"
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(role) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(role.label, fontSize = 12.sp, color = xc.ink); Text(role.hint, fontSize = 10.sp, color = xc.sub) }
                Text(label, fontSize = 10.sp, color = if (id > 0) xc.green else xc.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("失败条件", fontSize = 10.sp, color = xc.sub, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        RouteSwitch("限流 / 超时", rule.retryOnRateLimit) { onChange(rule.copy(retryOnRateLimit = it)) }
        RouteSwitch("服务器 5xx", rule.retryOnServerError) { onChange(rule.copy(retryOnServerError = it)) }
        RouteSwitch("模型不存在", rule.retryOnModelUnavailable) { onChange(rule.copy(retryOnModelUnavailable = it)) }
        RouteSwitch("能力不匹配", rule.retryOnCapabilityMismatch) { onChange(rule.copy(retryOnCapabilityMismatch = it)) }
    }
}

@Composable
private fun RouteSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RoutePickerDialog(role: RouteRole, configs: List<ProviderConfigEntity>, rule: ModelRouteRule, xc: HsuColors, onDismiss: () -> Unit, onSelect: (ModelRouteRule) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = xc.bg,
        title = { Text("选择${role.label}", color = xc.ink) },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                Text("选择“跟随当前主模型”可清除此角色。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(bottom = 6.dp))
                Text("跟随当前主模型", fontSize = 12.sp, color = xc.ink, modifier = Modifier.fillMaxWidth().clickable { onSelect(routeCopy(rule, role, 0L, "")) }.padding(vertical = 10.dp))
                configs.forEach { config ->
                    val models = (config.enabledModelIds + config.model).filter { it.isNotBlank() }.distinct()
                    Text(config.name, fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 6.dp))
                    models.forEach { model ->
                        Text(model, fontSize = 12.sp, color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(routeCopy(rule, role, config.id, model)) }.padding(vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = xc.sub) } })
}

private fun routeValue(rule: ModelRouteRule, role: RouteRole): Pair<Long, String> = when (role) {
    RouteRole.PRIMARY -> rule.primaryConfigId to rule.primaryModel
    RouteRole.FALLBACK -> rule.fallbackConfigId to rule.fallbackModel
    RouteRole.LOW_COST -> rule.lowCostConfigId to rule.lowCostModel
    RouteRole.LONG_CONTEXT -> rule.longContextConfigId to rule.longContextModel
    RouteRole.VISION -> rule.visionConfigId to rule.visionModel
}

private fun routeCopy(rule: ModelRouteRule, role: RouteRole, id: Long, model: String): ModelRouteRule = when (role) {
    RouteRole.PRIMARY -> rule.copy(primaryConfigId = id, primaryModel = model)
    RouteRole.FALLBACK -> rule.copy(fallbackConfigId = id, fallbackModel = model)
    RouteRole.LOW_COST -> rule.copy(lowCostConfigId = id, lowCostModel = model)
    RouteRole.LONG_CONTEXT -> rule.copy(longContextConfigId = id, longContextModel = model)
    RouteRole.VISION -> rule.copy(visionConfigId = id, visionModel = model)
}

@Composable
private fun EmptyHealthState(xc: HsuColors) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = xc.faint, modifier = Modifier.size(32.dp))
        Text("还没有供应商配置", fontSize = 13.sp, color = xc.ink, modifier = Modifier.padding(top = 8.dp))
        Text("先添加一个供应商，才能进行真实连接测试。", fontSize = 11.sp, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun RowScope.UsageMetric(label: String, value: String, xc: HsuColors) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(10.dp)) {
        Text(value, fontSize = 15.sp, color = xc.ink)
        Text(label, fontSize = 10.sp, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun compactNumber(value: Long): String = when {
    value >= 1_000_000_000 -> "${value / 1_000_000_000}B"
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}k"
    else -> value.toString()
}

private suspend fun exportConfig(context: android.content.Context, db: AppDatabase, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        val providers = JSONArray()
        db.providerConfigDao().getAll().forEach { cfg ->
            providers.put(JSONObject().apply {
                put("id", cfg.id); put("name", cfg.name); put("supplierId", cfg.supplierId); put("baseUrl", cfg.baseUrl)
                put("model", cfg.model); put("enabledModelIds", JSONArray(cfg.enabledModelIds)); put("apiPathType", cfg.apiPathType)
                put("contextWindow", cfg.contextWindow); put("supportsVision", cfg.supportsVision); put("supportsAudio", cfg.supportsAudio)
                put("supportsVideo", cfg.supportsVideo); put("supportsToolCall", cfg.supportsToolCall); put("isActive", cfg.isActive)
            })
        }
        val root = JSONObject().apply { put("format", "hsucode-model-config-v1"); put("providers", providers); put("metadata", JSONArray(ModelMetadataStore.getAll(db).map { modelMetadataJson(it) })); put("routing", modelRoutingJson(ModelRoutingStore.get(db))) }
        context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        "✓ 配置已导出（不含 API Key）"
    }.getOrElse { "✗ 导出失败：${it.message?.take(120).orEmpty()}" }
}

private suspend fun importConfig(context: android.content.Context, db: AppDatabase, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取文件")
        val root = JSONObject(raw)
        if (root.optString("format") != "hsucode-model-config-v1") error("不是 HSUCODE 模型配置文件")
        val dao = db.providerConfigDao(); var count = 0; val idMap = mutableMapOf<Long, Long>()
        val providers = root.optJSONArray("providers") ?: JSONArray()
        for (i in 0 until providers.length()) {
            val obj = providers.optJSONObject(i) ?: continue
            val name = obj.optString("name").ifBlank { obj.optString("supplierId") }
            val existing = dao.getAll().firstOrNull { it.name == name && it.baseUrl == obj.optString("baseUrl") }
            val ids = obj.optJSONArray("enabledModelIds")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotBlank) } ?: emptyList()
            val value = (existing ?: ProviderConfigEntity(name = name, supplierId = obj.optString("supplierId"), baseUrl = obj.optString("baseUrl"), apiKeyEnc = "", model = obj.optString("model"))).copy(
                name = name, supplierId = obj.optString("supplierId"), baseUrl = obj.optString("baseUrl"), model = obj.optString("model"), enabledModelIds = ids,
                apiPathType = obj.optString("apiPathType", "openai"), contextWindow = obj.optInt("contextWindow"), supportsVision = obj.optBoolean("supportsVision"),
                supportsAudio = obj.optBoolean("supportsAudio"), supportsVideo = obj.optBoolean("supportsVideo"), supportsToolCall = obj.optBoolean("supportsToolCall", true), isActive = existing?.isActive ?: false
            )
            val newId = if (existing == null) dao.insert(value) else { dao.update(value); existing.id }
            val oldId = obj.optLong("id")
            if (oldId > 0) idMap[oldId] = newId
            count++
        }
        val metadata = root.optJSONArray("metadata") ?: JSONArray()
        for (i in 0 until metadata.length()) metadata.optJSONObject(i)?.let { obj ->
            ModelMetadataStore.upsert(db, ModelMetadata(configId = idMap[obj.optLong("configId")] ?: obj.optLong("configId"), modelId = obj.optString("modelId"), contextWindow = obj.optInt("contextWindow"), supportsToolCall = obj.optBoolean("supportsToolCall"), supportsVision = obj.optBoolean("supportsVision"), supportsAudio = obj.optBoolean("supportsAudio"), supportsReasoning = obj.optBoolean("supportsReasoning"), inputPricePerMillion = obj.optDoubleOrNull("inputPricePerMillion"), outputPricePerMillion = obj.optDoubleOrNull("outputPricePerMillion"), status = obj.optString("status", "已导入"), sourceUpdatedAt = obj.optLong("sourceUpdatedAt"), favorite = obj.optBoolean("favorite"), region = obj.optString("region"), isLocal = obj.optBoolean("isLocal"), sortOrder = obj.optInt("sortOrder"), lastProbedAt = obj.optLong("lastProbedAt"), capabilitySource = obj.optString("capabilitySource")))
        }
        root.optJSONObject("routing")?.let { obj ->
            ModelRoutingStore.put(db, ModelRouteRule(
                primaryConfigId = idMap[obj.optLong("primaryConfigId")] ?: obj.optLong("primaryConfigId"), primaryModel = obj.optString("primaryModel"),
                fallbackConfigId = idMap[obj.optLong("fallbackConfigId")] ?: obj.optLong("fallbackConfigId"), fallbackModel = obj.optString("fallbackModel"),
                lowCostConfigId = idMap[obj.optLong("lowCostConfigId")] ?: obj.optLong("lowCostConfigId"), lowCostModel = obj.optString("lowCostModel"),
                longContextConfigId = idMap[obj.optLong("longContextConfigId")] ?: obj.optLong("longContextConfigId"), longContextModel = obj.optString("longContextModel"),
                visionConfigId = idMap[obj.optLong("visionConfigId")] ?: obj.optLong("visionConfigId"), visionModel = obj.optString("visionModel"),
                retryOnRateLimit = obj.optBoolean("retryOnRateLimit", true), retryOnServerError = obj.optBoolean("retryOnServerError", true),
                retryOnModelUnavailable = obj.optBoolean("retryOnModelUnavailable", true), retryOnCapabilityMismatch = obj.optBoolean("retryOnCapabilityMismatch", true)
            ))
        }
        "✓ 已导入 $count 个供应商；密钥请在供应商配置中补充"
    }.getOrElse { "✗ 导入失败：${it.message?.take(120).orEmpty()}" }
}

private fun modelMetadataJson(value: ModelMetadata) = JSONObject().apply {
    put("configId", value.configId); put("modelId", value.modelId); put("contextWindow", value.contextWindow)
    put("supportsToolCall", value.supportsToolCall); put("supportsVision", value.supportsVision); put("supportsAudio", value.supportsAudio); put("supportsReasoning", value.supportsReasoning)
    put("inputPricePerMillion", value.inputPricePerMillion ?: JSONObject.NULL); put("outputPricePerMillion", value.outputPricePerMillion ?: JSONObject.NULL)
    put("status", value.status); put("sourceUpdatedAt", value.sourceUpdatedAt); put("favorite", value.favorite); put("region", value.region); put("isLocal", value.isLocal)
    put("sortOrder", value.sortOrder); put("lastProbedAt", value.lastProbedAt); put("capabilitySource", value.capabilitySource)
}

private fun modelRoutingJson(value: ModelRouteRule) = JSONObject().apply {
    put("primaryConfigId", value.primaryConfigId); put("primaryModel", value.primaryModel); put("fallbackConfigId", value.fallbackConfigId); put("fallbackModel", value.fallbackModel)
    put("lowCostConfigId", value.lowCostConfigId); put("lowCostModel", value.lowCostModel); put("longContextConfigId", value.longContextConfigId); put("longContextModel", value.longContextModel)
    put("visionConfigId", value.visionConfigId); put("visionModel", value.visionModel); put("retryOnRateLimit", value.retryOnRateLimit); put("retryOnServerError", value.retryOnServerError); put("retryOnModelUnavailable", value.retryOnModelUnavailable); put("retryOnCapabilityMismatch", value.retryOnCapabilityMismatch)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
