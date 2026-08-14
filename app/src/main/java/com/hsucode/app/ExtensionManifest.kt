package com.hsucode.app

import org.json.JSONObject
import java.net.URI

data class ExtensionManifest(val id: String, val version: String, val kind: String, val name: String, val description: String, val permissions: List<String>, val sourceUrl: String, val sha256: String = "", val content: String = "")

object ExtensionManifestValidator {
    fun parse(raw: String): Result<ExtensionManifest> = runCatching {
        val json = JSONObject(raw); val id = json.optString("id").trim(); require(id.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "扩展 id 无效" }
        val url = json.optString("sourceUrl").trim(); require(url.isBlank() || URI(url).scheme == "https") { "扩展源必须使用 HTTPS" }
        val permissions = buildList { json.optJSONArray("permissions")?.let { arr -> for (i in 0 until arr.length()) add(arr.optString(i).take(80)) } }
        ExtensionManifest(id, json.optString("version", "1.0.0"), json.optString("kind", "skill"), json.optString("name", id), json.optString("description"), permissions, url, json.optString("sha256"), json.optString("content"))
    }
}
