package com.panopticon.phoneapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette lifted directly from docs/design/ux-mocks/phone-ux-mock.html's `:root` CSS variables -
// this project's ground truth for the visual language. Dark near-black/teal theme.
object PanopticonColors {
    val bg = Color(0xFF0B1210)
    val frame = Color(0xFF12312C)
    val frameHi = Color(0xFF1C4740)
    val screen = Color(0xFF0D1714)
    val surface = Color(0xFF132420)
    val surface2 = Color(0xFF1A332C)
    val border = Color(0xFF264238)
    val borderSoft = Color(0xFF1C332B)
    val text = Color(0xFFE9F3EE)
    val textDim = Color(0xFF8FADA0)
    val textFaint = Color(0xFF5C7C70)
    val accent = Color(0xFF4FE3C9)
    val accentDim = Color(0xFF2A6B5F)
    val accentInk = Color(0xFF04211B)
    val rec = Color(0xFFFF5F56)
    val recDim = Color(0xFF7A2B27)
    val warn = Color(0xFFE8B339)
}

private val PanopticonDarkScheme = darkColorScheme(
    background = PanopticonColors.bg,
    surface = PanopticonColors.surface,
    surfaceVariant = PanopticonColors.surface2,
    primary = PanopticonColors.accent,
    onPrimary = PanopticonColors.accentInk,
    onBackground = PanopticonColors.text,
    onSurface = PanopticonColors.text,
    outline = PanopticonColors.border,
    error = PanopticonColors.rec,
)

@Composable
fun PanopticonTheme(content: @Composable () -> Unit) {
    // Always dark - this app has no light theme in the mock.
    MaterialTheme(
        colorScheme = PanopticonDarkScheme,
        content = content,
    )
}
