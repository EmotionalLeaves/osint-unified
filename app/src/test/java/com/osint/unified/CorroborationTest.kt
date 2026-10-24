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
        // The three directories are deliberately ungraded: they are
        // places to look, not sources that said anything.
        //
        // websearch was the fourth, and it was a mistake of naming.
        // It fetches the DuckDuckGo instant-answer abstract and up to
        // eight Wikipedia articles with their snippets and emits no
        // search-engine links at all, so a fetching source was being
        // force-classified as an absence and graded F in six of the
        // twelve observable types. It is a C now — a third party
        // reporting what it holds.
        assertEquals(
            listOf("framework", "dorks", "toolrun").sorted(),
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

    /**
     * The investigator's judgement outranks the source's.
     *
     * The app grades what came back; it cannot know whether the host a
     * log asserted belongs to this target. Before this, saying so was
     * impossible and the same false lead returned at the same confidence
     * every time the case was reopened.
     */
    @Test
    fun aFindingMarkedWrongIsStruckAndSaysWhy() {
        val entities = Corroboration.merge(
            listOf(row("crtsh", "mail.example.org\n")),
            seed = "example.com",
            dismissed = mapOf(("HOST" to "mail.example.org") to "belongs to the hosting company")
        )
        val host = entities.first { it.value == "mail.example.org" }
        assertTrue(host.dismissed)
        assertEquals("you marked this wrong", host.caveat)
        assertEquals("belongs to the hosting company", host.dismissalReason)
        assertEquals(Grading.Credibility.DOUBTFUL, host.credibility)
    }

    /** Struck findings sort below everything, including the machinery. */
    @Test
    fun struckFindingsSinkToTheBottom() {
        val entities = Corroboration.merge(
            listOf(row("crtsh", "good.example.org\nbad.example.org\n")),
            seed = "example.com",
            dismissed = mapOf(("HOST" to "bad.example.org") to "")
        )
        assertEquals("bad.example.org", entities.last().value)
    }

    /**
     * One bad hit is an ordinary day; a pattern is evidence about the
     * source. A sweep caught being wrong twice, with nothing that held
     * up, is read one grade below its catalogue reliability for the rest
     * of the case.
     */
    @Test
    fun aSourceWrongTwiceWithNothingKeptIsDowngradedForTheCase() {
        val output = "a.example.org\nb.example.org\nc.example.org\n"
        val clean = Corroboration.merge(listOf(row("crtsh", output)), seed = "example.com")
        assertEquals(Grading.Reliability.COMPLETE, clean.first { it.value == "c.example.org" }.reliability)

        val marked = Corroboration.merge(
            listOf(row("crtsh", output)),
            seed = "example.com",
            dismissed = mapOf(
                ("HOST" to "a.example.org") to "",
                ("HOST" to "b.example.org") to ""
            )
        )
        val survivor = marked.first { it.value == "c.example.org" }
        assertEquals(Grading.Reliability.USUALLY, survivor.reliability)
        assertTrue(survivor.demoted)
    }

    /** A single mistake must not rewrite a source's grade. */
    @Test
    fun oneWrongHitDoesNotDowngradeASource() {
        val marked = Corroboration.merge(
            listOf(row("crtsh", "a.example.org\nc.example.org\n")),
            seed = "example.com",
            dismissed = mapOf(("HOST" to "a.example.org") to "")
        )
        val survivor = marked.first { it.value == "c.example.org" }
        assertEquals(Grading.Reliability.COMPLETE, survivor.reliability)
        assertTrue(!survivor.demoted)
    }

    /** Nor does being wrong twice while also being right more often. */
    @Test
    fun aSourceThatIsMostlyRightKeepsItsGrade() {
        val marked = Corroboration.merge(
            listOf(row("crtsh", (1..6).joinToString("\n") { "h$it.example.org" })),
            seed = "example.com",
            dismissed = mapOf(
                ("HOST" to "h1.example.org") to "",
                ("HOST" to "h2.example.org") to ""
            )
        )
        val survivor = marked.first { it.value == "h6.example.org" }
        assertEquals(Grading.Reliability.COMPLETE, survivor.reliability)
    }

    /** The headline counts what was struck, so the reader is not misled. */
    @Test
    fun theAssessmentSaysHowManyWereRuledOut() {
        val results = listOf(row("crtsh", "a.example.org\nb.example.org\n"))
        val entities = Corroboration.merge(
            results,
            seed = "example.com",
            dismissed = mapOf(("HOST" to "a.example.org") to "")
        )
        assertTrue(Corroboration.assessment(entities, results).contains("1 marked wrong"))
    }

    // ---- Who agrees, not just how many ----

    @Test
    fun aWeakWitnessDoesNotConfirmAStrongOne() {
        // crt.sh (A) and a username sweep's soft-404 profile URL (E)
        // both naming a host is not confirmation: one of them says
        // yes to almost anything.
        val lifted = Grading.credibility(
            source = Grading.Reliability.COMPLETE,
            agreeing = 1,
            agreeingSource = Grading.Reliability.UNRELIABLE
        )
        assertEquals(Grading.Credibility.PROBABLE, lifted)
        // Where crt.sh alone is also probable, so the weak source has
        // added nothing it was not entitled to add.
        assertEquals(
            Grading.credibility(Grading.Reliability.COMPLETE, agreeing = 0),
            lifted
        )
    }

    @Test
    fun twoGoodSourcesStillConfirm() {
        assertEquals(
            Grading.Credibility.CONFIRMED,
            Grading.credibility(
                source = Grading.Reliability.COMPLETE,
                agreeing = 1,
                agreeingSource = Grading.Reliability.USUALLY
            )
        )
    }

    // ---- The same evidence must always get the same grade ----

    @Test
    fun independenceDoesNotDependOnRowOrder() {
        // subsweep reads certificate transparency, the Wayback index,
        // DNS and hackertarget, so it collides with almost everything.
        // The greedy count used to depend on which row the database
        // happened to return first: crtsh, subsweep, wayback scored
        // two in one order and one in the other, and the same evidence
        // came out A1 or A2 by luck.
        val sources = listOf("crtsh", "subsweep", "wayback")
        val counts = listOf(
            Corroboration.independentCount(sources),
            Corroboration.independentCount(sources.reversed()),
            Corroboration.independentCount(listOf("subsweep", "crtsh", "wayback")),
            Corroboration.independentCount(listOf("wayback", "crtsh", "subsweep"))
        )
        assertEquals("order changed the count: $counts", 1, counts.distinct().size)
        // And the deterministic pick is the one that seats the most.
        assertEquals(2, counts.first())
    }

    @Test
    fun oneResolverAnsweringTwiceIsOneWitness() {
        // dns, mailsec, typosquat and emailperm all go to the same
        // DoH endpoint.
        assertEquals(1, Corroboration.independentCount(listOf("dns", "mailsec")))
        assertEquals(1, Corroboration.independentCount(listOf("dns", "typosquat", "emailperm")))
        // And BGPView falls through to RIPEstat, which is where
        // asnintel starts.
        assertEquals(1, Corroboration.independentCount(listOf("bgpview", "asnintel")))
    }
}
