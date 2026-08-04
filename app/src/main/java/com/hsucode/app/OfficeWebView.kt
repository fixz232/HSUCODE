package com.hsucode.app

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Decorative pixel-office layer. The native worker list remains the accessible source of truth. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficeWebView(
    configuredAgents: List<String>,
    workers: List<SubAgentSceneState.Worker>,
    brainBusy: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    val payload = remember(configuredAgents, workers) {
        val arr = JSONArray()
        workers.forEach { worker ->
            arr.put(JSONObject().apply {
                put("id", worker.workerRunId)
                put("name", worker.agent)
                put("status", worker.status.webStatus())
                put("text", worker.sceneText())
            })
        }
        val runningNames = workers.map { it.agent }.toSet()
        configuredAgents.filterNot { it in runningNames }.forEachIndexed { index, name ->
            arr.put(JSONObject().apply {
                put("id", "configured:$index:$name")
                put("name", name)
                put("status", "idle")
                put("text", "")
            })
        }
        arr.toString()
    }
    val brainText = remember(workers, brainBusy) {
        if (!brainBusy) "" else workers
            .filter { it.status.isActive }
            .take(2)
            .joinToString("；") { "${it.agent}→${it.task.take(12)}" }
            .ifBlank { "正在协调任务" }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                setBackgroundColor(0xFF141621.toInt())
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        ready = true
                    }
                }
                loadUrl("file:///android_asset/office/index.html")
                web = this
            }
        },
        update = { web = it }
    )

    LaunchedEffect(ready, payload, brainBusy, brainText, reducedMotion) {
        if (!ready) return@LaunchedEffect
        delay(100)
        val brain = JSONObject.quote(brainText)
        web?.evaluateJavascript(
            "window.setMotionEnabled(${!reducedMotion});" +
                "window.setBrain($brainBusy,$brain);window.setAgents($payload);",
            null
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            ready = false
            web?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            web = null
        }
    }
}

private fun SubAgentSceneState.Status.webStatus(): String = when (this) {
    SubAgentSceneState.Status.QUEUED -> "queued"
    SubAgentSceneState.Status.PREPARING -> "preparing"
    SubAgentSceneState.Status.RUNNING -> "running"
    SubAgentSceneState.Status.WAITING_PERMISSION -> "waiting_permission"
    SubAgentSceneState.Status.SUCCEEDED -> "succeeded"
    SubAgentSceneState.Status.FAILED -> "failed"
    SubAgentSceneState.Status.CANCELLED -> "cancelled"
    SubAgentSceneState.Status.TIMED_OUT -> "timed_out"
    SubAgentSceneState.Status.UNKNOWN -> "unknown"
}

private fun SubAgentSceneState.Worker.sceneText(): String = when (status) {
    SubAgentSceneState.Status.QUEUED -> "排队中"
    SubAgentSceneState.Status.PREPARING -> "准备任务"
    SubAgentSceneState.Status.RUNNING -> currentTool.ifBlank { activity.ifBlank { "执行中" } }.take(18)
    SubAgentSceneState.Status.WAITING_PERMISSION -> "等待审批"
    SubAgentSceneState.Status.SUCCEEDED -> "已完成"
    SubAgentSceneState.Status.FAILED -> "失败"
    SubAgentSceneState.Status.CANCELLED -> "已取消"
    SubAgentSceneState.Status.TIMED_OUT -> "已超时"
    SubAgentSceneState.Status.UNKNOWN -> "状态未知"
}
