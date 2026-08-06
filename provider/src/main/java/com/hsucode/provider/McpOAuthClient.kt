package com.hsucode.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Generic OAuth 2 device-flow helper for MCP servers. Tokens stay with the caller. */
object McpOAuthClient {
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSeconds: Long = 5)

    suspend fun requestDeviceCode(client: OkHttpClient, endpoint: String, clientId: String, scope: String = ""): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder().add("client_id", clientId).apply { if (scope.isNotBlank()) add("scope", scope) }.build()
            val response = client.newCall(Request.Builder().url(endpoint).post(body).build()).execute()
            response.use {
                if (!it.isSuccessful) error("OAuth 设备码请求失败: HTTP ${it.code}")
                val obj = JSONObject(it.body?.string().orEmpty())
                DeviceCode(
                    deviceCode = obj.optString("device_code").ifBlank { error("OAuth 缺少 device_code") },
                    userCode = obj.optString("user_code"),
                    verificationUri = obj.optString("verification_uri_complete").ifBlank { obj.optString("verification_uri") },
                    intervalSeconds = obj.optLong("interval", 5L).coerceIn(2L, 30L)
                )
            }
        }
    }

    suspend fun pollToken(client: OkHttpClient, endpoint: String, clientId: String, code: DeviceCode, timeoutMs: Long = 180_000L): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val body = FormBody.Builder().add("client_id", clientId).add("device_code", code.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
                val response = client.newCall(Request.Builder().url(endpoint).post(body).build()).execute()
                val obj = response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful && text.isBlank()) error("OAuth 令牌请求失败: HTTP ${it.code}")
                    JSONObject(text)
                }
                obj.optString("access_token").takeIf { it.isNotBlank() }?.let { return@runCatching it }
                val error = obj.optString("error")
                if (error !in setOf("authorization_pending", "slow_down")) error("OAuth 授权失败: $error")
                delay(code.intervalSeconds * 1_000L)
            }
            error("OAuth 授权超时")
        }
    }
}
