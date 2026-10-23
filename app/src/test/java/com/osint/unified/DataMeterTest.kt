package com.osint.unified

import com.osint.unified.network.DataMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DataMeterTest {

    @Before
    fun clear() = DataMeter.reset()

    @Test
    fun itAddsUpPerHostAndInTotal() {
        DataMeter.add("a.example", 1_000)
        DataMeter.add("b.example", 3_000)
        DataMeter.add("a.example", 500)
        assertEquals(4_500L, DataMeter.bytes())
        assertEquals(listOf("b.example" to 3_000L, "a.example" to 1_500L), DataMeter.worst())
    }

    /** A zero-length or failed read must not move the figure. */
    @Test
    fun nothingReadIsNothingSpent() {
        DataMeter.add("a.example", 0)
        DataMeter.add("a.example", -1)
        assertEquals(0L, DataMeter.bytes())
        assertTrue(DataMeter.worst().isEmpty())
    }

    /**
     * The figure is for orientation, so it rounds — but it must not
     * round a megabyte down to nothing or print a float at the user.
     *
     * Run under a locale that uses a decimal comma, because the
     * formatter used the device's locale and "4,2 MB" reads as four
     * thousand two hundred to anyone not expecting it.
     */
    @Test
    fun theFigureReadsLikeSomethingAPersonWouldSay() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.8 MB", DataMeter.human(1_750_000))
        } finally {
            java.util.Locale.setDefault(was)
        }
        assertEquals("512 B", DataMeter.human(512))
        assertEquals("2 kB", DataMeter.human(1_999))
        assertEquals("1.8 MB", DataMeter.human(1_750_000))
        assertEquals("2.5 GB", DataMeter.human(2_500_000_000L))
    }

    /** Resetting restarts the clock as well as the counter, or the
     *  rate it feeds is the new byte count over the old elapsed time. */
    @Test
    fun resettingRestartsTheClock() {
        val before = DataMeter.since()
        Thread.sleep(5)
        DataMeter.reset()
        assertTrue(DataMeter.since() > before)
    }

    /** A dossier can touch hundreds of hosts; the breakdown is capped
     *  so it stays a diagnostic rather than becoming a ledger. */
    @Test
    fun theHostBreakdownIsBounded() {
        repeat(900) { DataMeter.add("host$it.example", 10) }
        assertEquals(9_000L, DataMeter.bytes())
        assertTrue(DataMeter.worst(1000).size <= 512)
        // And a host already being tracked keeps accruing past the cap.
        DataMeter.add("host0.example", 50_000)
        assertEquals("host0.example", DataMeter.worst(1).single().first)
    }
}
