package com.osint.unified.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00210B),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE2E2E6),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE2E2E6)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF1A1C1E),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E)
)

@Composable
fun OsintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
