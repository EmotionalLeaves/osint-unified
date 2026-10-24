package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.ActivityHours
import com.osint.unified.domain.Grading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint that went nowhere.
 *
 * Two tools print a posting peak and a six-hour quiet window in UTC,
 * and one of them labels it "timezone hint" in so many words. Nothing
 * turned it into a number, and nothing compared it with the declared
 * location in the same dossier.
 */
class ActivityHoursTest {

    private fun row(tool: String, output: String) = ResultRow(
        id = 0,
        investigationId = 1,
        toolId = tool,
        toolName = tool,
        input = "jdoe",
        status = "success",
        rawOutput = output,
        startedAt = 1,
        finishedAt = 2
    )

    @Test
    fun aQuietWindowIsReadOutOfTheOutput() {
        val found = ActivityHours.quietWindows(
            listOf(row("ghactivity", "Commit peak: 14:00 UTC, quiet 2:00–8:00 UTC (timezone hint)"))
        )
        assertEquals(1, found.size)
        assertEquals(2, found.first().fromHour)
        assertEquals(8, found.first().toHour)
    }

    /**
     * A night is roughly local midnight to six. Somebody at UTC+1 is
     * therefore quiet from 23:00 to 05:00 UTC, and somebody at UTC-8
     * from 08:00 to 14:00.
     */
    @Test
    fun theOffsetBandFollowsTheNight() {
        assertTrue(1 in ActivityHours.offsetsFor(ActivityHours.Quiet(23, 5, "ghactivity")))
        assertTrue(-8 in ActivityHours.offsetsFor(ActivityHours.Quiet(8, 14, "ghactivity")))
        assertTrue(0 in ActivityHours.offsetsFor(ActivityHours.Quiet(0, 6, "ghactivity")))
        val band = ActivityHours.offsetsFor(ActivityHours.Quiet(23, 5, "ghactivity"))
        assertTrue("the band is a band, not a figure", band.last - band.first >= 2)
    }

    @Test
    fun aDeclaredPlaceInsideTheBandIsConsistent() {
        val note = ActivityHours.compare(
            listOf(row("ghactivity", "Commit peak: 14:00 UTC, quiet 23:00–5:00 UTC")),
            listOf("berlin")
        )
        requireNotNull(note)
        assertTrue(note.what, note.what.startsWith("Consistent"))
        assertEquals(Grading.Kind.INFERRED, note.kind)
    }

    /** The case this exists for. */
    @Test
    fun aDeclaredPlaceOutsideTheBandIsWorthChecking() {
        val note = ActivityHours.compare(
            listOf(row("ghactivity", "Commit peak: 9:00 UTC, quiet 23:00–5:00 UTC")),
            listOf("san francisco")
        )
        requireNotNull(note)
        assertTrue(note.what, note.what.startsWith("Worth checking"))
        assertTrue(note.what, note.what.contains("San francisco", ignoreCase = true))
    }

    /** Silence is the right answer more often than not. */
    @Test
    fun nothingIsSaidWithoutBothHalves() {
        assertNull(
            "no hours",
            ActivityHours.compare(listOf(row("github", "Location: Berlin")), listOf("berlin"))
        )
        assertNull(
            "no place",
            ActivityHours.compare(
                listOf(row("ghactivity", "quiet 2:00–8:00 UTC")),
                emptyList()
            )
        )
    }

    /**
     * A country wide enough to hold several zones gets no offset, so
     * nothing can contradict somebody truthfully.
     */
    @Test
    fun aWideCountryIsNotGivenAnOffset() {
        listOf("united states", "russia", "canada", "australia", "brazil").forEach { wide ->
            assertNull(
                wide,
                ActivityHours.compare(
                    listOf(row("ghactivity", "quiet 2:00–8:00 UTC")),
                    listOf(wide)
                )
            )
        }
    }
    /**
     * The one case where the inference is perfectly right, reported
     * as a discrepancy — because the band was plain integer
     * containment on a circular quantity, and the sentence named
     * "UTC-14", which nobody has.
     */
    @Test
    fun anOffsetAcrossTheDatelineStillAgrees() {
        val note = ActivityHours.compare(
            listOf(row("ghactivity", "quiet 12:00–18:00 UTC")),
            listOf("wellington")
        )
        requireNotNull(note)
        assertTrue(note.what, note.what.startsWith("Consistent"))
        assertTrue("no impossible offset", !note.what.contains("UTC-1") ||
            note.what.contains("UTC-11") || note.what.contains("UTC-10"))
        assertTrue(note.what, note.what.contains("UTC+12"))
    }

    @Test
    fun anOffsetIsNeverWrittenBeyondTheRealRange() {
        (0..23).forEach { from ->
            val band = ActivityHours.offsetsFor(ActivityHours.Quiet(from, (from + 6) % 24, "t"))
            val centre = (band.first + band.last) / 2
            val written = ActivityHours.asOffset(centre)
            val value = written.removePrefix("+").toInt()
            assertTrue("$written is not an offset anybody has", value in -11..12)
        }
    }

    /** The corrected rows. */
    @Test
    fun theTableAgreesWithTheWorld() {
        // Nairobi: East Africa Time is +3, and was written as +2.
        val nairobi = ActivityHours.compare(
            listOf(row("ghactivity", "quiet 21:00–3:00 UTC")),
            listOf("kenya")
        )
        requireNotNull(nairobi)
        assertTrue(nairobi.what, nairobi.what.contains("UTC+3"))
        // Mexico and Indonesia span three zones each and are excluded,
        // as the table's own comment says multi-zone countries are.
        listOf("mexico", "indonesia").forEach { wide ->
            assertNull(
                wide,
                ActivityHours.compare(
                    listOf(row("ghactivity", "quiet 2:00–8:00 UTC")),
                    listOf(wide)
                )
            )
        }
    }

}
