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
            "Wed, 22 Oct 2026 09:30:00 GMT",
            "2026-10-22"
        ).forEach { assertNotNull("could not parse $it", Freshness.parse(it)) }
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

    @Test
    fun anUndatedItemIsKept() {
        // Dropping items a feed simply does not date would empty a live
        // layer, which is a worse failure than showing one without an age.
        assertTrue(Freshness.within(null, 1))
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
