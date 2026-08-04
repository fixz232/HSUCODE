package com.hsucode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val OPEN_SOURCE_URL = "https://github.com/fixz232/HSUCODE.git"
private const val REPO_WEB_URL = "https://github.com/fixz232/HSUCODE"
private const val UPSTREAM_URL = "https://github.com/kusesad-1122/XINCODE-Public"

/**
 * 「关于」独立页:应用图标 + 名称 + 版本,以及检查更新、项目地址、Star、更新日志、开源许可、开发者。
 * 版本行可点击 → 主动检查更新(与启动时的静默检查复用同一套 [UpdateChecker])。
 */
@Composable
fun AboutScreen(app: HsucodeApplication, onBack: () -> Unit) {
    val xc = LocalHsuColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val version = remember { UpdateChecker.currentVersion(ctx) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf("") }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    fun open(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun checkUpdate() {
        if (checking) return
        checking = true; checkResult = "正在检查…"
        scope.launch {
            // force=true:跳过 6 小时节流,用户主动点就必须真查一次。
            val result = UpdateChecker.check(
                context = ctx,
                settingGet = { k -> app.database.settingDao().get(k) },
                settingPut = { k, v -> app.database.settingDao().put(k, v) },
                force = true
            )
            checking = false
            when (result) {
                is UpdateChecker.CheckResult.Available -> {
                    updateInfo = result.info
                    checkResult = "发现新版本 ${result.info.version}"
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    checkResult = "已是最新版本 ${result.latestVersion}"
                }
                is UpdateChecker.CheckResult.Failed -> checkResult = result.message
                UpdateChecker.CheckResult.Skipped -> checkResult = "该版本已跳过"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(xc.bg).verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = xc.sub)
            }
            Spacer(Modifier.weight(1f))
            Text("关于", fontSize = 14.sp, fontFamily = HsuFont, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        // 图标 + 名称 + 版本
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "HSUCODE",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(CircleShape)
            )
            Spacer(Modifier.height(12.dp))
            Text("HSUCODE", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = HsuFont, color = xc.ink)
            Spacer(Modifier.height(4.dp))
            Text("版本 ${version.ifBlank { "未知" }}", fontSize = 12.sp, fontFamily = HsuFont, color = xc.sub)
            Spacer(Modifier.height(6.dp))
            Text("纯 Kotlin 原生 Android AI Agent", fontSize = 11.sp, fontFamily = HsuFont, color = xc.faint)
        }

        // 卡片一:检查更新
        AboutCard(xc) {
            AboutRow(
                title = if (checking) "检查中…" else "检查更新",
                subtitle = checkResult.ifBlank { "当前版本 ${version.ifBlank { "未知" }}" },
                xc = xc,
                onClick = { checkUpdate() }
            )
        }

        // 卡片二:项目相关
        AboutCard(xc) {
            AboutRow("开源地址", OPEN_SOURCE_URL, xc) { open(OPEN_SOURCE_URL) }
            AboutDivider(xc)
            AboutRow("感谢上游", UPSTREAM_URL, xc) { open(UPSTREAM_URL) }
            AboutDivider(xc)
            AboutRow("更新日志", "查看历史版本更新内容", xc) { open("$REPO_WEB_URL/releases") }
            AboutDivider(xc)
            AboutRow("开源许可声明", "GPL-3.0 与第三方素材许可", xc) { open("$REPO_WEB_URL/blob/main/THIRD-PARTY-NOTICES.md") }
        }

        // 卡片三:反馈与开发者
        AboutCard(xc) {
            AboutRow("问题反馈", "提交 Issue", xc) { open("$REPO_WEB_URL/issues") }
            AboutDivider(xc)
            AboutRow("开发者", "fixz232", xc) { open("https://github.com/fixz232") }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "© 2026 HSUCODE · 开源免费,遵循 GPL-3.0",
            fontSize = 10.sp, fontFamily = HsuFont, color = xc.faint,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    // 主动检查发现新版 → 复用启动时的同一个更新弹窗
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            currentVersion = version,
            onDismiss = { updateInfo = null },
            onSkip = {
                scope.launch {
                    UpdateChecker.skipVersion(info.version) { k, v -> app.database.settingDao().put(k, v) }
                }
                updateInfo = null
            }
        )
    }
}

@Composable
private fun AboutCard(xc: HsuColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(xc.bgElevated, RoundedCornerShape(8.dp)),
        content = content
    )
}

@Composable
private fun AboutRow(title: String, subtitle: String, xc: HsuColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontFamily = HsuFont, color = xc.ink)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 10.sp, fontFamily = HsuFont, color = xc.sub, maxLines = 2)
            }
        }
        Text("›", fontSize = 16.sp, fontFamily = HsuFont, color = xc.faint)
    }
}

@Composable
private fun AboutDivider(xc: HsuColors) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(xc.divider))
}
