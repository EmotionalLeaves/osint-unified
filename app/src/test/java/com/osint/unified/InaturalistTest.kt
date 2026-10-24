package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The iNaturalist handle tool's output, as the dossier reads it.
 *
 * The body below is the shape the tool writes, filled from a real
 * account's public record (probed): the declared name has to become a
 * PERSON the follow-up pass can pivot on, the coordinates have to come
 * through as places, and the absence sentence has to read as an absence.
 */
class InaturalistTest {

    private fun row(output: String) = ResultRow(
        id = 1, investigationId = 1, toolId = "inaturalist",
        toolName = "iNaturalist record", input = "kueda", status = "success",
        rawOutput = output, startedAt = 0L, finishedAt = 1L
    )

    private val found = """
        iNaturalist account "kueda" exists.
        Declared name: Ken-ichi Ueda
        ORCID: https://orcid.org/0000-0003-0145-6846
        Joined: 2008-03-20
        56338 observations, 11712 species.

        Where and when — the latest 3 of 56338:
        • 2026-10-22 — Redwood Regional Park, Alameda County, US-CA, US (37.80445, -122.14042) · Pleocoma behrensi
        • 2026-10-18 — EBMUD, Contra Costa County, US-CA, US (37.92144, -122.18077) · Pacific madrone
        • 2026-10-11 — Tilden Regional Park — location blurred · Western fence lizard
    """.trimIndent()

    @Test
    fun anAccountIsAFinding() {
        assertEquals(Findings.Kind.HIT, Findings.kindOf(row(found)))
    }

    @Test
    fun noAccountIsAnAbsence() {
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(row("No iNaturalist account named \"zz-no-such-user\"."))
        )
    }

    @Test
    fun theDeclaredNameIsAPersonToPivotOn() {
        val people = EntityExtractor.extract(listOf(row(found)), "kueda")
            .filter { it.kind == "PERSON" }.map { it.value }
        assertTrue(people.toString(), people.any { it.equals("ken-ichi ueda", true) || it.equals("ken ichi ueda", true) })
    }
}
