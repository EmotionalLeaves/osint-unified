package com.osint.unified.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

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

    /**
     * RFC 1123 without the weekday.
     *
     * java.time's own RFC_1123_DATE_TIME insists the weekday agree with the
     * date, and third-party feeds get that wrong — one stale "Wed" on a
     * Thursday and java.time refuses the whole timestamp. Since the filter
     * now treats undated items as not current, a refused timestamp would
     * silently empty a layer over a detail nothing depends on. The weekday
     * is dropped before parsing, and both the "GMT" and "+0000" spellings
     * of the zone are accepted.
     */
    private val rfc1123: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy HH:mm[:ss]")
        .optionalStart()
        .appendLiteral(' ')
        .optionalStart().appendOffset("+HHMM", "GMT").optionalEnd()
        .optionalStart().appendZoneOrOffsetId().optionalEnd()
        .optionalEnd()
        .toFormatter(Locale.US)

    private val weekdayPrefix = Regex("^[A-Za-z]{3,9},\\s*")

    private val isoLike: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        // The fraction is optional on purpose. The IOC's tide gauges
        // send "2026-10-23 13:09:00.000" and several other feeds send
        // the same stamp without it; a formatter that takes only one
        // of those shapes silently dates every reading as unknown,
        // and a layer whose timestamps are all unknown is a layer
        // that gets filtered away as stale.
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss[.SSS]", Locale.US),
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    /** Epoch millis for a timestamp in any of the shapes these feeds use. */
    fun parse(raw: String?): Long? {
        val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null

        isoLike.forEach { fmt ->
            val parsed = runCatching {
                when (fmt) {
                    DateTimeFormatter.ISO_INSTANT -> Instant.from(fmt.parse(t)).toEpochMilli()
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME ->
                        OffsetDateTime.parse(t, fmt).toInstant().toEpochMilli()
                    DateTimeFormatter.ISO_LOCAL_DATE ->
                        LocalDate.parse(t, fmt).atStartOfDay(ZoneOffset.UTC)
                            .toInstant().toEpochMilli()
                    else ->
                        LocalDateTime.parse(t, fmt).toInstant(ZoneOffset.UTC).toEpochMilli()
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }

        val withoutDay = t.replaceFirst(weekdayPrefix, "")
        runCatching {
            return ZonedDateTime.parse(withoutDay, rfc1123).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(withoutDay, rfc1123)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
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

    /**
     * "in 3h", "in 12d" — the other direction, for things scheduled.
     *
     * Most of this app reports what has happened, so [ago] returns
     * null for a future timestamp rather than inventing a negative
     * age. A launch window is the exception: it has not happened yet
     * and that is the whole point of it.
     */
    fun until(millis: Long?): String? {
        if (millis == null) return null
        val minutes = (millis - System.currentTimeMillis()) / 60_000L
        return when {
            minutes < 0 -> null
            minutes < 60 -> "in ${minutes}m"
            minutes < 48 * 60 -> "in ${minutes / 60}h"
            else -> "in ${minutes / 1440}d"
        }
    }

    /** "undated" rather than nothing, so a missing timestamp is visible in
     *  the label instead of looking like a fresh item. */
    fun agoOrUndated(millis: Long?): String = ago(millis) ?: "undated"
}
