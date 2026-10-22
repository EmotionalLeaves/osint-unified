package com.osint.unified.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How recent a feed item is.
 *
 * A live map should show what is happening, not what once happened. Several
 * feeds keep an item "open" long after it stops mattering — EONET wildfires
 * stay open for months — so items are filtered by age and labelled with it.
 * Showing a four-month-old fire as a current one is the map lying.
 */
object Freshness {

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd"
    )

    /** Epoch millis for a timestamp in any of the shapes these feeds use. */
    fun parse(raw: String?): Long? {
        val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        formats.forEach { pattern ->
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.isLenient = true
                return fmt.parse(t)?.time ?: return@runCatching
            }
        }
        return null
    }

    /** True when [millis] is within [hours] of now, or unknown. An item with
     *  no timestamp is kept — dropping it would hide a live feed that simply
     *  does not date its entries. */
    fun within(millis: Long?, hours: Int): Boolean {
        if (millis == null) return true
        val age = System.currentTimeMillis() - millis
        return age in -6 * 3600_000L..hours * 3600_000L
    }

    /** "3h ago", "2d ago" — appended to labels so staleness is visible. */
    fun ago(millis: Long?): String? {
        if (millis == null) return null
        val minutes = (System.currentTimeMillis() - millis) / 60_000L
        return when {
            minutes < 0 -> null
            minutes < 60 -> "${minutes}m ago"
            minutes < 48 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }
}
