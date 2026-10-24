package com.osint.unified

import com.osint.unified.domain.DecoderEngine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a Unix timestamp.
 *
 * The decoder used to be one line: multiply by a thousand if the
 * number looks like seconds, hand it to Date, print whatever came
 * back. That answered every input with a date, including inputs
 * that are not timestamps, and it answered in the device's own
 * zone with nothing saying so — in the one field of an
 * investigation where the zone decides whether two events are the
 * same event.
 */
class DecoderTimestampTest {

    private fun ts(v: String) = DecoderEngine.run("ts", v)

    @Test
    fun secondsAreReadAsSeconds() {
        val out = ts("1700000000")
        assertTrue(out, out.contains("2023-11-14"))
        assertTrue(out, out.contains("UTC"))
        assertTrue(out, out.contains("Read as seconds"))
    }

    @Test
    fun millisecondsAreReadAsMilliseconds() {
        val out = ts("1700000000000")
        assertTrue(out, out.contains("2023-11-14"))
        assertTrue(out, out.contains("Read as milliseconds"))
    }

    @Test
    fun aDateBeforeTheEpochIsADate() {
        // Negative seconds used to go through the multiply and
        // overflow into a date somewhere else entirely.
        val out = ts("-1000000000")
        assertTrue(out, out.contains("1938-"))
    }

    @Test
    fun whatIsNotATimestampIsNotGivenADate() {
        listOf(
            "99999999999999999",
            "-9000000000000000000",
            "5551234567890123"
        ).forEach { assertTrue(it, ts(it).startsWith("Out of range")) }
        assertTrue(ts("not a number").startsWith("Not a number"))
    }

    @Test
    fun theZoneIsAlwaysStated() {
        val out = ts("0")
        assertTrue(out, out.contains("1970-01-01 00:00:00 UTC"))
        assertTrue(out, out.contains("Local:"))
    }
}
