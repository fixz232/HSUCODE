package com.hsucode.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 启动时静默检查 GitHub Release 是否有新版本。
 *
 * 原则:
 *  - **启动静默**:调用方可忽略失败结果,但主动检查必须明确显示失败,不能把网络错误冒充成“已是最新”。
 *  - **节流**:每 [CHECK_INTERVAL_MS] 最多查一次;GitHub 未认证 API 有 60 次/小时/IP 的限制。
 *  - **可忽略**:用户点「跳过此版本」后,该版本不再提示,直到出现更晚的版本。
 *  - 只读公开的 releases 接口,不需要任何 token。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val RELEASES_API = "https://api.github.com/repos/fixz232/HSUCODE/releases/latest"
    const val RELEASES_PAGE = "https://github.com/fixz232/HSUCODE/releases"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L   // 6 小时
    private const val KEY_LAST_CHECK = "update_last_check_ms"
    private const val KEY_SKIPPED = "update_skipped_version"

    /** 一个可供 UI 弹窗展示的更新。 */
    data class UpdateInfo(
        val version: String,      // 如 "1.02"
        val notes: String,        // Release 说明正文
        val pageUrl: String,      // Release 页面(下载跳转目标)
        val apkUrl: String?       // 直链 APK(若该 Release 挂了 apk 资产)
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data class UpToDate(val latestVersion: String) : CheckResult
        data class Failed(val message: String) : CheckResult
        data object Skipped : CheckResult
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 读取本机当前版本名(如 "1.01")。取不到时返回空串。 */
    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    /**
     * 版本号比较。项目版本规则为 `主.次`,次位递增(1.0 → 1.01 → 1.02 … → 1.10)。
     * 逐段按整数比较:1.02 > 1.01,1.10 > 1.02(字符串比较会把 "1.10" < "1.02" 判错,故必须转整数)。
     * @return true 表示 [remote] 比 [local] 新
     */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 检查更新。结果会区分已最新、失败与后台节流,主动检查页据此给出真实反馈。
     * @param force true 时忽略时间节流(用于设置页的「立即检查」)
     */
    suspend fun check(
        context: Context,
        settingGet: suspend (String) -> String?,
        settingPut: suspend (String, String) -> Unit,
        force: Boolean = false
    ): CheckResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            if (!force) {
                val last = settingGet(KEY_LAST_CHECK)?.toLongOrNull() ?: 0L
                if (now - last < CHECK_INTERVAL_MS) return@withContext CheckResult.Skipped
            }

            val req = Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HSUCODE-UpdateChecker")
                .get().build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val message = when (resp.code) {
                        403, 429 -> "更新服务请求过于频繁,请稍后重试"
                        404 -> "更新仓库尚未发布或暂不可访问"
                        else -> "更新服务返回 HTTP ${resp.code}"
                    }
                    Log.d(TAG, "check failed: HTTP ${resp.code}")
                    return@withContext CheckResult.Failed(message)
                }
                resp.body?.string().orEmpty().ifBlank {
                    return@withContext CheckResult.Failed("更新服务返回了空内容")
                }
            }
            // 请求真正成功才记录检查时间——失败不该白白吃掉一个检查窗口。
            settingPut(KEY_LAST_CHECK, now.toString())

            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                return@withContext CheckResult.Failed("更新仓库没有可用的正式版本")
            }

            val tag = json.optString("tag_name").ifBlank {
                return@withContext CheckResult.Failed("更新信息缺少版本号")
            }
            val remoteVer = tag.removePrefix("v").removePrefix("V")
            val local = currentVersion(context)
            if (local.isBlank()) return@withContext CheckResult.Failed("无法读取当前应用版本")
            if (!isNewer(remoteVer, local)) return@withContext CheckResult.UpToDate(remoteVer)

            // 用户主动跳过过这个版本(或更新的)就别再烦他。
            val skipped = settingGet(KEY_SKIPPED).orEmpty()
            if (skipped.isNotBlank() && !isNewer(remoteVer, skipped)) return@withContext CheckResult.Skipped

            val apk = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    ?.optString("browser_download_url")
            }?.takeIf { it.isNotBlank() }

            CheckResult.Available(
                UpdateInfo(
                    version = remoteVer,
                    notes = json.optString("body").trim().take(2000),
                    pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
                    apkUrl = apk
                )
            )
        } catch (t: Throwable) {
            Log.d(TAG, "check failed: ${t.message}")
            CheckResult.Failed("无法连接更新服务,请检查网络后重试")
        }
    }

    /** 记录「跳过此版本」,之后不再为该版本弹窗。 */
    suspend fun skipVersion(version: String, settingPut: suspend (String, String) -> Unit) {
        settingPut(KEY_SKIPPED, version)
    }
}
