package com.hsucode.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional, user-enabled automation bridge. It never enables itself or bypasses Android settings. */
class HsucodeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); AccessibilityBridge.attach(this) }
    override fun onInterrupt() = Unit
    override fun onDestroy() { AccessibilityBridge.detach(this); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
}

object AccessibilityBridge {
    @Volatile private var service: HsucodeAccessibilityService? = null
    fun attach(value: HsucodeAccessibilityService) { service = value }
    fun detach(value: HsucodeAccessibilityService) { if (service === value) service = null }
    fun isEnabled(): Boolean = service != null
    fun snapshot(limit: Int = 80): List<String> {
        val root = service?.rootInActiveWindow ?: return emptyList(); val result = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) { if (node == null || result.size >= limit) return; val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" · ").trim(); if (text.isNotBlank()) result += text; for (i in 0 until node.childCount) visit(node.getChild(i)) }
        visit(root); return result
    }

    /** Perform a real screen gesture through the user-enabled accessibility service. */
    fun tap(x: Int, y: Int): Result<String> = gesture(x, y, x, y, 1L, "点击")

    fun longPress(x: Int, y: Int, durationMs: Long = 800L): Result<String> =
        gesture(x, y, x, y, durationMs.coerceIn(300L, 10_000L), "长按")

    fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long = 300L): Result<String> =
        gesture(x, y, endX, endY, durationMs.coerceIn(1L, 10_000L), "滑动")

    private fun gesture(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long, label: String): Result<String> = runCatching {
        require(x >= 0 && y >= 0 && endX >= 0 && endY >= 0) { "坐标不能为负数" }
        val target = service ?: error("无障碍服务未启用")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) error("当前 Android 版本不支持手势自动化")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(endX.toFloat(), endY.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        require(target.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)) { "${label}请求未被系统接受" }
        "已执行$label：($x,$y) → ($endX,$endY)"
    }

    fun globalAction(action: Int): Result<String> = runCatching {
        val target = service ?: error("无障碍服务未启用")
        require(target.performGlobalAction(action)) { "系统操作未被接受" }
        "已执行系统操作"
    }
    fun clickText(text: String): Result<String> = runCatching {
        require(text.isNotBlank()) { "请输入元素文本" }; val root = service?.rootInActiveWindow ?: error("无障碍服务未启用")
        val node = findNode(root, text) ?: error("未找到匹配元素")
        var target: AccessibilityNodeInfo? = node; while (target != null && !target.isClickable) target = target.parent
        requireNotNull(target) { "匹配元素不可点击" }.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { require(it) { "点击失败" } }; "已点击：$text"
    }
    fun inputText(label: String, value: String): Result<String> = runCatching {
        require(label.isNotBlank() && value.isNotBlank()) { "请输入控件文本和内容" }; val root = service?.rootInActiveWindow ?: error("无障碍服务未启用")
        val node = findNode(root, label) ?: error("未找到输入控件")
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isEditable } ?: error("匹配元素不是输入框")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        require(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "写入失败" }; "已输入到：$label"
    }

    private fun findNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val needle = query.trim()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName,
            )
            if (values.any { it == needle || it.contains(needle, ignoreCase = true) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }
}
