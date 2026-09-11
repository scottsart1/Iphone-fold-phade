package dev.foldphase.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFF9FD8C4),
    background = Color(0xFF0C0E12),
    surface = Color(0xFF14171D),
    surfaceVariant = Color(0xFF1D2129),
    onBackground = Color(0xFFE4E7EC),
    onSurface = Color(0xFFE4E7EC),
    error = Color(0xFFEF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5FB8),
    secondary = Color(0xFF2E7D67),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

/** Monospaced style for every numeric readout, so digits do not jitter as values change. */
val MonoNumber = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
)

@Composable
fun FoldPhaseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
