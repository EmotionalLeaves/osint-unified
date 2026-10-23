package com.osint.unified

import com.osint.unified.domain.CaseLinks
import com.osint.unified.domain.Corroboration
import com.osint.unified.domain.Grading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What ties the targets of a case together.
 *
 * A case with three people in it is three dossiers until something is
 * found in two of them, and that moment is the only thing the app can
 * see that a person reading the dossiers one at a time cannot — because
 * the identifier that matters is unremarkable in each.
 */
class CaseLinksTest {

    private fun entity(
        kind: String,
        value: String,
        sources: List<String> = listOf("crtsh"),
        reliability: Grading.Reliability = Grading.Reliability.COMPLETE,
        caveat: String? = null,
        dismissed: Boolean = false
    ) = Corroboration.Entity(
        kind = kind,
        value = value,
        sources = sources,
        independent = sources.size,
        reliability = reliability,
        credibility = Grading.credibility(reliability, agreeing = sources.size - 1),
        mentions = 1,
        caveat = caveat,
        dismissed = dismissed
    )

    @Test
    fun oneTargetCanLinkToNothing() {
        val links = CaseLinks.find(mapOf("alice@example.com" to listOf(entity("HOST", "x.org"))))
        assertTrue(links.isEmpty())
        assertTrue(CaseLinks.summary(links, 1).contains("needs two"))
    }

    @Test
    fun anIdentifierUnderTwoTargetsIsALink() {
        val links = CaseLinks.find(
            mapOf(
                "alice@example.com" to listOf(entity("HOST", "shared.org"), entity("HOST", "a.org")),
                "bob@example.com" to listOf(entity("HOST", "shared.org"), entity("HOST", "b.org"))
            )
        )
        assertEquals(1, links.size)
        assertEquals("shared.org", links.first().value)
        assertEquals(
            listOf("alice@example.com", "bob@example.com"),
            links.first().targets
        )
    }

    /**
     * The difference between a useful screen and a useless one. Nearly
     * every site on Earth sits behind the same handful of content
     * delivery networks, so without this every pair of targets in every
     * case would "share" Cloudflare.
     */
    @Test
    fun machineryIsNeverALink() {
        val cdn = entity("HOST", "x.cloudflare.com", caveat = "a content delivery or cloud host")
        val links = CaseLinks.find(
            mapOf(
                "alice@example.com" to listOf(cdn),
                "bob@example.com" to listOf(cdn)
            )
        )
        assertTrue(links.isEmpty())
    }

    /** Nor is anything the investigator struck. */
    @Test
    fun somethingRuledOutIsNeverALink() {
        val wrong = entity("HOST", "wrong.org", caveat = "you marked this wrong", dismissed = true)
        val links = CaseLinks.find(
            mapOf(
                "alice@example.com" to listOf(wrong),
                "bob@example.com" to listOf(wrong)
            )
        )
        assertTrue(links.isEmpty())
    }

    /** One target naming another outright is the strongest shape a link takes. */
    @Test
    fun aTargetFoundInsideAnotherTargetIsFlagged() {
        val links = CaseLinks.find(
            mapOf(
                "alice@example.com" to listOf(entity("EMAIL", "bob@example.com")),
                "carol@example.com" to listOf(entity("EMAIL", "bob@example.com")),
                "bob@example.com" to listOf(entity("HOST", "b.org"))
            )
        )
        assertEquals(1, links.size)
        assertTrue(links.first().isTarget)
        assertTrue(CaseLinks.summary(links, 3).contains("names another target"))
    }

    /** A target asserting its own seed links it to nothing. */
    @Test
    fun aTargetDoesNotLinkToItself() {
        val links = CaseLinks.find(
            mapOf(
                "alice@example.com" to listOf(entity("EMAIL", "alice@example.com")),
                "bob@example.com" to listOf(entity("EMAIL", "alice@example.com"))
            )
        )
        // Only bob's dossier asserts it as a finding, so there is one
        // assertion, not two, and one is not a link.
        assertTrue(links.isEmpty())
    }

    /**
     * Two separate enquiries meeting is better corroboration than two
     * tools reading the same upstream, and the grade has to show it.
     */
    @Test
    fun agreementAcrossTargetsRaisesCredibility() {
        val alone = CaseLinks.find(
            mapOf(
                "a" to listOf(entity("HOST", "s.org", reliability = Grading.Reliability.FAIRLY)),
                "b" to listOf(entity("HOST", "s.org", reliability = Grading.Reliability.FAIRLY))
            )
        ).first()
        assertEquals(Grading.Credibility.CONFIRMED, alone.credibility)
    }

    @Test
    fun linksAreOrderedByHowManyTargetsTheyJoin() {
        val links = CaseLinks.find(
            mapOf(
                "a" to listOf(entity("HOST", "two.org"), entity("HOST", "three.org")),
                "b" to listOf(entity("HOST", "two.org"), entity("HOST", "three.org")),
                "c" to listOf(entity("HOST", "three.org"))
            )
        )
        assertEquals(listOf("three.org", "two.org"), links.map { it.value })
    }

    @Test
    fun tagsAreCleanedAndCounted() {
        assertEquals(listOf("alpha", "beta"), CaseLinks.split(" Alpha , beta,,alpha "))
        assertEquals("alpha, beta", CaseLinks.join(listOf("Beta", "alpha", "BETA")).let {
            CaseLinks.split(it).sorted().joinToString(", ")
        })
        assertEquals(
            listOf("shared" to 2, "only" to 1),
            CaseLinks.tagsOf(listOf("shared, only", "Shared"))
        )
        assertEquals("", CaseLinks.toggle("alpha", "alpha"))
        assertEquals("alpha, beta", CaseLinks.toggle("alpha", "Beta"))
    }

    /**
     * One witness is one witness, however many dossiers it turns up in.
     *
     * The grade used to add one for each extra target that mentioned
     * the value, on the reasoning that two dossiers meeting are two
     * separate enquiries. They need not be: run two domains and crt.sh
     * alone returns a shared host under both. That came out A1,
     * "confirmed by other sources", tinted as well-supported, with
     * crt.sh as the only name under it — two taps from a screen that
     * says one source is never a confirmation.
     */
    @Test
    fun oneSourceUnderTwoTargetsIsStillOneSource() {
        val links = CaseLinks.find(
            mapOf(
                "a.com" to listOf(entity("HOST", "shared.net", sources = listOf("crtsh"))),
                "b.com" to listOf(entity("HOST", "shared.net", sources = listOf("crtsh")))
            )
        )
        assertEquals(1, links.size)
        assertEquals(1, links.first().independent)
        assertTrue(
            links.first().credibility.toString(),
            links.first().credibility != Grading.Credibility.CONFIRMED
        )
    }

    /** Two genuinely separate upstreams still corroborate. */
    @Test
    fun twoIndependentSourcesDoConfirm() {
        val links = CaseLinks.find(
            mapOf(
                "a.com" to listOf(entity("HOST", "shared.net", sources = listOf("crtsh"))),
                "b.com" to listOf(entity("HOST", "shared.net", sources = listOf("breaches")))
            )
        )
        assertEquals(2, links.first().independent)
    }

    /**
     * And two tools reading one API are one source. subsweep and
     * hostintel both ask hackertarget; subsweep also reads the Wayback
     * index and the certificate logs, so it is not independent of
     * wayback or crt.sh either.
     */
    @Test
    fun toolsSharingAnUpstreamAreOneWitness() {
        assertEquals(1, Corroboration.independentCount(listOf("subsweep", "hostintel")))
        assertEquals(1, Corroboration.independentCount(listOf("subsweep", "wayback")))
        assertEquals(1, Corroboration.independentCount(listOf("subsweep", "crtsh")))
        assertEquals(1, Corroboration.independentCount(listOf("sherlock", "maigret", "blackbird")))
        // Genuinely different upstreams still count separately.
        assertEquals(2, Corroboration.independentCount(listOf("crtsh", "breaches")))
        assertEquals(3, Corroboration.independentCount(listOf("crtsh", "breaches", "github")))
    }
}
