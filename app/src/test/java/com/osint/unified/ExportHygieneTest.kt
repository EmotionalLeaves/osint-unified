package com.osint.unified

import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.EntityExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A report is handed to other people and opened in other programs, so the
 * two things it must never do are run code and lose its provenance.
 */
class ExportHygieneTest {

    /**
     * The escaping the exporter actually uses.
     *
     * This test used to carry its own copy of the function and test the
     * copy, which passes whatever production does.
     */
    private fun csv(value: String) = InvestigationExporter.csvCell(value)

    @Test
    fun formulaCellsAreNeutralised() {
        assertEquals("\"'=cmd|'/c calc'!A1\"", csv("=cmd|'/c calc'!A1"))
        assertEquals("\"'+1234\"", csv("+1234"))
        assertEquals("\"'-1+1\"", csv("-1+1"))
        assertEquals("\"'@SUM(A1)\"", csv("@SUM(A1)"))
    }

    @Test
    fun ordinaryCellsAreLeftAloneAndQuotesStillEscape() {
        assertEquals("\"github.com\"", csv("github.com"))
        assertEquals("\"he said \"\"hi\"\"\"", csv("he said \"hi\""))
        // A negative number read from a source is still a formula to Excel,
        // so it is quoted too. That is the safe side of the trade.
        assertTrue(csv("-12.5").startsWith("\"'"))
    }
}

/**
 * A name found on a commit has to reach the dossier as a name.
 *
 * The commit search is the one source that turns an email address
 * into a person's name, and a name the extractor cannot see is a
 * name nothing can pivot on — which would waste the only part of
 * it that answers the question somebody asked. The label has to
 * be one EntityExtractor reads, at the start of its own line.
 */
class CommitNameIsAPersonTest {

    private fun row(output: String) = ResultRow(
        id = 1,
        investigationId = 1,
        toolId = "emailgithub",
        toolName = "Email to GitHub",
        input = "someone@example.com",
        status = "success",
        rawOutput = output,
        startedAt = 0L,
        finishedAt = 1L
    )

    @Test
    fun theDeclaredNameBecomesAPerson() {
        val out = """
            Committed to public repositories as someone@example.com — 41 commits:
            Declared name: Ada Lovelace  (18 of 20 read)
            Declared name: ada
            • Linked account: adal — https://github.com/adal
            • Most recent commit: 2026-03-04
            • octo/engine — https://github.com/octo/engine
        """.trimIndent()
        val people = EntityExtractor.extract(listOf(row(out)), "someone@example.com")
            .filter { it.kind == "PERSON" }
            .map { it.value }
        assertTrue(people.toString(), people.any { it.equals("Ada Lovelace", true) })
    }

    @Test
    fun anAbsenceNamesNobody() {
        val out = "No GitHub account lists someone@example.com, and no public " +
            "commit was authored from it."
        val people = EntityExtractor.extract(listOf(row(out)), "someone@example.com")
            .filter { it.kind == "PERSON" }
        assertTrue(people.toString(), people.isEmpty())
    }
}
