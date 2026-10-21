package com.hookbox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

private val HookBoxColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04342C),
    secondary = Color(0xFF7F77DD),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    error = Danger,
)

@Composable
fun HookBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HookBoxColors, content = content)
}
