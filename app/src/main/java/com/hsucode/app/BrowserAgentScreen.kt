package com.hsucode.app

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserAgentScreen(onBack: () -> Unit) {
    var address by remember { mutableStateOf("https://") }; var selector by remember { mutableStateOf("") }; var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("仅加载 HTTP/HTTPS 页面。页面操作需要你手动触发。") }; var webView by remember { mutableStateOf<WebView?>(null) }
    fun load(url: String) {
        val normalized = url.trim(); if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) { result = "只允许 HTTP/HTTPS 地址"; return }
        address = normalized; webView?.loadUrl(normalized)
    }
    fun evaluate(script: String, success: String) { webView?.evaluateJavascript(script) { value -> result = "$success\n${value.removeSurrounding("\"").replace("\\n", "\n").take(12_000)}" } ?: run { result = "浏览器尚未就绪" } }
    Scaffold(topBar = { TopAppBar(title = { Text("浏览器 Agent") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = { IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "刷新") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                OutlinedTextField(address, { address = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("网页地址") })
                Button(onClick = { load(address) }, modifier = Modifier.padding(start = 8.dp)) { Text("打开") }
            }
            AndroidView(factory = { context -> WebView(context).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString(); return !(url.startsWith("https://") || url.startsWith("http://"))
                    }
                }; webView = this
            } }, update = { webView = it }, modifier = Modifier.fillMaxWidth().weight(1f))
            OutlinedTextField(selector, { selector = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, label = { Text("CSS 选择器，例如 input[name=q]") })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { evaluate("(document.body&&document.body.innerText||'').slice(0,12000)", "正文提取") }) { Text("提取正文") }
                TextButton(onClick = { if (selector.isBlank()) result = "请填写 CSS 选择器" else evaluate("(()=>{const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return '未找到元素';e.click();return '已点击 '+${JSONObject.quote(selector)}})()", "页面操作") }) { Text("点击元素") }
            }
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, label = { Text("填写内容") })
            TextButton(onClick = { if (selector.isBlank() || input.isBlank()) result = "请填写选择器和内容" else evaluate("(()=>{const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return '未找到元素';e.focus();e.value=${JSONObject.quote(input)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return '已填写 '+${JSONObject.quote(selector)}})()", "页面操作") }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("填写元素") }
            Text(result, modifier = Modifier.fillMaxWidth().height(108.dp).padding(horizontal = 12.dp))
        }
    }
    DisposableEffect(webView) { onDispose { webView?.destroy() } }
}
