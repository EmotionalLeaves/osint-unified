package com.osint.unified

import com.osint.unified.data.Freshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liveness filter decides what reaches the map, so a parsing failure
 * here would quietly either hide live events or let months-old ones
 * through looking current.
 */
class FreshnessTest {

    private val hour = 3_600_000L

    @Test
    fun parsesTheShapesTheseFeedsActuallyUse() {
        // EONET, NWS, GDACS and the aviation feeds each date things
        // differently; all of them have to work.
        listOf(
            "2026-10-20T06:00:00Z",
            "2026-10-22T15:39:00.000Z",
            "2026-10-22 13:00:00",
            "Thu, 22 Oct 2026 09:30:00 GMT",
            "Thu, 22 Oct 2026 04:30:00 -0500",
            "2026-10-22"
        ).forEach { assertNotNull("could not parse $it", Freshness.parse(it)) }
    }

    /**
     * Feeds get the weekday wrong, and it must not cost them the timestamp.
     *
     * 22 October 2026 is a Thursday. java.time's own RFC 1123 parser
     * refuses the whole string over the mismatch — and since an undated
     * item is now treated as not current, that would quietly empty a layer
     * over a detail nothing depends on.
     */
    @Test
    fun aWrongWeekdayDoesNotCostTheTimestamp() {
        assertEquals(
            Freshness.parse("Thu, 22 Oct 2026 09:30:00 GMT"),
            Freshness.parse("Wed, 22 Oct 2026 09:30:00 GMT")
        )
    }

    @Test
    fun blankAndNonsenseParseToNothing() {
        assertNull(Freshness.parse(null))
        assertNull(Freshness.parse("   "))
        assertNull(Freshness.parse("last tuesday"))
    }

    @Test
    fun recentPasses_andStaleDoesNot() {
        val now = System.currentTimeMillis()
        assertTrue(Freshness.within(now - 2 * hour, 24))
        assertFalse(Freshness.within(now - 200 * hour, 24))
    }

    /**
     * An undated item is not current.
     *
     * The filter used to keep it, which meant the one test standing between
     * a four-month-old fire and a live map failed open whenever a timestamp
     * could not be read. A caller that genuinely has an undated feed asks
     * for that explicitly.
     */
    @Test
    fun anUndatedItemIsNotAssumedCurrent() {
        assertFalse(Freshness.within(null, 1))
        assertTrue(Freshness.within(null, 1, keepUndated = true))
        assertEquals("undated", Freshness.agoOrUndated(null))
    }

    /**
     * An offset is not decoration.
     *
     * SimpleDateFormat matched these by prefix and ignored the trailing
     * offset, so a timestamp five hours behind UTC parsed as UTC — enough
     * to push a current METAR outside its two-hour window, or to let a
     * stale one in.
     */
    @Test
    fun offsetsAreRespected() {
        val utc = Freshness.parse("2026-10-22T14:00:00Z")!!
        val minusFive = Freshness.parse("2026-10-22T09:00:00-05:00")!!
        val plusTwo = Freshness.parse("2026-10-22T16:00:00+02:00")!!
        assertEquals(utc, minusFive)
        assertEquals(utc, plusTwo)
    }

    @Test
    fun aSlightlyFutureTimestampIsKept() {
        // Forecast products and clock skew both put timestamps a little
        // ahead; those are live, not stale.
        val now = System.currentTimeMillis()
        assertTrue(Freshness.within(now + hour, 24))
        // Something days in the future is a broken feed, not a forecast.
        assertFalse(Freshness.within(now + 72 * hour, 24))
    }

    @Test
    fun agesReadTheWayAPersonWouldSayThem() {
        val now = System.currentTimeMillis()
        assertEquals("30m ago", Freshness.ago(now - 30 * 60_000L))
        assertEquals("5h ago", Freshness.ago(now - 5 * hour))
        assertEquals("3d ago", Freshness.ago(now - 72 * hour))
        assertNull(Freshness.ago(null))
    }
}
