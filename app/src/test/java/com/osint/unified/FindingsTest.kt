package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindingsTest {

    private fun row(status: String, output: String, tool: String = "t") = ResultRow(
        investigationId = 1, toolId = tool, toolName = tool, input = "x",
        status = status, rawOutput = output, startedAt = 0L
    )

    @Test
    fun absenceIsNotAFinding() {
        assertTrue(Findings.saysNothing("OFAC SDN: no sanctioned entity matching 'xyz'."))
        assertTrue(Findings.saysNothing("  \n  No accounts found for \"xyz\" across 900 sites."))
    }

    /**
     * Saying nothing is not the same as saying no.
     *
     * This test used to assert the opposite: that a blank output
     * reported an absence. It is the difference between "we asked
     * the sanctions list and this person is not on it" and "the
     * sanctions list did not answer", and the app was drawing the
     * first conclusion from the second — a claim about the world
     * manufactured out of silence, then counted as a corroborating
     * source and written into the export.
     */
    @Test
    fun silenceIsNotAnAbsence() {
        assertFalse(Findings.saysNothing(""))
        assertFalse(Findings.saysNothing("   \n\n  "))
        assertEquals(Findings.Kind.UNREACHABLE, Findings.kindOf(row("success", "")))
        assertEquals(Findings.Kind.UNREACHABLE, Findings.kindOf(row("success", "  \n ")))
    }

    @Test
    fun substanceIsAFinding() {
        assertFalse(Findings.saysNothing("Breach exposure — a@b.com\nFound in 3 public breaches:"))
        assertFalse(Findings.saysNothing("IP: 1.1.1.1\nPrefix: 1.1.1.0/24"))
    }

    /** A source that said no and a source that could not be asked support
     *  opposite conclusions, so they are never pooled. */
    @Test
    fun errorsAreKeptApartFromEmptyAnswers() {
        val sorted = Findings.sort(
            listOf(
                row("success", "Found 2 accounts for \"jo\":\na — b", "hit"),
                row("success", "No results for jo.", "empty"),
                row("error", "HTTP 503", "failed")
            )
        )
        assertEquals(listOf("hit"), sorted.hits.map { it.toolId })
        assertEquals(listOf("empty"), sorted.empty.map { it.toolId })
        assertEquals(listOf("failed"), sorted.failed.map { it.toolId })
    }

    /**
     * The sweep's own caveat outranks its headline.
     *
     * "No accounts found" at the top of a run that reached a fifth of its
     * list is not an absence, and filing it as one is how a dossier states
     * a clean sheet nobody checked.
     */
    @Test
    fun partialCoverageIsNotAnAbsence() {
        val out = "No accounts found for \"jo\".\n" +
            "Coverage: 2140 answered · 900 refused or blocked · 6349 not reached " +
            "of 9389 sites — stopped at the time cap.\n" +
            "This is not a clean sheet: only the 2140 sites that answered said no."
        assertFalse(Findings.saysNothing(out))
        assertTrue(Findings.saysUnreachable(out))
        assertEquals(listOf("sweep"), Findings.sort(listOf(row("success", out, "sweep"))).failed.map { it.toolId })
    }

    /** A source reporting its own failure is unreachable, not empty. */
    @Test
    fun aSourceReportingItsOwnFailureIsNotAnAbsence() {
        val out = "The UN consolidated list is not bundled in this build."
        assertFalse(Findings.saysNothing(out))
        assertTrue(Findings.saysUnreachable(out))
        val sorted = Findings.sort(listOf(row("success", out, "unsanctions")))
        assertTrue(sorted.empty.isEmpty())
        assertEquals(listOf("unsanctions"), sorted.failed.map { it.toolId })
    }

    /** Only the first line decides: a long report that happens to contain
     *  the words "no results" further down is still a report. */
    @Test
    fun onlyTheOpeningLineDecides() {
        val out = "Certificate transparency — 48 names for example.com\n" +
            "…crt.sh returned no results for the wildcard query"
        assertFalse(Findings.saysNothing(out))
    }

    /**
     * The exact sentences the executors emit on failure.
     *
     * These are copied from the code rather than paraphrased, because
     * the bug both times was a phrase that read naturally and landed
     * in the wrong bucket. RIPEstat's failure began "No routing
     * data", which is in the list of things that mean a source looked
     * and found nothing — so a dropped connection was filed as an
     * established absence and the dossier reported that the address
     * is not routed. GDELT's was in no list at all, so a failed call
     * counted as a source that returned something.
     */
    @Test
    fun aFailureIsNeverAnAbsence() {
        assertEquals(
            Findings.Kind.UNREACHABLE,
            Findings.kindOf(
                row("success", "RIPEstat could not be reached for 1.2.3.4 (timeout).")
            )
        )
        assertEquals(
            Findings.Kind.UNREACHABLE,
            Findings.kindOf(row("success", "GDELT could not be reached for \"acme\"."))
        )
        assertEquals(
            Findings.Kind.UNREACHABLE,
            Findings.kindOf(
                row(
                    "success",
                    "The OFAC check could not be completed: the alias list " +
                        "could not be read from this build, so this says " +
                        "nothing either way about 'smith'."
                )
            )
        )
    }

    /** And a real absence still reads as one. */
    @Test
    fun arealAbsenceIsStillAnAbsence() {
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(row("success", "No routing data for 1.2.3.4."))
        )
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(row("success", "OFAC SDN: no sanctioned entity matching 'xyz'."))
        )
    }

    /**
     * The first lines two dozen tools actually print when they looked
     * and found nothing.
     *
     * Each of these was filed as a finding, and counted in "N of M
     * sources returned something", because the phrase list held "no
     * record" and the tool wrote "No PTR record". Taken verbatim from
     * the executors.
     */
    @Test
    fun anAbsenceIsAnAbsenceHoweverItIsWorded() {
        listOf(
            "No DNS records found.",
            "No PTR record for 1.2.3.4.",
            "No robots.txt, security.txt or sitemap found on host.",
            "No infostealer records found for this email.",
            "No urlscan.io scans for example.com.",
            "No GitHub user \"someone\".",
            "No Wikipedia places within 10 km.",
            "Telegram: no public channel '@someone' (or it is private).",
            "GitHub: no account publicly lists a@b.com.",
            "No PGP key published for a@b.com",
            "No network data for 1.2.3.4.",
            "IODA reports no internet outage alerts in the last 24 hours.",
            "No registrant matched.",
            "No geotagged imagery found here. Wider options:",
            "No archive data for that point.",
            "No public traffic-camera feed within 50 km of that point.",
            "1.2.3.4 is not a Tor exit node (checked 2000 known exits).",
            "Not a recognised BTC or ETH address: abc",
            "No CVEs found for acme widget.",
            "Host intel: no data returned for example.com."
        ).forEach {
            assertEquals(it, Findings.Kind.ABSENT, Findings.kindOf(row("success", it)))
        }
    }

    /**
     * And the headers real findings start with, none of which may be
     * dragged into that net.
     */
    @Test
    fun aFindingIsNotAnAbsence() {
        listOf(
            "Breach exposure — a@b.com",
            "Domain: example.com",
            "Aircraft — N12345",
            "Wikidata — Jane Doe (Q42)",
            "Host intel — 1.2.3.4",
            "IP reputation — 1.2.3.4",
            "Platform sweep — 'mike'",
            "Certificate transparency — 42 names for example.com",
            "Subdomain sweep — example.com",
            "Legal entities named 'Acme' (GLEIF, 3 shown):",
            "Keybase — proven identities for 'someone':",
            "Typosquat sweep — example.com",
            "Imagery near 51.50000, -0.10000",
            "CVEs for acme widget",
            "Likely addresses — Jane Doe at example.com",
            "Ethereum address — 0xabc"
        ).forEach {
            assertEquals(it, Findings.Kind.HIT, Findings.kindOf(row("success", it)))
        }
    }

    /** The failures that were being counted as sources that answered. */
    @Test
    fun theseAreFailuresNotFindings() {
        listOf(
            "Certificate transparency unreachable — crt.sh: timeout; Cert Spotter: 503",
            "GDELT is rate limiting (it allows one request every five seconds). Try again shortly.",
            "No TLS handshake with example.com.",
            "No certificate presented by example.com."
        ).forEach {
            assertEquals(it, Findings.Kind.UNREACHABLE, Findings.kindOf(row("success", it)))
        }
    }
}
