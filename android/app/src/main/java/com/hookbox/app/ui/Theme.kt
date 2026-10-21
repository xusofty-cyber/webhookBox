package com.hookbox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 深色色板（原有品牌色，保持不变）
val Bg = Color(0xFF0E1116)
val Surface1 = Color(0xFF171C24)
val Surface2 = Color(0xFF1E2530)
val Stroke = Color(0xFF262E3A)
val TextPrimary = Color(0xFFE6EAF0)
val TextMuted = Color(0xFF8B95A5)
val Accent = Color(0xFF5DCAA5)
val AccentDim = Color(0xFF1D9E75)
val Danger = Color(0xFFE24B4A)
val Warn = Color(0xFFEF9F27)

// 浅色色板：跟随系统，户外/强光下也能看清
val BgLight = Color(0xFFF6F7F9)
val Surface1Light = Color(0xFFFFFFFF)
val Surface2Light = Color(0xFFECEFF3)
val StrokeLight = Color(0xFFD9DEE5)
val TextPrimaryLight = Color(0xFF161B22)
val TextMutedLight = Color(0xFF5C6672)

private val HookBoxDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04342C),
    secondary = Color(0xFF7F77DD),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    outline = Stroke,
    error = Danger,
)

private val HookBoxLightColors = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
    secondary = Color(0xFF6558D3),
    background = BgLight,
    onBackground = TextPrimaryLight,
    surface = Surface1Light,
    onSurface = TextPrimaryLight,
    surfaceVariant = Surface2Light,
    onSurfaceVariant = TextMutedLight,
    outline = StrokeLight,
    error = Danger,
)

/** 全 App 统一字阶，页面里不再手写 sp 数值各行其是 */
private val HookBoxTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HookBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) HookBoxDarkColors else HookBoxLightColors,
        typography = HookBoxTypography,
        content = content,
    )
}

/** level 对应的语义色，深浅色下都可读 */
@Composable
fun levelColor(level: String): Color = when (level) {
    "error" -> Danger
    "warn" -> Warn
    else -> MaterialTheme.colorScheme.primary
}

fun levelLabel(level: String): String = when (level) {
    "error" -> "错误"
    "warn" -> "警告"
    else -> "信息"
}
