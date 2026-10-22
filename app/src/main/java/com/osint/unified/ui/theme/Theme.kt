package com.osint.unified.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app is a tactical intel console — always dark, regardless of the system
 * setting. Signal cyan is the single interactive accent; amber is the alert.
 */
private val ConsoleColors = darkColorScheme(
    primary = Intel.Cyan,
    onPrimary = Intel.Base,
    primaryContainer = Intel.Elevated,
    onPrimaryContainer = Intel.CyanBright,
    secondary = Intel.Amber,
    onSecondary = Intel.Base,
    secondaryContainer = Intel.Elevated,
    onSecondaryContainer = Intel.Amber,
    background = Intel.Base,
    onBackground = Intel.TextHi,
    surface = Intel.Surface,
    onSurface = Intel.TextHi,
    surfaceVariant = Intel.Elevated,
    onSurfaceVariant = Intel.TextMid,
    error = Intel.Danger,
    onError = Intel.Base,
    outline = Intel.Hairline,
    outlineVariant = Intel.Hairline
)

@Composable
fun OsintTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ConsoleColors,
        typography = IntelTypography,
        content = content
    )
}
