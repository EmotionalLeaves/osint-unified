package com.osint.unified

import com.osint.unified.ui.godeye.AreaReport
import com.osint.unified.ui.godeye.GeoBlip
import com.osint.unified.ui.godeye.GodEyeUiState
import com.osint.unified.ui.godeye.Quake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaReportTest {

    /** London to Paris, a distance anyone can check: 343 km. */
    @Test
    fun distanceMatchesAKnownPair() {
        val d = AreaReport.distanceKm(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343.0, d, 3.0)
    }

    /** The antipode of a point is half the circumference away. */
    @Test
    fun antipodeIsHalfTheWorldAway() {
        val d = AreaReport.distanceKm(10.0, 20.0, -10.0, -160.0)
        assertEquals(20015.0, d, 5.0)
    }

    @Test
    fun sweepKeepsWhatIsInsideAndDropsWhatIsNot() {
        val state = GodEyeUiState(
            // Heathrow is ~23 km from central London; Paris is not close.
            airports = listOf(
                GeoBlip(51.4700, -0.4543, "LHR Heathrow"),
                GeoBlip(49.0097, 2.5479, "CDG Charles de Gaulle")
            ),
            quakes = listOf(Quake(51.50, -0.13, 2.4))
        )
        val r = AreaReport.build(state, 51.5074, -0.1278, radiusKm = 50.0)
        val airports = r.groups.first { it.kind == "Airport" }
        assertEquals(1, airports.total)
        assertTrue(airports.items.first().label.contains("Heathrow"))
        assertEquals(1, r.groups.first { it.kind == "Earthquake" }.total)
        assertEquals(2, r.total)
    }

    @Test
    fun itemsComeBackNearestFirst() {
        val state = GodEyeUiState(
            airports = listOf(
                GeoBlip(51.90, -0.13, "far"),
                GeoBlip(51.55, -0.13, "near"),
                GeoBlip(51.70, -0.13, "middle")
            )
        )
        val r = AreaReport.build(state, 51.5074, -0.1278, radiusKm = 100.0)
        assertEquals(
            listOf("near", "middle", "far"),
            r.groups.first { it.kind == "Airport" }.items.map { it.label }
        )
    }

    @Test
    fun emptyAreaReportsNothingRatherThanEmptyGroups() {
        val r = AreaReport.build(GodEyeUiState(), 0.0, 0.0, 100.0)
        assertTrue(r.groups.isEmpty())
        assertEquals(0, r.total)
        assertTrue(AreaReport.headline(r).startsWith("Nothing loaded"))
    }

    @Test
    fun zoomPicksAWiderSweepWhenYouAreFurtherOut() {
        assertTrue(AreaReport.radiusForZoom(2.0) > AreaReport.radiusForZoom(8.0))
        assertTrue(AreaReport.radiusForZoom(8.0) > AreaReport.radiusForZoom(14.0))
    }
}
