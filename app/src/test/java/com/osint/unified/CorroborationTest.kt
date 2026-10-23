package com.osint.unified

import com.osint.unified.domain.Corroboration
import com.osint.unified.domain.Grading
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.data.db.Result as ResultRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part of the app that decides what a result means.
 *
 * Every OSINT framework puts source grading and corroboration at the
 * centre; this app had neither, and presented a certificate transparency
 * record and a username collision at identical weight.
 */
class CorroborationTest {

    private var next = 1L

    private fun row(toolId: String, output: String): ResultRow {
        val tool = ToolRegistry.byId(toolId)
        return ResultRow(
            id = next++,
            investigationId = 1,
            toolId = toolId,
            toolName = tool?.name ?: toolId,
            input = "example.com",
            status = "success",
            rawOutput = output,
            startedAt = 0L,
            finishedAt = 1L
        )
    }

    @Test
    fun everySourceIsGraded() {
        val ungraded = ToolRegistry.all.filter {
            it.reliability == Grading.Reliability.UNJUDGED
        }.map { it.id }
        // The four directories are deliberately ungraded: they are places
        // to look, not sources that said anything.
        assertEquals(
            listOf("framework", "websearch", "dorks", "toolrun").sorted(),
            ungraded.sorted()
        )
    }

    /** One witness is one witness, however good. */
    @Test
    fun aSingleAuthoritativeSourceIsProbableAndNeverConfirmed() {
        val entities = Corroboration.merge(
            listOf(row("crtsh", "mail.example.org\n")),
            seed = "example.com"
        )
        val host = entities.first { it.value == "mail.example.org" }
        assertEquals(Grading.Reliability.COMPLETE, host.reliability)
        assertEquals(Grading.Credibility.PROBABLE, host.credibility)
        assertEquals("A2", host.grade)
    }

    /** Two independent sources are what makes a finding. */
    @Test
    fun twoIndependentSourcesCorroborate() {
        val entities = Corroboration.merge(
            listOf(
                row("crtsh", "mail.example.org\n"),
                row("dns", "mail.example.org has address 1.2.3.4\n")
            ),
            seed = "example.com"
        )
        val host = entities.first { it.value == "mail.example.org" }
        assertEquals(2, host.independent)
        assertEquals(Grading.Credibility.CONFIRMED, host.credibility)
    }

    /**
     * Two tools reading the same upstream are one witness. Counting them
     * twice manufactures confidence out of nothing.
     */
    @Test
    fun toolsSharingAnUpstreamDoNotCorroborateEachOther() {
        val entities = Corroboration.merge(
            listOf(
                row("wayback", "http://mail.example.org/a\n"),
                row("gau", "http://mail.example.org/b\n"),
                row("waybackurls", "http://mail.example.org/c\n")
            ),
            seed = "example.com"
        )
        val host = entities.first { it.value == "mail.example.org" }
        assertEquals("three archive readers are one archive", 1, host.independent)
        assertTrue(host.credibility.code >= 2)
    }

    /** A name being taken on a site is a fact about the name. */
    @Test
    fun aUsernameSweepNeverProducesAConfirmedFinding() {
        val entities = Corroboration.merge(
            listOf(
                row("sherlock", "jdoe@example.org\n"),
                row("maigret", "jdoe@example.org\n"),
                row("blackbird", "jdoe@example.org\n")
            ),
            seed = "jdoe"
        )
        val found = entities.first { it.value == "jdoe@example.org" }
        assertEquals(Grading.Reliability.UNRELIABLE, found.reliability)
        assertEquals(1, found.independent)
        assertTrue("graded ${found.grade}", found.credibility.code >= 4)
    }

    /** Machinery is not a lead. */
    @Test
    fun roleMailboxesAndProxiesAndCdnsAreMarkedAsMachinery() {
        assertNotNull(Corroboration.noise("EMAIL", "abuse@example.com"))
        assertNotNull(Corroboration.noise("EMAIL", "no-reply@example.com"))
        assertNotNull(Corroboration.noise("EMAIL", "x@whoisguard.com"))
        assertNotNull(Corroboration.noise("HOST", "d1234.cloudfront.net"))
        assertNotNull(Corroboration.noise("HOST", "foo.s3.amazonaws.com"))
        assertNull(Corroboration.noise("EMAIL", "j.doe@example.com"))
        assertNull(Corroboration.noise("HOST", "mail.example.org"))
    }

    /** And it sorts below everything that is a lead. */
    @Test
    fun machinerySinksToTheBottom() {
        val entities = Corroboration.merge(
            listOf(row("dns", "abuse@example.org\nj.doe@example.org\n")),
            seed = "example.com"
        )
        val values = entities.map { it.value }
        assertTrue(
            "order was $values",
            values.indexOf("j.doe@example.org") < values.indexOf("abuse@example.org")
        )
    }
}
