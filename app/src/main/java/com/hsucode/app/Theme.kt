package com.hsucode.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class HsuColors(
    val bg: Color,
    val bgElevated: Color,
    val ink: Color,
    val sub: Color,
    val faint: Color,
    val green: Color,
    val red: Color,
    val yellow: Color,
    val border: Color,
    val activeBg: Color,
    val activeBar: Color,
    val divider: Color,
    val isDark: Boolean
)

val HsuLight = HsuColors(
    bg = Color(0xFFF7F9F7),
    bgElevated = Color(0xFFFFFFFF),
    ink = Color(0xFF17201D),
    sub = Color(0xFF4F5F59),
    faint = Color(0xFF66736E),
    green = Color(0xFF176B4C),
    red = Color(0xFFA23C3C),
    yellow = Color(0xFF8A6200),
    border = Color(0xFFD9E0DC),
    activeBg = Color(0x14176B4C),
    activeBar = Color(0xFF176B4C),
    divider = Color(0x1F17201D),
    isDark = false
)

val HsuDark = HsuColors(
    bg = Color(0xFF101412),
    bgElevated = Color(0xFF181D1A),
    ink = Color(0xFFEDF2EF),
    sub = Color(0xFFACB8B2),
    faint = Color(0xFF87938D),
    green = Color(0xFF70C99B),
    red = Color(0xFFFF9B95),
    yellow = Color(0xFFE9BC63),
    border = Color(0xFF303934),
    activeBg = Color(0x2670C99B),
    activeBar = Color(0xFF70C99B),
    divider = Color(0x2DEDF2EF),
    isDark = true
)

val LocalHsuColors = compositionLocalOf { HsuLight }
val HsuFont: FontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

private fun hsuTypography(colors: HsuColors) = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = colors.ink),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = colors.ink),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = colors.sub),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = colors.ink),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = colors.ink),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = colors.ink),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val HsuShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun HsuTheme(dark: Boolean, content: @Composable () -> Unit) {
    val target = if (dark) HsuDark else HsuLight
    val bg by animateColorAsState(target.bg, tween(220), label = "themeBg")
    val ink by animateColorAsState(target.ink, tween(220), label = "themeInk")
    val elevated by animateColorAsState(target.bgElevated, tween(220), label = "themeSurface")
    val border by animateColorAsState(target.border, tween(220), label = "themeBorder")
    val colors = target.copy(bg = bg, ink = ink, bgElevated = elevated, border = border)
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.green, onPrimary = Color(0xFF082117),
            background = colors.bg, onBackground = colors.ink,
            surface = colors.bgElevated, onSurface = colors.ink,
            surfaceVariant = colors.activeBg, onSurfaceVariant = colors.sub,
            error = colors.red, outline = colors.border
        )
    } else {
        lightColorScheme(
            primary = colors.green, onPrimary = Color.White,
            background = colors.bg, onBackground = colors.ink,
            surface = colors.bgElevated, onSurface = colors.ink,
            surfaceVariant = colors.activeBg, onSurfaceVariant = colors.sub,
            error = colors.red, outline = colors.border
        )
    }
    CompositionLocalProvider(LocalHsuColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = hsuTypography(colors), shapes = HsuShapes, content = content)
    }
}
