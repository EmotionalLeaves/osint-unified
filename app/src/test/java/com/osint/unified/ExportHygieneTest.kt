package com.osint.unified

import com.osint.unified.data.InvestigationExporter
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
