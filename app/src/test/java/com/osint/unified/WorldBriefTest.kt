package com.osint.unified

import com.osint.unified.data.SpaceWeather
import com.osint.unified.ui.godeye.GeoBlip
import com.osint.unified.ui.godeye.GodEyeUiState
import com.osint.unified.ui.godeye.Quake
import com.osint.unified.ui.godeye.WorldBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBriefTest {

    @Test
    fun readsTheAgeEveryFeedAlreadyWritesIntoItsLabel() {
        assertEquals(40, WorldBrief.ageMinutes("Flood: Somewhere · ORANGE · 40m ago"))
        assertEquals(180, WorldBrief.ageMinutes("Cyclone · 3h ago"))
        assertEquals(2880, WorldBrief.ageMinutes("Wildfire · 2d ago"))
        assertNull(WorldBrief.ageMinutes("KC135 · K35R"))
    }

    /**
     * A standing conflict is not today's news.
     *
     * It used to outscore a magnitude 6 quake and carried no age, so the
     * same war held the headline for months — and a headline that never
     * changes stops being read.
     */
    @Test
    fun aStandingConflictDoesNotOwnTheHeadline() {
        val state = GodEyeUiState(
            conflicts = listOf(GeoBlip(1.0, 1.0, "Some war · since 2014")),
            quakes = listOf(Quake(2.0, 2.0, 6.3))
        )
        val brief = WorldBrief.build(state)
        assertTrue(brief.sections.first().title.startsWith("EARTHQUAKES"))
    }

    /** The category word decides, not the bulletin's prose. */
    @Test
    fun anInformationStatementIsNotAWarning() {
        assertEquals("INFORMATION", WorldBrief.categoryWord(
            "NTWC INFORMATION: 5 miles SE of Sand Point, Alaska · 2h ago"))
        assertEquals("WARNING", WorldBrief.categoryWord("PTWC WARNING: offshore Honshu · 10m ago"))
        // The issuing body's name contains "Warning Center"; that must not
        // promote an information statement to a live warning.
        assertEquals("INFORMATION", WorldBrief.categoryWord(
            "NTWC INFORMATION: issued by the National Tsunami Warning Center"))
    }

    /** A red alert must outrank a larger pile of ordinary ones. */
    @Test
    fun severityBeatsVolume() {
        val state = GodEyeUiState(
            gdacs = listOf(GeoBlip(1.0, 1.0, "Flood: Here · RED · 1h ago")),
            news = (1..50).map { GeoBlip(it.toDouble(), 0.0, "story $it") }
        )
        val brief = WorldBrief.build(state)
        assertEquals("DISASTER ALERTS — 1", brief.sections.first().title)
    }

    /** Within a section, the worst comes first; ties go to the most recent. */
    @Test
    fun ordersBySeverityThenRecency() {
        val state = GodEyeUiState(
            gdacs = listOf(
                GeoBlip(1.0, 1.0, "Flood: Old green one · GREEN · 10m ago"),
                GeoBlip(2.0, 2.0, "Quake: Severe · RED · 6h ago"),
                GeoBlip(3.0, 3.0, "Storm: Middling · ORANGE · 20m ago")
            )
        )
        val entries = WorldBrief.build(state).sections.first().entries
        assertTrue(entries[0].text.contains("RED"))
        assertTrue(entries[1].text.contains("ORANGE"))
    }

    @Test
    fun aBigQuakeLiftsTheQuakeSectionAboveRoutineTraffic() {
        val state = GodEyeUiState(
            quakes = listOf(Quake(1.0, 1.0, 6.4), Quake(2.0, 2.0, 2.1)),
            aircraft = emptyList()
        )
        val brief = WorldBrief.build(state)
        assertTrue(brief.sections.first().title.startsWith("EARTHQUAKES"))
        assertTrue(brief.sections.first().entries.first().text.startsWith("M6.4"))
    }

    /** A geomagnetic storm is news; a quiet magnetosphere is not. */
    @Test
    fun spaceWeatherOnlyClimbsWhenThereIsAStorm() {
        val quiet = WorldBrief.build(
            GodEyeUiState(
                spaceWeather = SpaceWeather(kp = 2.0),
                quakes = listOf(Quake(0.0, 0.0, 5.2))
            )
        )
        assertTrue(quiet.sections.first().title.startsWith("EARTHQUAKES"))

        val storm = WorldBrief.build(
            GodEyeUiState(
                spaceWeather = SpaceWeather(kp = 7.0, alerts = listOf("G3 warning")),
                quakes = listOf(Quake(0.0, 0.0, 5.2))
            )
        )
        assertEquals("SPACE WEATHER", storm.sections.first().title)
    }

    /** "Nothing anywhere" is information; "has not run yet" is not. */
    @Test
    fun aLayerThatReportedNothingIsDistinctFromOneThatHasNotRun() {
        val reported = WorldBrief.build(GodEyeUiState(loaded = setOf("gdacs")))
        assertEquals(listOf("disaster alerts"), reported.quiet)

        val notRun = WorldBrief.build(GodEyeUiState())
        assertTrue(notRun.quiet.isEmpty())
    }

    /**
     * A feed being down is not a thing that happened.
     *
     * The outage section outranked most of the news, and its entries are
     * feed names, so the one-line ticker read "military aircraft" when it
     * meant "the military aircraft feed is down" — which is indis-
     * tinguishable from a report of military aircraft.
     */
    @Test
    fun anOutageIsNeverTheHeadlineWhenSomethingIsHappening() {
        val state = GodEyeUiState(
            feedTrouble = mapOf(
                "milAircraft" to "timeout",
                "aircraft" to "timeout"
            ),
            quakes = listOf(
                Quake(
                    lat = 38.0, lon = 142.0, mag = 6.4,
                    place = "off the coast of Honshu",
                    atMillis = System.currentTimeMillis() - 600_000L, depthKm = 10.0
                )
            )
        )
        val headline = WorldBrief.headline(WorldBrief.build(state))
        assertTrue(
            "headline was \"$headline\"",
            headline.contains("Honshu") || headline.contains("6.4")
        )
    }

    /** With nothing happening and feeds down, it says that plainly. */
    @Test
    fun anOutageIsTheHeadlineOnlyWhenThereIsNothingElse() {
        // A key the brief actually reports on; "aircraft" is not one of
        // them, which is what made the first version of this test assert
        // against an empty brief.
        val state = GodEyeUiState(feedTrouble = mapOf("milAircraft" to "timeout"))
        val headline = WorldBrief.headline(WorldBrief.build(state))
        assertTrue("headline was \"$headline\"", headline.contains("not reporting"))
    }

    /**
     * The shelf of six must agree with the full sort it replaced.
     *
     * A hundred thousand wildfire detections were being sorted in
     * full to take six off the front, during composition. The
     * selection is a single pass now, and the only thing that matters
     * about it is that it picks the same six in the same order.
     */
    @Test
    fun theTopSixAreTheSameSixAFullSortWouldPick() {
        val labels = (1..500).map { i ->
            val level = when (i % 4) {
                0 -> "RED"
                1 -> "ORANGE"
                2 -> "GREEN"
                else -> ""
            }
            "Event $i · $level · ${i}m ago"
        }
        val blips = labels.mapIndexed { i, l -> GeoBlip(i * 0.1, i * 0.1, l) }
        val brief = WorldBrief.build(
            GodEyeUiState(gdacs = blips, loaded = setOf("gdacs"))
        )
        val section = brief.sections.first { it.title.startsWith("DISASTER ALERTS") }

        // What a full sort of the same scores would have produced.
        val expected = blips
            .map { Triple(WorldBrief.alertRank(it.label), WorldBrief.ageMinutes(it.label) ?: Int.MAX_VALUE, it) }
            .sortedWith(
                compareByDescending<Triple<Int, Int, GeoBlip>> { it.first }.thenBy { it.second }
            )
            .take(6)
            .map { it.third.label }

        assertEquals(expected, section.entries.map { it.text })
    }

    @Test
    fun aFeedThatIsDownIsMarkedOnTheHeadlineToo() {
        // The mark lives on the section title, and the headline used
        // to return the entry text without it — so the one line of
        // the brief that is always on screen presented a stale entry
        // from a dead feed as the current situation.
        val brief = WorldBrief.build(
            GodEyeUiState(
                gdacs = listOf(GeoBlip(1.0, 1.0, "Flood: Somewhere · RED · 10m ago")),
                loaded = setOf("gdacs"),
                feedTrouble = mapOf("gdacs" to "no answer")
            )
        )
        assertTrue(brief.sections.first().title.contains("FEED DOWN"))
        assertTrue(
            WorldBrief.headline(brief),
            WorldBrief.headline(brief).contains("LAST KNOWN")
        )
    }
}
