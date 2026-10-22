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
        assertTrue(Findings.saysNothing(""))
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
}
