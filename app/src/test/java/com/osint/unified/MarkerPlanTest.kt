package com.osint.unified

import com.osint.unified.ui.godeye.GeoBlip
import com.osint.unified.ui.godeye.GlobePoint
import com.osint.unified.ui.godeye.GLOBE_BUDGET
import com.osint.unified.ui.godeye.MapLayers
import com.osint.unified.ui.godeye.globeBucket
import com.osint.unified.ui.godeye.planGlobe
import com.osint.unified.ui.godeye.thinGlobe
import com.osint.unified.ui.godeye.withinDegrees
import com.osint.unified.ui.godeye.withoutAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which markers the globe draws.
 *
 * Half of this file used to test the flat map's planner — `planMarkers`,
 * `MapWindow`, a rectangle cull. The map is gone, so those are gone;
 * what stays is the sphere's planner and the fairness logic the two
 * always shared, which is the part worth keeping a test on. It runs off
 * the main thread, only when the layers or the camera change.
 */
class MarkerPlanTest {

    private fun blips(n: Int, lat: Double = 0.0, lon: Double = 0.0) =
        (1..n).map { GeoBlip(lat, lon + it * 0.0001, "blip $it") }

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
        val points = thinGlobe(layers.points())
        assertTrue(points.size <= GLOBE_BUDGET)
        // And the small layer is all still there: a hundred thousand
        // fires must not push thirty cameras off the planet.
        assertEquals(30, points.count { it.kind == "Traffic camera" })
        assertTrue(points.count { it.kind == "Satellite" } > 1000)
        assertTrue(points.count { it.kind == "Wildfire" } > 1000)
    }

    /** Under budget, nothing is thinned at all, and the total is kept. */
    @Test
    fun aSmallWorldIsDrawnWhole() {
        val plan = planGlobe(MapLayers(cameras = blips(40)).points())
        assertEquals(40, plan.points.size)
        assertEquals(40, plan.total)
    }

    /**
     * The budget is spent where the camera is looking.
     *
     * It used to be spent on the whole Earth, once, and never
     * recomputed. The visible fraction of a sphere is
     * (1 - cos(span/2))/2, so by the time the globe is close enough
     * to show tiles, under one per cent of the survivors were on
     * screen, shared between thirty layers — every layer emptied
     * together as the user zoomed in. A tight view over one place
     * must not spend its budget on the other side of the planet.
     */
    @Test
    fun theGlobeSpendsItsBudgetWhereTheCameraPoints() {
        val layers = MapLayers(
            fires = blips(60_000, lat = 10.0, lon = 10.0),
            cameras = blips(400, lat = -40.0, lon = -120.0)
        )
        val near = thinGlobe(layers.points(), centre = 10.0 to 10.0, keepWithinDegrees = 14.0)
        assertTrue(near.isNotEmpty())
        assertEquals(0, near.count { it.kind == "Traffic camera" })
        // The fires get the whole budget rather than half, because
        // nothing else is in view to share with.
        assertTrue(near.size > GLOBE_BUDGET / 2)
    }

    @Test
    fun aWholeEarthViewStillThinsEverything() {
        val layers = MapLayers(
            fires = blips(60_000, lat = 10.0, lon = 10.0),
            cameras = blips(400, lat = -40.0, lon = -120.0)
        )
        val all = thinGlobe(layers.points())
        assertEquals(400, all.count { it.kind == "Traffic camera" })
        assertTrue(all.size <= GLOBE_BUDGET)
    }

    @Test
    fun angularDistanceIsMeasuredOnTheSphereAndNotOnAGrid() {
        assertTrue(withinDegrees(0.0, 0.0, 0.0, 10.0, 11.0))
        assertTrue(!withinDegrees(0.0, 0.0, 0.0, 12.0, 11.0))
        // Ten degrees of longitude near the pole is a short way, and a
        // flat comparison on the numbers would call it far.
        assertTrue(withinDegrees(85.0, 0.0, 85.0, 30.0, 11.0))
        // Across the antimeridian, where a flat comparison breaks.
        assertTrue(withinDegrees(0.0, 179.0, 0.0, -179.0, 5.0))
    }

    /** Thinning twice gives the same answer, however the feed reorders. */
    @Test
    fun theGlobeSubsetIsStable() {
        val one = blips(40_000, lat = 10.0)
        val first = thinGlobe(MapLayers(fires = one).points()).map { it.title }.toSet()
        val second = thinGlobe(MapLayers(fires = one.reversed()).points()).map { it.title }.toSet()
        assertEquals(first, second)
    }

    /**
     * A named thing keeps its identity when it moves, so its marker is
     * reused rather than destroyed and rebuilt; an unnamed reading has
     * only its place.
     */
    @Test
    fun identityFollowsTheThingNotThePlace() {
        val here = GlobePoint("Traffic camera", 1.0, 1.0, 0, 8, "GHOST52", true)
        val there = here.copy(lat = 2.0, lon = 2.0)
        assertEquals(here.identity, there.identity)

        val unnamedHere = GlobePoint("Traffic camera", 1.0, 1.0, 0, 8, "", true)
        val unnamedThere = unnamedHere.copy(lat = 2.0, lon = 2.0)
        assertTrue(unnamedHere.identity != unnamedThere.identity)
    }

    /**
     * A marker is the same marker a minute later.
     *
     * Most of these labels end with how old the reading is, and the
     * identity was the whole label — so at every minute boundary the
     * globe rebuilt a point it had reused, and the thinning hash
     * moved, which made stationary markers blink in and out of any
     * layer over its quota.
     */
    @Test
    fun theAgeOnTheEndIsNotPartOfWhatSomethingIs() {
        assertEquals("Buoy 41001", withoutAge("Buoy 41001 · 40m ago"))
        assertEquals(withoutAge("Buoy 41001 · 40m ago"), withoutAge("Buoy 41001 · 41m ago"))
        assertEquals("Falcon 9", withoutAge("Falcon 9 · in 3h"))
        assertEquals("Station X", withoutAge("Station X · undated"))
        // A middle dot that is part of the label itself stays.
        assertEquals("Tokyo · M6.1", withoutAge("Tokyo · M6.1 · 2h ago"))
        assertEquals("Plain label", withoutAge("Plain label"))
    }

    /**
     * The largest layer cannot be thinned to nothing.
     *
     * With more layers than budget left, the share floors to one and
     * every single-marker layer is satisfied in one pass. If those use
     * up exactly the budget, the loop used to end with the big layer
     * still waiting and no quota — so it was dropped entirely, the one
     * layer most worth sampling.
     */
    @Test
    fun theBiggestLayerIsNeverStrandedAtZero() {
        fun p(kind: String, i: Int) = GlobePoint(kind, 1.0, 1.0 + i * 0.001, 0, 8, "$kind $i", true)
        val points = listOf(p("a", 0), p("b", 0), p("c", 0)) + (1..1000).map { p("big", it) }
        val thinned = thinGlobe(points, budget = 3)
        assertTrue("the big layer vanished", thinned.any { it.kind == "big" })
        // And the small ones are still all there.
        assertEquals(setOf("a", "b", "c", "big"), thinned.map { it.kind }.toSet())
    }

    /** And the consequence: the same reading keeps its bucket as it ages. */
    @Test
    fun aPointKeepsItsBucketAsItAges() {
        val young = GlobePoint("buoy", 10.0, 20.0, 0, 8, "Buoy 41001 · 40m ago", true)
        val older = young.copy(title = "Buoy 41001 · 41m ago")
        assertEquals(young.identity, older.identity)
        assertEquals(globeBucket(young), globeBucket(older))
    }
}
