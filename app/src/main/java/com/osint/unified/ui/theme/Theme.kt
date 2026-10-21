package com.osint.unified.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF004A6B),
    onPrimaryContainer = Color(0xFFCDE8FF),
    secondary = Color(0xFF8BD0B0),
    onSecondary = Color(0xFF003823),
    secondaryContainer = Color(0xFF0F4E34),
    onSecondaryContainer = Color(0xFFA7EFCB),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF12161B),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF1C222A),
    onSurfaceVariant = Color(0xFFB9C2CC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF3A434D)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF186B45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F2C6),
    onSecondaryContainer = Color(0xFF002112),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF42474E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF72787E)
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
