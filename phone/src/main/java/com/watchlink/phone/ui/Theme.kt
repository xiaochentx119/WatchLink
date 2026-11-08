package com.watchlink.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WatchGreen = Color(0xFF3DDC97)
val WatchAmber = Color(0xFFFFC55C)
val WatchRed = Color(0xFFFF6B6B)
val WatchBlue = Color(0xFF7FB2FF)
val Ink = Color(0xFF0B0F17)
val Surface1 = Color(0xFF121826)
val Surface2 = Color(0xFF1B2334)
val TextPrimary = Color(0xFFE8EEF7)
val TextSecondary = Color(0xFF9AA8BF)

private val DarkColors = darkColorScheme(
    primary = WatchGreen,
    onPrimary = Color(0xFF04130C),
    secondary = WatchBlue,
    onSecondary = Color(0xFF061225),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF2A3548),
    error = WatchRed
)

@Composable
fun WatchLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
