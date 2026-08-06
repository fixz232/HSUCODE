package com.hsucode.app

import com.hsucode.data.AppDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Persisted per-model facts. Defaults are derived from the provider config when absent. */
data class ModelMetadata(
    val configId: Long,
    val modelId: String,
    val contextWindow: Int = 0,
    val supportsToolCall: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsReasoning: Boolean = false,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val status: String = "unknown",
    val sourceUpdatedAt: Long = 0L,
    val favorite: Boolean = false,
    val region: String = "",
    val isLocal: Boolean = false,
    val sortOrder: Int = 0,
    val lastProbedAt: Long = 0L,
    val capabilitySource: String = ""
)

object ModelMetadataStore {
    private const val KEY = "model_metadata_v1"

    suspend fun getAll(db: AppDatabase): List<ModelMetadata> {
        val raw = db.settingDao().get(KEY).orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index -> decode(arr.optJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(db: AppDatabase, value: ModelMetadata) {
        val values = getAll(db).filterNot { it.configId == value.configId && it.modelId == value.modelId } + value
        db.settingDao().put(KEY, JSONArray(values.map(::encode)).toString())
    }

    suspend fun saveOrder(db: AppDatabase, ordered: List<Pair<Long, String>>) {
        val all = getAll(db).associateBy { it.configId to it.modelId }.toMutableMap()
        ordered.forEachIndexed { index, (configId, modelId) ->
            val current = all[configId to modelId] ?: ModelMetadata(configId = configId, modelId = modelId)
            all[configId to modelId] = current.copy(sortOrder = index)
        }
        db.settingDao().put(KEY, JSONArray(all.values.sortedBy { it.sortOrder }.map(::encode)).toString())
    }

    suspend fun toggleFavorite(db: AppDatabase, configId: Long, modelId: String): Boolean {
        val current = getAll(db).firstOrNull { it.configId == configId && it.modelId == modelId } ?: return false
        upsert(db, current.copy(favorite = !current.favorite))
        return !current.favorite
    }

    private fun encode(value: ModelMetadata) = JSONObject().apply {
        put("configId", value.configId)
        put("modelId", value.modelId)
        put("contextWindow", value.contextWindow)
        put("supportsToolCall", value.supportsToolCall)
        put("supportsVision", value.supportsVision)
        put("supportsAudio", value.supportsAudio)
        put("supportsReasoning", value.supportsReasoning)
        put("inputPricePerMillion", value.inputPricePerMillion ?: JSONObject.NULL)
        put("outputPricePerMillion", value.outputPricePerMillion ?: JSONObject.NULL)
        put("status", value.status)
        put("sourceUpdatedAt", value.sourceUpdatedAt)
        put("favorite", value.favorite)
        put("region", value.region)
        put("isLocal", value.isLocal)
        put("sortOrder", value.sortOrder)
        put("lastProbedAt", value.lastProbedAt)
        put("capabilitySource", value.capabilitySource)
    }

    private fun decode(obj: JSONObject?): ModelMetadata? {
        if (obj == null) return null
        val id = obj.optString("modelId").trim()
        if (id.isBlank()) return null
        return ModelMetadata(
            configId = obj.optLong("configId"),
            modelId = id,
            contextWindow = obj.optInt("contextWindow"),
            supportsToolCall = obj.optBoolean("supportsToolCall"),
            supportsVision = obj.optBoolean("supportsVision"),
            supportsAudio = obj.optBoolean("supportsAudio"),
            supportsReasoning = obj.optBoolean("supportsReasoning"),
            inputPricePerMillion = obj.optDoubleOrNull("inputPricePerMillion"),
            outputPricePerMillion = obj.optDoubleOrNull("outputPricePerMillion"),
            status = obj.optString("status", "unknown"),
            sourceUpdatedAt = obj.optLong("sourceUpdatedAt"),
            favorite = obj.optBoolean("favorite"),
            region = obj.optString("region"),
        isLocal = obj.optBoolean("isLocal"),
        sortOrder = obj.optInt("sortOrder"),
        lastProbedAt = obj.optLong("lastProbedAt"),
        capabilitySource = obj.optString("capabilitySource")
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
}

data class ProviderHealth(
    val testedAt: Long = 0L,
    val latencyMs: Long = 0L,
    val modelListStatus: String = "未测试",
    val protocol: String = "OpenAI 兼容",
    val ok: Boolean = false,
    val statusCode: Int = 0,
    val message: String = "",
    val balanceAmount: Double? = null,
    val balanceCurrency: String = "",
    val balanceCheckedAt: Long = 0L
)

object ProviderHealthStore {
    private const val PREFIX = "model_health_"

    suspend fun get(db: AppDatabase, configId: Long): ProviderHealth {
        val raw = db.settingDao().get("$PREFIX$configId").orEmpty()
        return runCatching {
            val obj = JSONObject(raw)
            ProviderHealth(
                testedAt = obj.optLong("testedAt"),
                latencyMs = obj.optLong("latencyMs"),
                modelListStatus = obj.optString("modelListStatus", "未测试"),
                protocol = obj.optString("protocol", "OpenAI 兼容"),
                ok = obj.optBoolean("ok"),
                statusCode = obj.optInt("statusCode"),
                message = obj.optString("message"),
                balanceAmount = if (obj.isNull("balanceAmount")) null else obj.optDouble("balanceAmount"),
                balanceCurrency = obj.optString("balanceCurrency"),
                balanceCheckedAt = obj.optLong("balanceCheckedAt")
            )
        }.getOrDefault(ProviderHealth())
    }

    suspend fun put(db: AppDatabase, configId: Long, value: ProviderHealth) {
        db.settingDao().put("$PREFIX$configId", JSONObject().apply {
            put("testedAt", value.testedAt)
            put("latencyMs", value.latencyMs)
            put("modelListStatus", value.modelListStatus)
            put("protocol", value.protocol)
            put("ok", value.ok)
            put("statusCode", value.statusCode)
            put("message", value.message)
            put("balanceAmount", value.balanceAmount ?: JSONObject.NULL)
            put("balanceCurrency", value.balanceCurrency)
            put("balanceCheckedAt", value.balanceCheckedAt)
        }.toString())
    }
}

data class ModelRouteRule(
    val primaryConfigId: Long = 0L,
    val primaryModel: String = "",
    val fallbackConfigId: Long = 0L,
    val fallbackModel: String = "",
    val lowCostConfigId: Long = 0L,
    val lowCostModel: String = "",
    val longContextConfigId: Long = 0L,
    val longContextModel: String = "",
    val visionConfigId: Long = 0L,
    val visionModel: String = "",
    val retryOnRateLimit: Boolean = true,
    val retryOnServerError: Boolean = true,
    val retryOnModelUnavailable: Boolean = true,
    val retryOnCapabilityMismatch: Boolean = true
)

object ModelRoutingStore {
    private const val KEY = "model_routing_rules"

    suspend fun get(db: AppDatabase): ModelRouteRule {
        val raw = db.settingDao().get(KEY).orEmpty()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(ModelRouteRule())
    }

    suspend fun put(db: AppDatabase, rule: ModelRouteRule) {
        db.settingDao().put(KEY, encode(rule).toString())
    }

    private fun encode(value: ModelRouteRule) = JSONObject().apply {
        put("primaryConfigId", value.primaryConfigId); put("primaryModel", value.primaryModel)
        put("fallbackConfigId", value.fallbackConfigId); put("fallbackModel", value.fallbackModel)
        put("lowCostConfigId", value.lowCostConfigId); put("lowCostModel", value.lowCostModel)
        put("longContextConfigId", value.longContextConfigId); put("longContextModel", value.longContextModel)
        put("visionConfigId", value.visionConfigId); put("visionModel", value.visionModel)
        put("retryOnRateLimit", value.retryOnRateLimit); put("retryOnServerError", value.retryOnServerError)
        put("retryOnModelUnavailable", value.retryOnModelUnavailable)
        put("retryOnCapabilityMismatch", value.retryOnCapabilityMismatch)
    }

    private fun decode(obj: JSONObject) = ModelRouteRule(
        primaryConfigId = obj.optLong("primaryConfigId"), primaryModel = obj.optString("primaryModel"),
        fallbackConfigId = obj.optLong("fallbackConfigId"), fallbackModel = obj.optString("fallbackModel"),
        lowCostConfigId = obj.optLong("lowCostConfigId"), lowCostModel = obj.optString("lowCostModel"),
        longContextConfigId = obj.optLong("longContextConfigId"), longContextModel = obj.optString("longContextModel"),
        visionConfigId = obj.optLong("visionConfigId"), visionModel = obj.optString("visionModel"),
        retryOnRateLimit = obj.optBoolean("retryOnRateLimit", true),
        retryOnServerError = obj.optBoolean("retryOnServerError", true),
        retryOnModelUnavailable = obj.optBoolean("retryOnModelUnavailable", true),
        retryOnCapabilityMismatch = obj.optBoolean("retryOnCapabilityMismatch", true)
    )
}
