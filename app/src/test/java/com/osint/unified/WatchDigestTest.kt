package com.osint.unified

import com.osint.unified.data.WatchDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDigestTest {

    /**
     * The failure that would ruin the feature: a source that stamps its
     * answer with the current time looks different on every check, so every
     * check reports a change, so the alerts mean nothing.
     */
    @Test
    fun aResponseThatOnlyDiffersByItsTimestampIsNotAChange() {
        val a = "Breach exposure — a@b.com\nGenerated 2026-10-22T09:15:01Z\nFound in 3 breaches"
        val b = "Breach exposure — a@b.com\nGenerated 2026-10-22T19:47:55.21Z\nFound in 3 breaches"
        assertEquals(WatchDigest.normalise(a), WatchDigest.normalise(b))
    }

    @Test
    fun relativeAgesAndEpochSecondsAreStrippedToo() {
        assertEquals(
            WatchDigest.normalise("Flood alert · ORANGE · 2 hours ago"),
            WatchDigest.normalise("Flood alert · ORANGE · 9 hours ago")
        )
        assertEquals(
            WatchDigest.normalise("retrieved at 1792683655 ok"),
            WatchDigest.normalise("retrieved at 1792699999 ok")
        )
    }

    /** Stripping noise must not strip the signal. */
    @Test
    fun aRealDifferenceSurvivesNormalisation() {
        val before = "Found in 3 public breaches:\nAdobe, LinkedIn, Dropbox"
        val after = "Found in 4 public breaches:\nAdobe, LinkedIn, Dropbox, Canva"
        assertNotEquals(WatchDigest.normalise(before), WatchDigest.normalise(after))
    }

    @Test
    fun whitespaceAloneIsNeverAChange() {
        assertEquals(
            WatchDigest.normalise("one\n\n  two   three"),
            WatchDigest.normalise("one two three")
        )
    }

    @Test
    fun nothingChangedMeansNothingIsSaid() {
        assertNull(WatchDigest.describe("x.com", emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun appearingAndVanishingAreDistinguished() {
        val d = WatchDigest.describe("x.com", listOf("crtsh"), listOf("rdap"), listOf("dns"))
        assertTrue(d!!.startsWith("x.com — "))
        assertTrue(d.contains("now found by"))
        assertTrue(d.contains("no longer found by"))
        assertTrue(d.contains("changed at"))
    }

    /** An unknown id still has to be named, not silently dropped. */
    @Test
    fun anUnregisteredToolIdIsStillReported() {
        val d = WatchDigest.describe("x.com", listOf("not-a-real-tool"), emptyList(), emptyList())
        assertTrue(d!!.contains("not-a-real-tool"))
    }
}
