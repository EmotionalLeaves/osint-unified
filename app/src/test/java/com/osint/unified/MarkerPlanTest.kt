package com.osint.unified

import com.osint.unified.ui.godeye.GeoBlip
import com.osint.unified.ui.godeye.MapLayers
import com.osint.unified.ui.godeye.MapWindow
import com.osint.unified.ui.godeye.MARKER_BUDGET
import com.osint.unified.ui.godeye.PendingMarker
import com.osint.unified.ui.godeye.markerBucket
import com.osint.unified.ui.godeye.withoutAge
import com.osint.unified.ui.godeye.planMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which markers belong on the flat map.
 *
 * This used to run inside the map view's update block, on the main
 * thread, on every recomposition — including every keystroke in the
 * search box. Pulled out, it can finally be checked.
 */
class MarkerPlanTest {

    private fun blips(n: Int, lat: Double = 0.0, lon: Double = 0.0) =
        (1..n).map { GeoBlip(lat, lon + it * 0.0001, "blip $it") }

    @Test
    fun pointsOutsideTheViewAreNotPlanned() {
        val layers = MapLayers(
            cameras = listOf(GeoBlip(51.5, -0.1, "London"), GeoBlip(-33.9, 151.2, "Sydney"))
        )
        val plan = planMarkers(layers, MapWindow(north = 60.0, south = 40.0, west = -10.0, east = 10.0))
        assertEquals(listOf("London"), plan.map { it.title })
    }

    /** A map scrolled past the antimeridian has west numerically east of east. */
    @Test
    fun aViewAcrossTheAntimeridianKeepsWhatIsInsideIt() {
        val layers = MapLayers(
            cameras = listOf(
                GeoBlip(0.0, 179.0, "west of the line"),
                GeoBlip(0.0, -179.0, "east of the line"),
                GeoBlip(0.0, 0.0, "nowhere near it")
            )
        )
        val plan = planMarkers(layers, MapWindow(north = 10.0, south = -10.0, west = 170.0, east = -170.0))
        assertEquals(
            setOf("west of the line", "east of the line"),
            plan.map { it.title }.toSet()
        )
    }

    @Test
    fun impossibleCoordinatesAreDropped() {
        val layers = MapLayers(
            cameras = listOf(
                GeoBlip(Double.NaN, 0.0, "nan"),
                GeoBlip(95.0, 0.0, "off the pole"),
                GeoBlip(0.0, 200.0, "off the edge"),
                GeoBlip(1.0, 1.0, "real")
            )
        )
        assertEquals(listOf("real"), planMarkers(layers, null).map { it.title })
    }

    /**
     * The satellite catalogue alone is twelve thousand objects. Taking
     * markers in the order the layers happen to be added would let it
     * eat the whole budget and push everything else off the map.
     */
    @Test
    fun oneHugeLayerCannotCrowdOutASmallOne() {
        val layers = MapLayers(
            satellites = blips(12_000, lat = 10.0),
            cameras = blips(20, lat = 20.0)
        )
        val plan = planMarkers(layers, null)
        assertTrue(plan.size <= MARKER_BUDGET)
        assertEquals(20, plan.count { it.kind == "Traffic camera" })
        assertTrue(plan.count { it.kind == "Satellite" } > 2000)
    }

    /**
     * Feeds return their items in a different order every poll. If what
     * survives thinning depended on list position, stationary things
     * would blink in and out while nothing was moving.
     */
    @Test
    fun thinningKeepsTheSameMarkersWhenTheFeedReordersItself() {
        val one = blips(8000, lat = 10.0)
        val first = planMarkers(MapLayers(satellites = one), null).map { it.title }.toSet()
        val second = planMarkers(MapLayers(satellites = one.reversed()), null).map { it.title }.toSet()
        assertEquals(first, second)
    }

    /** Under budget, nothing is thinned at all. */
    @Test
    fun asmallWorldIsDrawnWhole() {
        val plan = planMarkers(MapLayers(cameras = blips(40)), null)
        assertEquals(40, plan.size)
    }

    /**
     * A named thing keeps its identity when it moves, so its marker is
     * moved rather than destroyed and rebuilt; an unnamed reading has
     * only its place.
     */
    @Test
    fun identityFollowsTheThingNotThePlace() {
        val before = planMarkers(MapLayers(aircraft = emptyList(), cameras = listOf(GeoBlip(1.0, 1.0, "GHOST52"))), null)
        val after = planMarkers(MapLayers(cameras = listOf(GeoBlip(2.0, 2.0, "GHOST52"))), null)
        assertEquals(before.first().identity, after.first().identity)

        val unnamedHere = planMarkers(MapLayers(cameras = listOf(GeoBlip(1.0, 1.0, ""))), null)
        val unnamedThere = planMarkers(MapLayers(cameras = listOf(GeoBlip(2.0, 2.0, ""))), null)
        assertTrue(unnamedHere.first().identity != unnamedThere.first().identity)
    }

    /**
     * The globe projects every point it is handed on every frame, so
     * it is handed a number that can be turned into trigonometry
     * sixty times a second. The fire detections alone are six figures.
     */
    @Test
    fun theGlobeGetsAListItCanProject() {
        val layers = MapLayers(
            fires = blips(120_000, lat = 10.0),
            cameras = blips(30, lat = 20.0),
            satellites = blips(12_000, lat = 30.0)
        )
        val points = com.osint.unified.ui.godeye.thinGlobe(layers.points())
        assertTrue(points.size <= com.osint.unified.ui.godeye.GLOBE_BUDGET)
        // And the small layer is all still there: a hundred thousand
        // fires must not push thirty cameras off the planet.
        assertEquals(30, points.count { it.kind == "Traffic camera" })
        assertTrue(points.count { it.kind == "Satellite" } > 1000)
        assertTrue(points.count { it.kind == "Wildfire" } > 1000)
    }

    /**
     * The budget is spent where the camera is looking.
     *
     * It used to be spent on the whole Earth, once, and never
     * recomputed — and the only view test was at draw time, which
     * is correct and invisible. The visible fraction of a sphere is
     * (1 - cos(span/2))/2, so by the time the globe hands over to
     * tiles at 22 degrees of span, under one per cent of the
     * survivors were on screen, shared between thirty layers. Every
     * layer emptied together as the user zoomed in. This is the
     * regression test for the shape of that: a tight view over one
     * place must not spend its budget on the other side of the
     * planet.
     */
    @Test
    fun theGlobeSpendsItsBudgetWhereTheCameraPoints() {
        val layers = MapLayers(
            fires = blips(60_000, lat = 10.0, lon = 10.0),
            cameras = blips(400, lat = -40.0, lon = -120.0)
        )
        // Pointed at the fires, with the span the globe has just
        // before it gives up and shows tiles.
        val near = com.osint.unified.ui.godeye.thinGlobe(
            layers.points(), centre = 10.0 to 10.0, keepWithinDegrees = 11.0 + 3.0
        )
        assertTrue(near.isNotEmpty())
        assertEquals(0, near.count { it.kind == "Traffic camera" })
        // And the fires now get the whole budget rather than half of
        // it, because nothing else is in view to share with.
        assertTrue(near.size > com.osint.unified.ui.godeye.GLOBE_BUDGET / 2)
    }

    @Test
    fun aWholeEarthViewStillThinsEverything() {
        val layers = MapLayers(
            fires = blips(60_000, lat = 10.0, lon = 10.0),
            cameras = blips(400, lat = -40.0, lon = -120.0)
        )
        val all = com.osint.unified.ui.godeye.thinGlobe(layers.points())
        assertEquals(400, all.count { it.kind == "Traffic camera" })
        assertTrue(all.size <= com.osint.unified.ui.godeye.GLOBE_BUDGET)
    }

    @Test
    fun angularDistanceIsMeasuredOnTheSphereAndNotOnAGrid() {
        val within = com.osint.unified.ui.godeye.withinDegrees(0.0, 0.0, 0.0, 10.0, 11.0)
        assertTrue(within)
        assertTrue(!com.osint.unified.ui.godeye.withinDegrees(0.0, 0.0, 0.0, 12.0, 11.0))
        // Ten degrees of longitude near the pole is a short way, and a
        // flat comparison on the numbers would call it far.
        assertTrue(com.osint.unified.ui.godeye.withinDegrees(85.0, 0.0, 85.0, 30.0, 11.0))
        // Across the antimeridian, where a flat comparison breaks.
        assertTrue(com.osint.unified.ui.godeye.withinDegrees(0.0, 179.0, 0.0, -179.0, 5.0))
    }

    /** Thinning twice gives the same answer, however the feed reorders. */
    @Test
    fun theGlobeSubsetIsStable() {
        val one = blips(40_000, lat = 10.0)
        val first = com.osint.unified.ui.godeye.thinGlobe(MapLayers(fires = one).points())
            .map { it.title }.toSet()
        val second = com.osint.unified.ui.godeye.thinGlobe(
            MapLayers(fires = one.reversed()).points()
        ).map { it.title }.toSet()
        assertEquals(first, second)
    }

    /**
     * A marker is the same marker a minute later.
     *
     * Most of these labels end with how old the reading is, and the
     * identity was the whole label — so at every minute boundary the
     * map destroyed and rebuilt a Marker it had gone to trouble to
     * reuse, and the thinning hash moved, which made stationary
     * markers blink in and out of any layer over its quota.
     */
    @Test
    fun theAgeOnTheEndIsNotPartOfWhatSomethingIs() {
        assertEquals("Buoy 41001", withoutAge("Buoy 41001 · 40m ago"))
        assertEquals(
            withoutAge("Buoy 41001 · 40m ago"),
            withoutAge("Buoy 41001 · 41m ago")
        )
        assertEquals("Falcon 9", withoutAge("Falcon 9 · in 3h"))
        assertEquals("Station X", withoutAge("Station X · undated"))
        // A middle dot that is part of the label itself stays.
        assertEquals("Tokyo · M6.1", withoutAge("Tokyo · M6.1 · 2h ago"))
        assertEquals("Plain label", withoutAge("Plain label"))
    }

    /** And the consequence: the same reading keeps its bucket. */
    @Test
    fun aMarkerKeepsItsPlaceInTheThinningAsItAges() {
        val young = PendingMarker(
            kind = "buoy", lat = 10.0, lon = 20.0, color = 0, sizeDp = 8,
            title = "Buoy 41001 · 40m ago", clickable = true, url = null
        )
        val older = young.copy(title = "Buoy 41001 · 41m ago")
        assertEquals(young.identity, older.identity)
        assertEquals(markerBucket(young), markerBucket(older))
    }

    // ---- The window a map reports before it has been laid out ----
    //
    // Every layer switched on and nothing on the map: that was this.
    // osmdroid answers `boundingBox` from the MapView's current size,
    // and before layout the size is zero, so it reports a north edge
    // equal to its south edge. The planner culled every marker on
    // Earth against it, and nothing re-read the window until a scroll
    // or zoom event — so a map that was opened and merely looked at
    // stayed empty.

    @Test
    fun aBoxWithNoHeightIsNotAWindow() {
        assertNull(MapWindow.of(north = 0.0, south = 0.0, west = -180.0, east = 180.0))
        assertNull(MapWindow.of(north = -10.0, south = 10.0, west = -180.0, east = 180.0))
    }

    @Test
    fun aBoxOfNaNIsNotAWindow() {
        assertNull(MapWindow.of(Double.NaN, 0.0, -1.0, 1.0))
        assertNull(MapWindow.of(1.0, 0.0, Double.NaN, 1.0))
    }

    @Test
    fun aSliverOfLongitudeIsNotAWindow() {
        assertNull(MapWindow.of(north = 1.0, south = -1.0, west = 10.0, east = 10.0001))
    }

    @Test
    fun theWholeWorldIsAWindow() {
        // osmdroid reports the fully zoomed-out map with west == east,
        // which the contains() test already treats as everything.
        val w = MapWindow.of(north = 85.0, south = -85.0, west = 0.0, east = 0.0)
        assertNotNull(w)
        assertTrue(w!!.contains(51.5, -0.1))
        assertTrue(w.contains(-33.9, 151.2))
    }

    @Test
    fun anOrdinaryViewIsAWindow() {
        val w = MapWindow.of(north = 52.0, south = 51.0, west = -1.0, east = 1.0)
        assertNotNull(w)
        assertTrue(w!!.contains(51.5, -0.1))
    }

    @Test
    fun noWindowMeansNoCulling() {
        // Null is what the map hands the planner until it knows what it
        // can see, and the planner must read it as "show everything"
        // rather than "show nothing".
        val layers = MapLayers(
            cameras = listOf(GeoBlip(51.5, -0.1, "London"), GeoBlip(-33.9, 151.2, "Sydney"))
        )
        assertEquals(2, planMarkers(layers, null).size)
    }
}
