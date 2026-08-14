package com.hsucode.app

import com.hsucode.data.IdentityEntity
import org.json.JSONObject

/** JSON codec compatible with common Tavern-style character card fields. */
object RoleCardCodec {
    fun encode(identity: IdentityEntity): String = JSONObject().apply {
        put("spec", "chara_card_v2"); put("spec_version", "2.0")
        put("data", JSONObject().apply {
            put("name", identity.name); put("description", identity.description.ifBlank { identity.systemPrompt })
            put("personality", identity.systemPrompt); put("first_mes", identity.openingStatement)
            put("system_prompt", identity.systemPrompt); put("post_history_instructions", identity.marks)
            put("temperature", identity.temperature.toDouble()); put("top_p", identity.topP ?: JSONObject.NULL)
            put("max_tokens", identity.maxTokens ?: JSONObject.NULL); put("creator_notes", identity.marks)
        })
    }.toString(2)

    fun decode(json: String): Result<IdentityEntity> = runCatching {
        val root = JSONObject(json); val data = root.optJSONObject("data") ?: root
        val name = data.optString("name").trim().ifBlank { error("角色卡缺少 name") }
        val system = data.optString("system_prompt").ifBlank { data.optString("personality") }.ifBlank { data.optString("description") }
        IdentityEntity(name = name, systemPrompt = system, description = data.optString("description"), openingStatement = data.optString("first_mes"), marks = data.optString("creator_notes").ifBlank { data.optString("post_history_instructions") }, temperature = data.optDouble("temperature", 1.0).toFloat().coerceIn(0f, 2f), topP = data.optDouble("top_p", Double.NaN).takeUnless { it.isNaN() }?.toFloat(), maxTokens = data.optInt("max_tokens", 0).takeIf { it > 0 })
    }
}
