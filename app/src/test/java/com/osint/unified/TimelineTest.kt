package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The dates were always in the answers; nobody ever saw them, because
 * they were spread across sixty result cards.
 */
class TimelineTest {

    private var next = 1L

    private fun row(toolId: String, output: String) = ResultRow(
        id = next++,
        investigationId = 1,
        toolId = toolId,
        toolName = toolId,
        input = "example.com",
        status = "success",
        rawOutput = output,
        startedAt = 0L,
        finishedAt = 1L
    )

    @Test
    fun readsTheShapesTheseSourcesWriteDatesIn() {
        assertEquals(LocalDate.of(2019, 3, 14), Timeline.firstDate("Registered: 2019-03-14"))
        assertEquals(LocalDate.of(2019, 3, 14), Timeline.firstDate("Created 2019/03/14 by x"))
        assertEquals(LocalDate.of(2019, 3, 14), Timeline.firstDate("Issued 14 Mar 2019"))
        assertEquals(LocalDate.of(2019, 3, 14), Timeline.firstDate("Breach on March 14, 2019"))
    }

    /** A hash is not a date and 1970 is not a story. */
    @Test
    fun refusesThingsThatAreNotDates() {
        assertNull(Timeline.firstDate("sha256: 3fa85f6457174562b3fc2c963f66afa6"))
        assertNull(Timeline.firstDate("ASN 13335"))
        assertNull(Timeline.firstDate("no date here at all"))
        // A real shape with impossible numbers.
        assertNull(Timeline.firstDate("Registered: 2019-13-45"))
    }

    /**
     * A response's own generated-at stamp is a fact about the request,
     * not about the person, and a timeline full of those is noise with
     * dates on it.
     */
    @Test
    fun ignoresTheTimestampEveryResponseCarries() {
        val events = Timeline.build(
            listOf(
                row(
                    "rdap",
                    """
                    Generated: 2026-10-22
                    Registered: 2014-06-01
                    Retrieved at 2026-10-22
                    """.trimIndent()
                )
            )
        )
        assertEquals(1, events.size)
        assertEquals(LocalDate.of(2014, 6, 1), events.first().date)
    }

    /** Oldest first, because that is the order a story happens in. */
    @Test
    fun putsEventsInTheOrderTheyHappened() {
        val events = Timeline.build(
            listOf(
                row("breaches", "Breach: 2019-08-02 Collection"),
                row("rdap", "Created: 2011-01-09"),
                row("crtsh", "Not before 2026-02-20")
            )
        )
        assertEquals(
            listOf(2011, 2019, 2026),
            events.map { it.year }
        )
    }

    /** The same fact from two sources is one event. */
    @Test
    fun theSameDatedFactTwiceIsOneEvent() {
        val events = Timeline.build(
            listOf(
                row("rdap", "Registered: 2014-06-01"),
                row("bgpview", "Registered: 2014-06-01")
            )
        )
        assertEquals(1, events.size)
    }

    /** A source that found nothing has no dates belonging to anyone. */
    @Test
    fun readsOnlyTheSourcesThatFoundSomething() {
        val absent = row("sanctions", "No OFAC entry matched. Listed: 2019-01-01")
            .copy(status = "absent")
        assertTrue(Timeline.build(listOf(absent)).isEmpty())
    }

    /** Lines keep their meaning and lose their clutter. */
    @Test
    fun trimsTheLineToSomethingReadable() {
        assertEquals(
            "Registered: 2014-06-01",
            Timeline.tidy("• Registered: 2014-06-01T00:00:00Z  ")
        )
    }
}
