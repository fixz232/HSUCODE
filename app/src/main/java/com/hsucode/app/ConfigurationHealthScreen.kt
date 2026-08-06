package com.hsucode.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class HealthLevel { OK, INFO, WARNING, ERROR }
enum class HealthAction { PROVIDER, WORKSPACE, NOTIFICATION, BATTERY, ENVIRONMENT, MCP, UPDATE, RECOVERY }

data class HealthCheck(
    val id: String,
    val title: String,
    val detail: String,
    val level: HealthLevel,
    val action: HealthAction? = null
)

object ConfigurationHealth {
    suspend fun run(context: Context, app: HsucodeApplication, testModel: Boolean): List<HealthCheck> =
        withContext(Dispatchers.IO) {
            val checks = mutableListOf<HealthCheck>()
            checks += database(app)
            checks += provider(app, testModel)
            checks += workspace(app)
            checks += notification(context)
            checks += battery(context)
            checks += root(app)
            checks += environment()
            checks += mcp(app)
            checks += recovery(app)
            checks += update(context, app)
            checks
        }

    private suspend fun database(app: HsucodeApplication): HealthCheck = runCatching {
        val sessions = app.database.sessionDao().countSessions()
        HealthCheck("database", "本地数据库", "可读写 · $sessions 个会话", HealthLevel.OK)
    }.getOrElse { HealthCheck("database", "本地数据库", it.message ?: "访问失败", HealthLevel.ERROR) }

    private suspend fun provider(app: HsucodeApplication, testModel: Boolean): HealthCheck {
        val cfg = app.database.providerConfigDao().getActive()
            ?: return HealthCheck("provider", "主模型", "尚未选择活动供应商", HealthLevel.ERROR, HealthAction.PROVIDER)
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            return HealthCheck("provider", "主模型", "端点或模型 ID 不完整", HealthLevel.ERROR, HealthAction.PROVIDER)
        }
        val key = runCatching {
            if (cfg.apiKeyEnc.isBlank()) "" else app.keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }
            .getOrElse {
                return HealthCheck("provider", "主模型", "API Key 无法解密", HealthLevel.ERROR, HealthAction.PROVIDER)
            }
        val local = cfg.baseUrl.contains("localhost", true) ||
            cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("10.0.2.2")
        if (key.isBlank() && !local && cfg.apiPathType != "anthropic") {
            return HealthCheck("provider", "主模型", "API Key 为空", HealthLevel.ERROR, HealthAction.PROVIDER)
        }
        if (!testModel) return HealthCheck("provider", "主模型", "${cfg.name} · ${cfg.model} · ${if (local) "本地端点" else "密钥可用"}", HealthLevel.OK, HealthAction.PROVIDER)
        val smoke = app.openAiClient.testConfig(cfg)
        if (!smoke.ok) {
            return HealthCheck("provider", "主模型", "连接失败: ${smoke.message.take(120)}", HealthLevel.ERROR, HealthAction.PROVIDER)
        }
        val listDetail = smoke.modelListStatus.takeIf { it.isNotBlank() && it != "未测试" } ?: "未提供模型列表"
        return HealthCheck("provider", "主模型", "真实请求正常 · ${smoke.latencyMs}ms · $listDetail", HealthLevel.OK, HealthAction.PROVIDER)
    }

    private fun workspace(app: HsucodeApplication): HealthCheck {
        val path = app.workspaceRootGlobal.ifBlank { com.hsucode.tools.WorkspaceContext.DEFAULT_ROOT }
        val dir = File(path)
        val level = if (dir.isDirectory && dir.canRead() && dir.canWrite()) HealthLevel.OK else HealthLevel.WARNING
        val detail = if (level == HealthLevel.OK) "可读写 · $path" else "目录不存在或当前进程不可读写 · $path"
        return HealthCheck("workspace", "工作区", detail, level, HealthAction.WORKSPACE)
    }

    private fun notification(context: Context): HealthCheck {
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return HealthCheck(
            "notification", "任务通知",
            if (granted) "已允许" else "未授权,后台完成时可能没有提醒",
            if (granted) HealthLevel.OK else HealthLevel.WARNING,
            if (granted) null else HealthAction.NOTIFICATION
        )
    }

    private fun battery(context: Context): HealthCheck {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = manager.isIgnoringBatteryOptimizations(context.packageName)
        return HealthCheck(
            "battery", "后台续跑",
            if (ignored) "未受电池优化限制" else "系统可能限制长任务后台运行",
            if (ignored) HealthLevel.OK else HealthLevel.WARNING,
            if (ignored) null else HealthAction.BATTERY
        )
    }

    private fun root(app: HsucodeApplication): HealthCheck {
        val status = app.rootDetector.status
        val ok = status == RootDetector.Status.ROOT_GRANTED
        return HealthCheck(
            "root", "Root 能力", app.rootDetector.detail.ifBlank { status.label },
            if (ok) HealthLevel.OK else HealthLevel.INFO, HealthAction.ENVIRONMENT
        )
    }

    private fun environment(): HealthCheck {
        val ready = WorkspaceRuntime.hasLinux()
        return HealthCheck(
            "ubuntu", "免 Root Ubuntu",
            if (ready) "${WorkspaceRuntime.detail()} 已就绪" else "未部署（可在环境配置中安装）",
            if (ready) HealthLevel.OK else HealthLevel.INFO, HealthAction.ENVIRONMENT
        )
    }

    private suspend fun mcp(app: HsucodeApplication): HealthCheck {
        val configured = app.database.mcpServerDao().getAll().size
        val active = app.mcpManager.activeConnectionCount()
        return HealthCheck(
            "mcp", "MCP 服务器",
            if (configured == 0) "未配置（可选）" else "$active / $configured 已连接",
            when { configured == 0 -> HealthLevel.INFO; active == configured -> HealthLevel.OK; else -> HealthLevel.WARNING },
            HealthAction.MCP
        )
    }

    private suspend fun recovery(app: HsucodeApplication): HealthCheck {
        val pending = app.database.stateCursorDao().getAll().size
        return HealthCheck(
            "recovery", "长任务恢复", if (pending == 0) "没有待恢复任务" else "$pending 个任务正在续跑或等待处理",
            if (pending == 0) HealthLevel.OK else HealthLevel.INFO,
            if (pending == 0) null else HealthAction.RECOVERY
        )
    }

    private suspend fun update(context: Context, app: HsucodeApplication): HealthCheck {
        return when (val result = UpdateChecker.check(
            context,
            settingGet = { app.database.settingDao().get(it) },
            settingPut = { key, value -> app.database.settingDao().put(key, value) },
            force = true
        )) {
            is UpdateChecker.CheckResult.Available -> HealthCheck(
                "update", "更新渠道", "发现 ${result.info.version}", HealthLevel.INFO, HealthAction.UPDATE
            )
            is UpdateChecker.CheckResult.UpToDate -> HealthCheck(
                "update", "更新渠道", "连接正常 · 最新 ${result.latestVersion}", HealthLevel.OK, HealthAction.UPDATE
            )
            is UpdateChecker.CheckResult.Failed -> HealthCheck(
                "update", "更新渠道", result.message, HealthLevel.WARNING, HealthAction.UPDATE
            )
            UpdateChecker.CheckResult.Skipped -> HealthCheck(
                "update", "更新渠道", "当前远端版本已跳过", HealthLevel.INFO, HealthAction.UPDATE
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationHealthScreen(
    app: HsucodeApplication,
    onBack: () -> Unit,
    onProvider: () -> Unit,
    onWorkspace: () -> Unit,
    onEnvironment: () -> Unit,
    onMcp: () -> Unit,
    onUpdate: () -> Unit,
    onRecovery: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalHsuColors.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<HealthCheck>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var testingModel by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { scope.launch { checks = ConfigurationHealth.run(context, app, false) } }

    fun refresh(testModel: Boolean = false) {
        loading = true
        testingModel = testModel
        scope.launch {
            checks = ConfigurationHealth.run(context, app, testModel)
            loading = false
            testingModel = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun perform(action: HealthAction) {
        when (action) {
            HealthAction.PROVIDER -> onProvider()
            HealthAction.WORKSPACE -> onWorkspace()
            HealthAction.NOTIFICATION -> if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            HealthAction.BATTERY -> runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            HealthAction.ENVIRONMENT -> onEnvironment()
            HealthAction.MCP -> onMcp()
            HealthAction.UPDATE -> onUpdate()
            HealthAction.RECOVERY -> onRecovery()
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("配置健康中心", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { refresh(true) }, enabled = !loading) {
                        Text(if (testingModel) "测试中" else "测试模型")
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (loading && checks.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        } else {
            val problems = checks.count { it.level == HealthLevel.ERROR || it.level == HealthLevel.WARNING }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        if (problems == 0) "关键配置正常" else "$problems 项需要处理",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (problems == 0) colors.green else colors.yellow,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp)
                    )
                }
                items(checks, key = { it.id }) { check ->
                    HealthRow(check) { check.action?.let(::perform) }
                }
                item { Spacer(Modifier.size(12.dp)) }
            }
        }
    }
}

@Composable
private fun HealthRow(check: HealthCheck, onClick: () -> Unit) {
    val colors = LocalHsuColors.current
    val tint: Color = when (check.level) {
        HealthLevel.OK -> colors.green
        HealthLevel.INFO -> colors.sub
        HealthLevel.WARNING -> colors.yellow
        HealthLevel.ERROR -> colors.red
    }
    val icon = when (check.level) {
        HealthLevel.OK -> Icons.Outlined.CheckCircle
        HealthLevel.INFO -> Icons.Outlined.Info
        HealthLevel.WARNING -> Icons.Outlined.WarningAmber
        HealthLevel.ERROR -> Icons.Outlined.ErrorOutline
    }
    Surface(
        onClick = onClick,
        enabled = check.action != null,
        color = colors.bgElevated,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(check.title, fontWeight = FontWeight.Medium)
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (check.action != null) {
                Icon(Icons.Outlined.ChevronRight, null, tint = colors.faint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
