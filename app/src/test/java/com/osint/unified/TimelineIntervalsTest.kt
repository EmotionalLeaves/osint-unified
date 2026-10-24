package com.osint.unified

import com.osint.unified.domain.Grading
import com.osint.unified.domain.Timeline
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finding in a timeline is almost never a date.
 *
 * It is a gap: the domain registered three days before the account,
 * six accounts created in one fortnight, a certificate issued the week
 * of the breach. Every one of those is a subtraction the app had both
 * operands for and left to the reader.
 */
class TimelineIntervalsTest {

    private fun event(day: String, what: String, tool: String) = Timeline.Event(
        date = LocalDate.parse(day),
        what = what,
        toolId = tool,
        toolName = tool,
        reliability = Grading.Reliability.USUALLY
    )

    @Test
    fun nothingIsSaidAboutOneDate() {
        assertTrue(
            Timeline.observations(listOf(event("2024-01-01", "registered", "rdap"))).isEmpty()
        )
    }

    @Test
    fun theSpanIsAlwaysWorthSaying() {
        val notes = Timeline.observations(
            listOf(
                event("2019-03-01", "registered", "rdap"),
                event("2024-09-01", "certificate issued", "crtsh")
            )
        )
        assertTrue("$notes", notes.any { it.what.contains("falls between") })
        assertTrue("$notes", notes.any { it.what.contains("years") })
    }

    @Test
    fun thingsHappeningTogetherAcrossSourcesAreSaid() {
        val notes = Timeline.observations(
            listOf(
                event("2024-02-01", "domain registered", "rdap"),
                event("2024-02-03", "account created", "github"),
                event("2024-02-09", "certificate issued", "crtsh"),
                event("2031-01-01", "certificate expires", "crtsh")
            )
        )
        assertTrue(
            "$notes",
            notes.any { it.what.contains("3 dated records fall inside") }
        )
    }

    /**
     * One source listing four of its own records in a week is a
     * renewal schedule, not a fortnight in which several unrelated
     * things happened.
     */
    @Test
    fun oneSourceRepeatingItselfIsNotACluster() {
        val notes = Timeline.observations(
            listOf(
                event("2024-02-01", "cert 1 issued", "crtsh"),
                event("2024-02-03", "cert 2 issued", "crtsh"),
                event("2024-02-05", "cert 3 issued", "crtsh"),
                event("2024-02-07", "cert 4 issued", "crtsh")
            )
        )
        assertTrue("$notes", notes.none { it.what.contains("dated records fall inside") })
    }

    @Test
    fun theClosestPairFromDifferentSourcesIsNamed() {
        val notes = Timeline.observations(
            listOf(
                event("2019-01-01", "old thing", "rdap"),
                event("2024-06-01", "domain registered", "rdap"),
                event("2024-06-04", "account created", "github")
            )
        )
        assertTrue("$notes", notes.any { it.what.contains("3 days apart") })
    }

    /** Anything derived is stamped as derived. */
    @Test
    fun everyObservationIsInferred() {
        val notes = Timeline.observations(
            listOf(
                event("2019-03-01", "registered", "rdap"),
                event("2024-09-01", "certificate issued", "crtsh")
            )
        )
        assertTrue(notes.isNotEmpty())
        notes.forEach { assertEquals(Grading.Kind.INFERRED, it.kind) }
    }
}
