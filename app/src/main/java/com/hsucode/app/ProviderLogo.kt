package com.hsucode.app

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG

private val providerLogoAssets = mapOf(
    "alibaba-coding-plan" to "qwen-color.svg",
    "anthropic" to "claude-color.svg",
    "deepseek" to "deepseek-color.svg",
    "gemini" to "gemini-color.svg",
    "kimi-coding" to "kimi-color.svg",
    "kimi-intl" to "kimi-color.svg",
    "moonshot" to "kimi-color.svg",
    "lmstudio" to "ollama.svg",
    "longcat" to "longcat-color.svg",
    "minimax" to "minimax-color.svg",
    "minimax-cn" to "minimax-color.svg",
    "nvidia" to "nvidia-color.svg",
    "ollama" to "ollama.svg",
    "openai" to "openai.svg",
    "openrouter" to "openrouter.svg",
    "siliconflow" to "siliconflow.svg",
    "modelscope" to "qwen-color.svg",
    "stepfun-plan-cn" to "stepfun-color.svg",
    "stepfun-plan-intl" to "stepfun-color.svg",
    "vercel-ai-gateway" to "vercel.svg",
    "xai" to "xai.svg",
    "xiaomi" to "xiaomimimo.svg",
    "xiaomi-plan-ams" to "xiaomimimo.svg",
    "xiaomi-plan-cn" to "xiaomimimo.svg",
    "xiaomi-plan-sgp" to "xiaomimimo.svg",
    "zai" to "zhipu-color.svg",
    "zai-coding" to "zhipu-color.svg",
    "zhipu" to "zhipu-color.svg",
    "zhipu-coding-cn" to "zhipu-color.svg",
    "ollama-cloud" to "ollama.svg"
)

/** Displays the imported provider SVG when available, with a deterministic text fallback. */
@Composable
fun ProviderLogo(
    supplierId: String,
    providerName: String,
    modifier: Modifier = Modifier,
    logoSize: Dp = 40.dp
) {
    val xc = LocalHsuColors.current
    val context = LocalContext.current
    val asset = providerLogoAssets[supplierId]
    val svg = remember(asset) {
        asset?.let {
            runCatching {
                // A few catalog SVGs use width/height="1em". Rendering their Picture and
                // scaling by Picture.width makes the mark occupy only a tiny fraction of the
                // logo box. Set a normalized document size and render into the actual target
                // rectangle instead, so viewBox-only and em-sized assets behave consistently.
                SVG.getFromAsset(context.assets, "provider_icons/$it").apply {
                    setDocumentWidth(128f)
                    setDocumentHeight(128f)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .size(logoSize)
            .clip(RoundedCornerShape(8.dp))
            .background(xc.bgElevated)
            .semantics { contentDescription = "$providerName 图标" }
    ) {
        if (svg != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    val inset = minOf(this.size.width, this.size.height) * 0.055f
                    val target = RectF(
                        inset,
                        inset,
                        this.size.width - inset,
                        this.size.height - inset
                    )
                    svg.renderToCanvas(canvas.nativeCanvas, target)
                }
            }
        } else {
            androidx.compose.material3.Text(
                text = providerInitials(providerName),
                color = xc.green,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
            )
        }
    }
}

private fun providerInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size >= 2) return (words[0].take(1) + words[1].take(1)).uppercase()
    return name.trim().take(2).ifBlank { "?" }.uppercase()
}
