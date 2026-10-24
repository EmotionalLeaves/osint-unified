package com.osint.unified

import com.osint.unified.data.DiskCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cache under the two feeds that are seven and fourteen
 * megabytes.
 *
 * T15 named "a DiskCache round trip against a truncated file" as
 * one of four cheap, high-value tests, marked it done, and shipped
 * three — in a file called DiskCacheAndFindingsTest that held no
 * DiskCache test. So the atomic write, the age test and the
 * clock-moved-backwards guard were all written, reviewed twice,
 * and never once executed.
 */
class DiskCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache() = DiskCache(folder.root)

    @Test
    fun whatGoesInComesBack() {
        val c = cache()
        assertTrue(c.write("quakes", "x".repeat(200)))
        assertEquals("x".repeat(200), c.read("quakes", 60_000L))
        assertEquals("x".repeat(200), c.readStale("quakes"))
        assertNull(c.trouble.value)
    }

    @Test
    fun nothingWrittenIsNotSomethingRead() {
        val c = cache()
        assertNull(c.read("never", 60_000L))
        assertNull(c.readStale("never"))
        assertNull(c.ageMillis("never"))
    }

    /**
     * The defect the atomic write exists for: `writeText` truncates
     * and then writes, so a process killed partway left a short
     * file that passed the 64-byte test and was served for ever.
     */
    @Test
    fun aTruncatedFileIsNotServedAsAnAnswer() {
        val c = cache()
        c.write("stations", "y".repeat(500))
        // Shorter than the floor, which is what a kill at the start
        // of a seven-megabyte write leaves behind.
        File(folder.root, "feed-stations").writeText("y".repeat(10))
        assertNull(c.read("stations", 60_000L))
        assertNull(c.readStale("stations"))
    }

    /** No partial file is left lying about after a successful write. */
    @Test
    fun theTemporaryFileDoesNotSurvive() {
        val c = cache()
        c.write("sats", "z".repeat(100))
        assertFalse(File(folder.root, "feed-sats.tmp").exists())
        assertEquals(
            listOf("feed-sats"),
            folder.root.listFiles().orEmpty().map { it.name }.sorted()
        )
    }

    /**
     * A clock that has moved backwards makes the age negative, the
     * `age > maxAge` test never fires, and a stale entry is served
     * as fresh for ever — in the function whose whole job is
     * deciding freshness.
     */
    @Test
    fun aClockThatWentBackwardsDoesNotMakeEverythingFresh() {
        val c = cache()
        c.write("tomorrow", "w".repeat(100))
        val f = File(folder.root, "feed-tomorrow")
        f.setLastModified(System.currentTimeMillis() + 86_400_000L)
        assertEquals(0L, c.ageMillis("tomorrow"))
        // Still fresh, which is the right answer for a file from the
        // future: it is not old. What must not happen is the age
        // arithmetic going negative and the comparison losing its
        // meaning.
        assertEquals("w".repeat(100), c.read("tomorrow", 60_000L))
    }

    @Test
    fun anOldEntryIsStaleButStillThere() {
        val c = cache()
        c.write("old", "v".repeat(100))
        File(folder.root, "feed-old").setLastModified(System.currentTimeMillis() - 86_400_000L)
        assertNull(c.read("old", 60_000L))
        assertEquals("v".repeat(100), c.readStale("old"))
        assertTrue((c.ageMillis("old") ?: 0L) > 80_000_000L)
    }

    /**
     * The whole reason `write` stopped returning Unit: an
     * unwritable directory used to be invisible, and every caller
     * believed it had cached.
     */
    @Test
    fun anUnwritableCacheSaysSoRatherThanPretending() {
        val nested = File(folder.root, "gone/deeper")
        val c = DiskCache(nested)
        assertFalse(c.write("anything", "u".repeat(100)))
        assertTrue(c.trouble.value != null)
        // And it clears when the cache works again.
        nested.mkdirs()
        assertTrue(c.write("anything", "u".repeat(100)))
        assertNull(c.trouble.value)
    }
}
