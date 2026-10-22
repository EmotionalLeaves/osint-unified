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
        assertEquals("M6.4", brief.sections.first().entries.first().text)
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
}
