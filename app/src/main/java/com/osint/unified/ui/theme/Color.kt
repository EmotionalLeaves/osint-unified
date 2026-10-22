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

    /**
     * The dimmest text in the app, and it has to stay readable.
     *
     * #5E7383 measured 4.00:1 on the base, 3.69:1 on panels and 3.31:1 on
     * raised rows — all under the 4.5:1 that ordinary text needs — and it
     * was carrying result headlines, hints, off-state pills and the line
     * that says a feed reported nothing. This value is the darkest that
     * still clears 4.5:1 against the lightest surface it is drawn on
     * (4.53 on Elevated, 5.05 on Surface, 5.47 on Base), so none of it is
     * lost in sunlight.
     */
    val TextLow = Color(0xFF778999)

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
