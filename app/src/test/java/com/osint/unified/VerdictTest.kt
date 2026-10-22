package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two readings that phrase-matching got backwards, and the stated
 * verdict that makes them moot.
 */
class VerdictTest {

    private fun row(status: String, output: String, tool: String = "t") = ResultRow(
        investigationId = 1, toolId = tool, toolName = tool, input = "x",
        status = status, rawOutput = output, startedAt = 0L
    )

    /** A stated verdict is believed over anything its prose might suggest. */
    @Test
    fun aStatedAbsenceIsAnAbsenceWhateverTheWordsAre() {
        val r = row(Findings.STATUS_ABSENT, "Found nothing of note, 4 open ports listed below")
        assertEquals(Findings.Kind.ABSENT, Findings.kindOf(r))
        assertFalse(Findings.isHit(r))
    }

    @Test
    fun aStatedFailureIsNotAnAbsence() {
        val r = row(Findings.STATUS_UNREACHABLE, "The UN consolidated list is not bundled.")
        assertEquals(Findings.Kind.UNREACHABLE, Findings.kindOf(r))
        val sorted = Findings.sort(listOf(r))
        assertTrue(sorted.empty.isEmpty())
        assertEquals(1, sorted.failed.size)
    }

    /**
     * A row still running is not a miss.
     *
     * The dossier used to show "NOTHING FOUND (38 errored)" while
     * thirty-eight tools were still working.
     */
    @Test
    fun runningRowsAreInNoneOfTheThree() {
        val rows = listOf(
            row("running", "", "busy"),
            row("success", "Found 3 accounts:\na — b", "hit")
        )
        val sorted = Findings.sort(rows)
        assertEquals(listOf("hit"), sorted.hits.map { it.toolId })
        assertTrue(sorted.empty.isEmpty())
        assertTrue(sorted.failed.isEmpty())
        assertEquals(listOf("busy"), Findings.pending(rows).map { it.toolId })
    }

    /**
     * The two readings phrase-matching got backwards.
     *
     * "No database exposures, but 4 open ports" was an absence because
     * "no database" begins with "no data", and "registrant unavailable"
     * was unreachable because of one adjective. Both are findings.
     */
    @Test
    fun aPhraseInsideALongerWordIsNotAVerdict() {
        val ports = row("success", "No database exposures, but 4 open ports: 22, 80, 443")
        assertEquals(Findings.Kind.HIT, Findings.kindOf(ports))

        val whois = row("success", "Domain registered; WHOIS privacy: registrant unavailable")
        assertEquals(Findings.Kind.HIT, Findings.kindOf(whois))
    }

    /** A real absence still reads as one. */
    @Test
    fun anActualAbsenceStillReadsAsOne() {
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(row("success", "OFAC SDN: no sanctioned entity matching 'xyz'."))
        )
        assertEquals(
            Findings.Kind.UNREACHABLE,
            Findings.kindOf(row("success", "The UN consolidated list is not bundled in this build."))
        )
    }
}
