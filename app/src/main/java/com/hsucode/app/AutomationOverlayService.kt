package com.hsucode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/** Small Phone-Agent style progress card. It is shown only after explicit overlay consent. */
class AutomationOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var action: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        stateJob = serviceScope.launch {
            AutomationTaskRunner.state.collectLatest { state ->
                update(state)
                if (state.terminal) {
                    delay(3_000)
                    stopSelf()
                }
            }
        }
    }

    private fun createOverlay() {
        val background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 18f
            setStroke(1, 0x22000000)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 10, 10)
            this.background = background
            elevation = 12f
        }
        val icon = TextView(this).apply { text = "◉"; textSize = 18f; setTextColor(0xFF5B42D6.toInt()) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 0, 8, 0) }
        title = TextView(this).apply { text = "Phone Agent"; textSize = 14f; setTextColor(0xFF202124.toInt()) }
        detail = TextView(this).apply { text = "准备中…"; textSize = 11f; setTextColor(0xFF6B7280.toInt()); maxLines = 1 }
        texts.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        texts.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        action = TextView(this).apply {
            text = "Ⅱ"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF4B5563.toInt())
            setPadding(12, 8, 12, 8)
            setOnClickListener { if (AutomationTaskRunner.state.value.paused) AutomationTaskRunner.resume() else AutomationTaskRunner.pause() }
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(0xFF4B5563.toInt())
            setPadding(8, 6, 8, 6)
            setOnClickListener { AutomationTaskRunner.cancel() }
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(28), dp(48)))
        box.addView(texts, LinearLayout.LayoutParams(0, dp(48), 1f))
        box.addView(action, LinearLayout.LayoutParams(dp(42), dp(48)))
        box.addView(close, LinearLayout.LayoutParams(dp(36), dp(48)))
        root = box
        val params = WindowManager.LayoutParams(
            dp(320),
            dp(72),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        runCatching { windowManager?.addView(box, params) }.onFailure { stopSelf() }
    }

    private fun update(state: AutomationTaskState) {
        val progress = if (state.total > 0) "${state.completed}/${state.total}" else "0/0"
        title?.text = "Phone Agent $progress"
        detail?.text = state.error ?: state.current.ifBlank { "准备中…" }
        action?.text = if (state.paused) "▶" else "Ⅱ"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stateJob?.cancel()
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HSUCODE 自动化", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("HSUCODE 自动化任务")
        .setContentText("任务正在执行")
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "hsucode_automation"
        private const val NOTIFICATION_ID = 4201
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, AutomationOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }
        }
        fun stop(context: Context?) { context?.let { runCatching { it.stopService(Intent(it, AutomationOverlayService::class.java)) } } }
    }
}
