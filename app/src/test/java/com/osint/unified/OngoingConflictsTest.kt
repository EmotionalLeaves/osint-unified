package com.osint.unified

import com.osint.unified.data.OngoingConflicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conflict layer's source, read from a fixture shaped like the live
 * page — the markup below is copied from it, citations, flag icons,
 * sort keys and all.
 *
 * Reported: "conflict showed something from 2017". The old source had no
 * notion of "active", only "no end date recorded". This one carries
 * deaths per year, and these tests pin that the label is built from
 * those and not from when a conflict's lineage began.
 */
class OngoingConflictsTest {

    private val ref = """<sup id="cite&#95;ref-5" class="reference"><a href="#cite_note-5"><span class="cite-bracket">&#91;</span>5<span class="cite-bracket">&#93;</span></a></sup>"""
    private val flag = """<span class="flagicon nowrap"><img alt="" src="//x/Flag.png" /></span> """

    private val page = """
        <table class="wikitable sortable">
        <tr><th>Start of<br />conflict</th><th>Conflict</th><th>Continent</th><th>Location</th>
        <th>Cumulative<br />fatalities</th><th>Fatalities in 2025</th><th>Fatalities in 2026</th></tr>
        <tr>
        <td style="text-align:center;">1948
        </td>
        <td><div class="treelist">
        <ul><li><a href="/wiki/Myanmar_conflict" title="Myanmar conflict">Myanmar civil war</a> (<a href="/wiki/Myanmar_civil_war_(2021–present)" title="x">since 2021</a>)
        <ul><li><a href="/wiki/Rohingya_conflict" title="Rohingya conflict">Rohingya conflict</a></li></ul></li></ul>
        </div>
        </td>
        <td><a href="/wiki/Asia" title="Asia">Asia</a>
        </td>
        <td>$flag<a href="/wiki/Myanmar" title="Myanmar">Myanmar</a><br />$flag<a href="/wiki/India" title="India">India</a>
        </td>
        <td><span data-sort-value="7005214000000000000♠">214,000+</span>$ref
        </td>
        <td><span data-sort-value="7004158240000000000♠">15,824</span>$ref
        </td>
        <td><span data-sort-value="7003834800000000000♠">8,348</span>$ref
        </td></tr>
        <tr>
        <td style="text-align:center;">1955
        </td>
        <td><a href="/wiki/Sudanese_civil_wars" title="Sudanese civil wars">Sudanese civil wars</a>
        </td>
        <td><a href="/wiki/Africa" title="Africa">Africa</a></td>
        <td>$flag<a href="/wiki/Sudan" title="Sudan">Sudan</a><br />$flag<a href="/wiki/South_Sudan" title="South Sudan">South Sudan</a></td>
        <td>1,521,000+$ref</td>
        <td>21,994–80,000+$ref</td>
        <td>10,179$ref</td></tr>
        <tr>
        <td>1999</td><td><a href="/wiki/Short_row" title="x">A row missing columns</a></td><td>Asia</td>
        </tr>
        </table>
        <table class="wikitable"><tr><th>Rank</th><th>Conflict</th></tr>
        <tr><td>1</td><td><a href="/wiki/Other" title="o">Not a conflict row</a></td></tr></table>
    """.trimIndent()

    private val parsed = OngoingConflicts.parse(page, fallbackYear = 2026)

    @Test
    fun eachConflictRowIsRead() {
        assertEquals(listOf("Myanmar civil war", "Sudanese civil wars"), parsed.rows.map { it.name })
    }

    /** The phase, not the lineage: "since 2021", never "since 1948". */
    @Test
    fun theCurrentPhaseIsKeptAndTheLineageIsNotTheHeadline() {
        val myanmar = parsed.rows.first()
        assertEquals("since 2021", myanmar.phase)
        assertEquals(1948, myanmar.began)
        assertNull(parsed.rows[1].phase)
    }

    @Test
    fun deathsThisYearAreReadPastTheCitationsAndSortKeys() {
        val myanmar = parsed.rows.first()
        assertEquals(OngoingConflicts.Deaths(2026, 8348, exact = true), myanmar.thisYear)
        assertEquals(OngoingConflicts.Deaths(2025, 15824, exact = true), myanmar.lastYear)
    }

    /** "21,994–80,000+" is a floor of 21,994 and must say "at least". */
    @Test
    fun aRangeIsAFloorAndNotACount() {
        val sudan = parsed.rows[1].lastYear
        assertNotNull(sudan)
        assertEquals(21994, sudan!!.atLeast)
        assertFalse(sudan.exact)
    }

    @Test
    fun theCountriesComeFromTheLocationCellInOrder() {
        assertEquals(listOf("Myanmar", "India"), parsed.rows.first().countries)
        assertEquals(listOf("Sudan", "South Sudan"), parsed.rows[1].countries)
    }

    @Test
    fun theArticleIsLinked() {
        assertEquals("https://en.wikipedia.org/wiki/Myanmar_conflict", parsed.rows.first().article)
    }

    /** A row that starts like a conflict and then is not one is counted, not dropped silently. */
    @Test
    fun aMalformedRowIsCountedSoTheLayerCanSaySo() {
        assertEquals(1, parsed.skipped)
    }

    /**
     * The years come from the headers. The page is edited by hand and in
     * January can still be headed with last year's pair; a label saying
     * "killed in 2027" over a 2026 count would be wrong in exactly the way
     * this layer exists to stop being.
     */
    @Test
    fun theYearsAreTheHeadersAndNotTheClock() {
        val january = OngoingConflicts.parse(page, fallbackYear = 2027)
        assertEquals(2026, january.rows.first().thisYear!!.year)
        assertEquals(2025, january.rows.first().lastYear!!.year)
    }

    @Test
    fun aPageWithNoConflictRowsReadsAsNothing() {
        val empty = OngoingConflicts.parse("<html><body>maintenance</body></html>", 2026)
        assertTrue(empty.rows.isEmpty())
    }

    /**
     * The names Wikipedia uses where Natural Earth's differ resolve to a
     * code, so a third of Africa's wars do not fall off the globe on a
     * spelling.
     */
    @Test
    fun theNamesThatDifferResolveToACode() {
        assertEquals("CD", OngoingConflicts.isoFor("Democratic Republic of the Congo"))
        assertEquals("SS", OngoingConflicts.isoFor("South Sudan"))
        assertEquals("CF", OngoingConflicts.isoFor("Central African Republic"))
        assertEquals("EH", OngoingConflicts.isoFor("Sahrawi Republic"))
        assertEquals("SO", OngoingConflicts.isoFor("Somaliland"))
        assertEquals("PS", OngoingConflicts.isoFor("Palestine"))
        assertEquals("CI", OngoingConflicts.isoFor("Côte d'Ivoire"))
        // A name that agrees with Natural Earth needs no alias.
        assertNull(OngoingConflicts.isoFor("Sudan"))
    }

    /**
     * A name with markup inside its link is still its name. Read as
     * plain-text links only, the italic one was skipped and the row was
     * named after the next link in the cell — its phase.
     */
    @Test
    fun aNameInItalicsIsStillTheName() {
        val wrapped = page.replace(
            """<a href="/wiki/Myanmar_conflict" title="Myanmar conflict">Myanmar civil war</a>""",
            """<a href="/wiki/Myanmar_conflict" title="Myanmar conflict"><i>Myanmar civil war</i></a>"""
        )
        val rows = OngoingConflicts.parse(wrapped, fallbackYear = 2026).rows
        assertEquals("Myanmar civil war", rows.first().name)
        assertEquals("since 2021", rows.first().phase)
        assertEquals(listOf("Myanmar", "India"), rows.first().countries)
    }
}
