package com.osint.unified

import com.osint.unified.domain.executor.DeepApis
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date a chronolocation tool is given.
 *
 * Both tools are documented as testing whether a photograph matches
 * its claimed time and place, and until now neither could be told the
 * time: the input parser read two comma-separated fields and dropped
 * the rest. The risk in loosening it is reading a year out of
 * something that is not a date, so the pattern is tested from both
 * directions.
 */
class ChronoInputTest {

    private val apis = DeepApis()

    private fun utc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))

    @Test
    fun aDateAloneIsMidnightUtc() {
        val m = apis.parseWhen("51.5074, -0.1278, 2026-03-02")
        requireNotNull(m)
        assertEquals("2026-03-02 00:00:00", utc(m.instant))
        assertFalse("no time was given", m.hasTime)
    }

    @Test
    fun aDateAndTimeAreTakenAsUtcWhenNoZoneIsGiven() {
        val m = apis.parseWhen("51.5074, -0.1278, 2026-03-02 14:30")
        requireNotNull(m)
        assertEquals("2026-03-02 14:30:00", utc(m.instant))
        assertTrue(m.hasTime)
    }

    @Test
    fun theTSeparatorAndSecondsBothWork() {
        val m = apis.parseWhen("0,0,2026-03-02T14:30:09")
        requireNotNull(m)
        assertEquals("2026-03-02 14:30:09", utc(m.instant))
    }

    @Test
    fun anExplicitOffsetIsApplied() {
        val plus = apis.parseWhen("0,0,2026-03-02T14:30+02:00")
        requireNotNull(plus)
        assertEquals("2026-03-02 12:30:00", utc(plus.instant))

        val minus = apis.parseWhen("0,0,2026-03-02T14:30-0500")
        requireNotNull(minus)
        assertEquals("2026-03-02 19:30:00", utc(minus.instant))

        val zulu = apis.parseWhen("0,0,2026-03-02T14:30Z")
        requireNotNull(zulu)
        assertEquals("2026-03-02 14:30:00", utc(zulu.instant))
    }

    /**
     * The failure that would be worse than having no date at all:
     * answering confidently about a day nobody asked for.
     */
    @Test
    fun aCoordinateAloneCarriesNoDate() {
        assertNull(apis.parseWhen("51.5074, -0.1278"))
        assertNull(apis.parseWhen("-33.8688, 151.2093"))
        // A year-like run of digits inside the decimals is not a date.
        assertNull(apis.parseWhen("20.26031, -12.02150"))
        assertNull(apis.parseWhen(""))
    }

    @Test
    fun animpossibleDateIsNotADate() {
        assertNull(apis.parseWhen("0,0,2026-13-02"))
        assertNull(apis.parseWhen("0,0,2026-03-40"))
        assertNull(apis.parseWhen("0,0,2026-03-02T25:00"))
        assertNull(apis.parseWhen("0,0,2026-03-02T14:77"))
    }
}
