package com.hsucode.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A small, explicit automation plan. Steps are visible to the user before execution. */
data class AutomationStep(
    val type: Type,
    val argument: String = "",
    val value: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val durationMs: Long = 300L,
) {
    enum class Type(val label: String) {
        LAUNCH_APP("启动应用"),
        CLICK_TEXT("点击文本"),
        INPUT_TEXT("输入文本"),
        TAP("点击坐标"),
        LONG_PRESS("长按坐标"),
        SWIPE("滑动"),
        WAIT("等待"),
        BACK("返回"),
        HOME("主页"),
        RECENTS("最近任务"),
        DUMP("读取页面结构"),
    }

    fun description(): String = when (type) {
        Type.LAUNCH_APP -> "启动 ${argument.ifBlank { "应用" }}"
        Type.CLICK_TEXT -> "点击“${argument.take(30)}”"
        Type.INPUT_TEXT -> "在“${argument.take(20)}”输入文本"
        Type.TAP -> "点击 ($x,$y)"
        Type.LONG_PRESS -> "长按 ($x,$y)"
        Type.SWIPE -> "滑动 ($x,$y) → ($endX,$endY)"
        Type.WAIT -> "等待 ${durationMs / 1_000.0}s"
        Type.BACK -> "返回"
        Type.HOME -> "回到主页"
        Type.RECENTS -> "打开最近任务"
        Type.DUMP -> "读取当前页面结构"
    }
}

data class AutomationTaskState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val taskName: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val lastOutput: String = "",
    val error: String? = null,
    val finished: Boolean = false,
    val overlayAvailable: Boolean = false,
) {
    val terminal: Boolean get() = finished || error != null
}

/** Executes only explicit, user-visible steps and exposes one observable state for the UI/overlay. */
object AutomationTaskRunner {
    private const val MAX_STEPS = 100
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AutomationTaskState())
    val state: StateFlow<AutomationTaskState> = _state.asStateFlow()
    @Volatile private var context: Context? = null
    @Volatile private var paused = false
    private var job: Job? = null

    fun initialize(value: Context) { context = value.applicationContext }

    fun canDrawOverlay(): Boolean = context?.let { Settings.canDrawOverlays(it) } == true

    fun openOverlaySettings() {
        val value = context ?: return
        runCatching {
            value.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${value.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun start(name: String, steps: List<AutomationStep>): Result<String> {
        val value = context ?: return Result.failure(IllegalStateException("自动化服务尚未初始化"))
        val plan = steps.take(MAX_STEPS)
        if (plan.isEmpty()) return Result.failure(IllegalArgumentException("请先添加至少一个自动化步骤"))
        if (job?.isActive == true) return Result.failure(IllegalStateException("已有自动化任务正在运行"))
        paused = false
        _state.value = AutomationTaskState(
            running = true,
            taskName = name.ifBlank { "自定义自动化" },
            total = plan.size,
            current = plan.first().description(),
            overlayAvailable = canDrawOverlay(),
        )
        if (canDrawOverlay()) AutomationOverlayService.start(value)
        job = scope.launch { runPlan(plan) }
        return Result.success(if (canDrawOverlay()) "自动化任务已启动" else "任务已启动；未授予悬浮窗权限")
    }

    fun pause() { if (_state.value.running) { paused = true; _state.value = _state.value.copy(paused = true) } }
    fun resume() { if (_state.value.running) { paused = false; _state.value = _state.value.copy(paused = false) } }
    fun cancel() { job?.cancel(CancellationException("用户停止自动化任务")); job = null; paused = false; _state.value = _state.value.copy(running = false, paused = false, finished = true, current = "已停止") }

    private suspend fun runPlan(plan: List<AutomationStep>) = mutex.withLock {
        try {
            for ((index, step) in plan.withIndex()) {
                awaitResume()
                _state.value = _state.value.copy(current = step.description(), completed = index)
                val output = execute(step)
                _state.value = _state.value.copy(lastOutput = output, completed = index + 1)
            }
            _state.value = _state.value.copy(running = false, paused = false, finished = true, current = "已完成")
        } catch (cancelled: CancellationException) {
            if (_state.value.running) _state.value = _state.value.copy(running = false, paused = false, finished = true, current = "已停止")
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(running = false, paused = false, error = error.message ?: "自动化步骤失败", current = "执行失败")
        } finally {
            job = null
        }
    }

    private suspend fun awaitResume() {
        while (paused && scope.isActive) delay(120)
    }

    private suspend fun execute(step: AutomationStep): String {
        val value = context ?: error("自动化服务尚未初始化")
        return when (step.type) {
            AutomationStep.Type.LAUNCH_APP -> {
                val intent = value.packageManager.getLaunchIntentForPackage(step.argument.trim())
                    ?: error("找不到可启动的应用：${step.argument}")
                value.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(800)
                "已启动 ${step.argument}"
            }
            AutomationStep.Type.CLICK_TEXT -> AccessibilityBridge.clickText(step.argument).getOrThrow()
            AutomationStep.Type.INPUT_TEXT -> AccessibilityBridge.inputText(step.argument, step.value).getOrThrow()
            AutomationStep.Type.TAP -> AccessibilityBridge.tap(step.x, step.y).getOrThrow()
            AutomationStep.Type.LONG_PRESS -> AccessibilityBridge.longPress(step.x, step.y, step.durationMs).getOrThrow()
            AutomationStep.Type.SWIPE -> AccessibilityBridge.swipe(step.x, step.y, step.endX, step.endY, step.durationMs).getOrThrow()
            AutomationStep.Type.WAIT -> { delay(step.durationMs.coerceIn(1L, 120_000L)); "等待完成" }
            AutomationStep.Type.BACK -> AccessibilityBridge.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK).getOrThrow()
            AutomationStep.Type.HOME -> AccessibilityBridge.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME).getOrThrow()
            AutomationStep.Type.RECENTS -> AccessibilityBridge.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS).getOrThrow()
            AutomationStep.Type.DUMP -> "页面元素：${AccessibilityBridge.snapshot().joinToString(" | ").take(2_000)}"
        }
    }
}
