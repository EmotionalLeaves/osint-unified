package com.osint.unified

import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.Hostnames
import com.osint.unified.domain.Identify
import com.osint.unified.domain.ObservableExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worst defect this project has had, and the shape that caused it.
 *
 * `\b(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}\b`
 * was written in five files. It is the textbook hostname pattern and
 * `java.util.regex` repeats a *group* recursively — one stack frame
 * per label — so two thousand labels throw StackOverflowError. The
 * scan ran during composition, on the main thread, over output
 * straight out of the database, so four kilobytes of dotted labels
 * in any stored result crashed the app every time that case was
 * opened, permanently, from content a subject could choose.
 *
 * These tests would all have failed before the rewrite, and three
 * of them by crashing the test runner rather than by failing an
 * assertion, which is the point.
 */
class HostnamesTest {

    /** Four kilobytes of dotted labels, which is the trigger. */
    private val hostile = "a.".repeat(12_000)

    @Test
    fun aLongRunOfDottedLabelsDoesNotOverflowTheStack() {
        // No assertion about the result — the test is that it returns.
        ObservableExtractor.extract(hostile, exclude = "")
    }

    @Test
    fun theSameRunDoesNotOverflowTheEntityExtractor() {
        EntityExtractor.extract(
            listOf(
                com.osint.unified.data.db.Result(
                    id = 1,
                    investigationId = 1,
                    toolId = "crtsh",
                    toolName = "crt.sh",
                    input = "example.com",
                    status = "success",
                    rawOutput = hostile,
                    startedAt = 0L,
                    finishedAt = 1L
                )
            ),
            seed = "example.com"
        )
    }

    @Test
    fun aLongPasteIntoTheDecoderDoesNotOverflowTheStack() {
        Identify.of(hostile)
    }

    /**
     * And it finishes quickly. The email pattern did not overflow;
     * it was quadratic, at sixty-one seconds over the scan cap,
     * because every starting position consumed the rest of the text
     * looking for an `@`.
     */
    @Test
    fun scanningIsLinearEnoughToRunOnTheMainThread() {
        val big = "a.".repeat(49_152)
        val started = System.currentTimeMillis()
        ObservableExtractor.extract(big, exclude = "")
        val took = System.currentTimeMillis() - started
        assertTrue("took ${took}ms over 96 KB", took < 2_000)
    }

    // ---- and it still does the job it was doing ----

    @Test
    fun realHostsAndAddressesAreStillFound() {
        val found = ObservableExtractor.extract(
            "see mail.example.co.uk and write to jane.doe+tag@sub.example.com.",
            exclude = ""
        )
        assertTrue(found.toString(), "mail.example.co.uk" in found)
        assertTrue(found.toString(), "jane.doe+tag@sub.example.com" in found)
    }

    @Test
    fun theStructureOfAHostnameIsChecked() {
        assertTrue(Hostnames.isHostname("example.com"))
        assertTrue(Hostnames.isHostname("a-b.example.co.uk"))
        assertFalse("a bare word is not a host", Hostnames.isHostname("example"))
        assertFalse("doubled dot", Hostnames.isHostname("a..b.com"))
        assertFalse("leading hyphen in a label", Hostnames.isHostname("-a.example.com"))
        assertFalse("trailing hyphen in a label", Hostnames.isHostname("a-.example.com"))
        assertFalse("trailing dot", Hostnames.isHostname("example.com."))
        assertFalse("digits are not a TLD", Hostnames.isHostname("1.2.3.4"))
        assertFalse("a label over 63", Hostnames.isHostname("${"a".repeat(64)}.com"))
    }

    @Test
    fun anAddressNeedsBothHalves() {
        assertTrue(Hostnames.isEmail("jane@example.com"))
        assertTrue(Hostnames.isEmail("jane.doe+tag@sub.example.co.uk"))
        assertFalse(Hostnames.isEmail("@example.com"))
        assertFalse(Hostnames.isEmail("jane@"))
        assertFalse(Hostnames.isEmail("jane@example"))
        assertFalse(Hostnames.isEmail(".jane@example.com"))
    }

    /** The guard ObservableExtractor had and Identify did not. */
    @Test
    fun aFileNameIsNotOfferedAsADomain() {
        assertEquals(
            emptyList<String>(),
            ObservableExtractor.extract("see config.yaml and index.html", exclude = "")
        )
        val guesses = Identify.of("config.yaml").map { it.label }
        assertFalse(guesses.toString(), "Domain name" in guesses)
    }
}
