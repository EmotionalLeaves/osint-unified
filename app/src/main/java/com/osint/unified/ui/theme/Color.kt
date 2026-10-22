package com.osint.unified.ui.theme

import androidx.compose.ui.graphics.Color
import com.osint.unified.domain.ToolCategory

/**
 * Tactical intel console palette. Deep slate base, signal cyan for anything
 * interactive/active, amber for alerts. Exposed as plain tokens so bespoke
 * console components (status dots, section rails, telemetry bar) can use the
 * exact colors without routing everything through the Material scheme.
 */
object Intel {
    val Base = Color(0xFF070B0F)       // app background
    val Surface = Color(0xFF0F161D)    // panels / cards
    val Elevated = Color(0xFF16212B)   // raised rows, chips
    val Hairline = Color(0xFF24323D)   // 1px borders / dividers

    val Cyan = Color(0xFF2FD4C8)       // primary signal / interactive
    val CyanBright = Color(0xFF5CE1D6)
    val Amber = Color(0xFFF4B740)      // secondary / alerts

    val TextHi = Color(0xFFE6EEF2)
    val TextMid = Color(0xFF8CA2B0)
    val TextLow = Color(0xFF5E7383)

    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF4FE0A3)

    // Category accents (also used for globe markers where relevant).
    val Violet = Color(0xFF9A8CFF)     // SOCMINT
    val NetCyan = Color(0xFF4FC3F7)    // NETINT
    val Teal = Color(0xFF2FD4C8)       // GEOINT
    val Gold = Color(0xFFF4B740)       // FININT
    val Coral = Color(0xFFFF8A6B)      // FORENSICS
}

fun categoryColor(category: ToolCategory): Color = when (category) {
    ToolCategory.SOCMINT -> Intel.Violet
    ToolCategory.NETINT -> Intel.NetCyan
    ToolCategory.GEOINT -> Intel.Teal
    ToolCategory.FININT -> Intel.Gold
    ToolCategory.FORENSICS -> Intel.Coral
}
