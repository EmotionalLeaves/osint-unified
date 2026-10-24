package com.osint.unified

import com.osint.unified.data.WatchDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
        // Spacing within a line, and blank lines, are formatting.
        assertEquals(
            WatchDigest.normalise("one\n\n  two   three  "),
            WatchDigest.normalise("one\ntwo three")
        )
        // A line break between two tokens is NOT the same as a space,
        // and this test used to say it was — which was a side effect
        // of collapsing the whole answer onto one line, and is the
        // thing that had to go for a reordered answer to stop
        // reading as a change. The line is the unit now, so what is
        // on a line matters and the order of the lines does not.
        assertNotEquals(
            WatchDigest.normalise("one\ntwo"),
            WatchDigest.normalise("one two")
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
    /**
     * The whole point of a watch: not that something changed, but
     * what.
     *
     * Every event used to read "changed at Shodan", because the check
     * reduced each answer to a hash and threw the rest away — and
     * runTool replaces the previous row, so there was nothing left to
     * compare against afterwards either.
     */
    @Test
    fun aChangeNamesWhatChanged() {
        val d = WatchDigest.describe(
            "example.com",
            appeared = emptyList(),
            vanished = emptyList(),
            altered = listOf("crtsh"),
            gained = listOf("HOST:mail2.example.com", "HOST:vpn.example.com"),
            lost = listOf("HOST:old.example.com")
        )
        requireNotNull(d)
        assertTrue(d, d.contains("mail2.example.com"))
        assertTrue(d, d.contains("vpn.example.com"))
        assertTrue(d, d.contains("old.example.com"))
        assertTrue(d, d.contains("new:"))
        assertTrue(d, d.contains("gone:"))
        // The kind prefix is for the diff, not for the reader.
        assertFalse(d, d.contains("HOST:"))
    }

    /** A long list is counted rather than claimed to be complete. */
    @Test
    fun aLongListSaysHowManyItIsNotShowing() {
        val many = (1..9).map { "HOST:h$it.example.com" }
        val d = WatchDigest.describe(
            "example.com", emptyList(), emptyList(), listOf("crtsh"), gained = many
        )
        requireNotNull(d)
        assertTrue(d, d.contains("and 5 more"))
    }

    /** And with nothing extracted it reads exactly as it always did. */
    @Test
    fun withNoDetailTheOldSentenceIsUnchanged() {
        val d = WatchDigest.describe("x.com", listOf("crtsh"), emptyList(), emptyList())
        requireNotNull(d)
        assertFalse(d, d.contains("new:"))
        assertTrue(d, d.contains("now found by"))
    }

    /**
     * The order a source prints its answer in is not a fact about
     * the target.
     *
     * crt.sh and the subdomain sweeps do not promise one, so a
     * reordered answer used to change the fingerprint and raise an
     * alert saying something had changed when nothing had — and,
     * once the check remembered identifiers, it named hosts as new
     * and gone that had been there throughout.
     */
    @Test
    fun aReorderedAnswerIsNotAChange() {
        assertEquals(
            WatchDigest.normalise("a.example.com\nb.example.com\nc.example.com"),
            WatchDigest.normalise("c.example.com\na.example.com\nb.example.com")
        )
    }

    @Test
    fun arealChangeIsStillAChange() {
        assertNotEquals(
            WatchDigest.normalise("a.example.com\nb.example.com"),
            WatchDigest.normalise("a.example.com\nb.example.com\nc.example.com")
        )
    }

}
