package com.osint.unified.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * How recent a feed item is.
 *
 * A live map should show what is happening, not what once happened. Several
 * feeds keep an item "open" long after it stops mattering — EONET wildfires
 * stay open for months — so items are filtered by age and labelled with it.
 * Showing a four-month-old fire as a current one is the map lying.
 *
 * Parsing is java.time rather than SimpleDateFormat. The old patterns
 * matched by prefix, so "2026-10-22T09:15:01-05:00" parsed as if the
 * offset were not there and came out five hours wrong — which on a
 * two-hour METAR window is the difference between current and discarded.
 * Six formatters were also being allocated per call, across tens of
 * thousands of items.
 */
object Freshness {

    private val patterns: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    /** Epoch millis for a timestamp in any of the shapes these feeds use. */
    fun parse(raw: String?): Long? {
        val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        patterns.forEach { fmt ->
            val parsed = runCatching {
                when (fmt) {
                    DateTimeFormatter.ISO_INSTANT -> Instant.from(fmt.parse(t)).toEpochMilli()
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                    DateTimeFormatter.RFC_1123_DATE_TIME ->
                        OffsetDateTime.parse(t, fmt).toInstant().toEpochMilli()
                    DateTimeFormatter.ISO_LOCAL_DATE ->
                        LocalDate.parse(t, fmt).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    else ->
                        LocalDateTime.parse(t, fmt).toInstant(ZoneOffset.UTC).toEpochMilli()
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * True when [millis] is within [hours] of now.
     *
     * An item whose timestamp could not be read used to be kept, on the
     * reasoning that a feed which does not date its entries should not
     * vanish. But this is the test that keeps a four-month-old fire off a
     * live map, and failing it open means an undated item is shown as
     * current — which is the thing the test exists to prevent. Callers that
     * genuinely have undated feeds pass [keepUndated].
     */
    fun within(millis: Long?, hours: Int, keepUndated: Boolean = false): Boolean {
        if (millis == null) return keepUndated
        val age = System.currentTimeMillis() - millis
        // A little slack for feeds whose clocks run ahead of the device's.
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

    /** "undated" rather than nothing, so a missing timestamp is visible in
     *  the label instead of looking like a fresh item. */
    fun agoOrUndated(millis: Long?): String = ago(millis) ?: "undated"
}
