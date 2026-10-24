package com.osint.unified

import com.osint.unified.ui.godeye.GlobeHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale maths behind the one-view globe.
 *
 * These used to decide a handoff between a sphere and a flat map.
 * There is no map now — the globe fetches its own tiles — so what is
 * left, and what is tested here, is the conversion: how much ground
 * the sphere shows, and the tile zoom whose pixels match it.
 */
class GlobeHandoffTest {

    private val w = 1080
    private val h = 2000

    /** Zoomed out, the whole visible hemisphere is on the screen. */
    @Test
    fun aGlobeAtRestShowsAHemisphere() {
        assertEquals(180.0, GlobeHandoff.spanDegrees(w, h, 1f), 0.01)
        assertEquals(180.0, GlobeHandoff.spanDegrees(w, h, 0.6f), 0.01)
    }

    @Test
    fun zoomingInShowsLess() {
        val near = GlobeHandoff.spanDegrees(w, h, 5f)
        val far = GlobeHandoff.spanDegrees(w, h, 2f)
        assertTrue(near < far)
        assertTrue(near in 20.0..30.0)
    }

    /**
     * A globe zoom converted to the tile zoom that shows the same
     * ground and back must be the globe zoom you started with.
     */
    @Test
    fun theScaleSurvivesTheRoundTrip() {
        listOf(2.0f, 3.5f, 5.0f, 6.0f).forEach { zoom ->
            val span = GlobeHandoff.spanDegrees(w, h, zoom)
            val tile = GlobeHandoff.tileZoom(span, w)
            val back = GlobeHandoff.globeZoom(GlobeHandoff.mapSpanDegrees(tile, w), w, h)
            assertEquals("round trip at $zoom", zoom, back, 0.01f)
        }
    }

    /** A zoom nobody can use is not offered. */
    @Test
    fun theResultsStayInsideWhatEachViewCanShow() {
        assertEquals(2.0, GlobeHandoff.tileZoom(100000.0, w), 1e-9)
        assertEquals(19.0, GlobeHandoff.tileZoom(0.00001, w), 1e-9)
        assertEquals(8f, GlobeHandoff.globeZoom(0.5, w, h), 1e-6f)
        assertTrue(GlobeHandoff.globeZoom(179.0, w, h) >= 0.6f)
    }

    /** A view with no size yet must not produce a camera from a divide by zero. */
    @Test
    fun anUnmeasuredViewIsNotAnError() {
        assertEquals(180.0, GlobeHandoff.spanDegrees(0, 0, 1f), 1e-9)
        assertEquals(2.0, GlobeHandoff.tileZoom(10.0, 0), 1e-9)
        assertEquals(1f, GlobeHandoff.globeZoom(10.0, 0, 0), 1e-9f)
        assertEquals(360.0, GlobeHandoff.mapSpanDegrees(4.0, 0), 1e-9)
    }

    /** A landscape tablet is a different conversion, not a broken one. */
    @Test
    fun aWideScreenGetsItsOwnArithmetic() {
        val tw = 2560
        val th = 1600
        val span = GlobeHandoff.spanDegrees(tw, th, 3f)
        val back = GlobeHandoff.globeZoom(
            GlobeHandoff.mapSpanDegrees(GlobeHandoff.tileZoom(span, tw), tw), tw, th
        )
        assertEquals(3f, back, 0.01f)
    }

    /**
     * The tiles fetched have to be for the same amount of ground the
     * globe is showing, at every latitude, and for a long time they
     * were not.
     *
     * The globe is orthographic, so its span is a great-circle angle.
     * Tiles are Mercator, where a pixel is a fixed amount of longitude
     * and so only cos(latitude) as much ground. Converting one to the
     * other without that factor meant a globe showing 22 degrees of
     * Norway asking for tiles covering 11 — twice the detail the view
     * could show, on somebody's mobile data.
     */
    @Test
    fun theSameGroundIsAskedForAtEveryLatitude() {
        for (lat in listOf(0.0, 30.0, 45.0, 60.0, 75.0)) {
            val z = GlobeHandoff.tileZoom(22.0, w, lat)
            val covered = GlobeHandoff.mapSpanDegrees(z, w, lat)
            assertEquals("at $lat", 22.0, covered, 0.05)
        }
    }

    /** And back the other way, for the same reason. */
    @Test
    fun theSameGroundConvertsBackToTheGlobe() {
        for (lat in listOf(0.0, 45.0, 70.0)) {
            val span = GlobeHandoff.spanDegrees(w, h, 2.5f)
            val z = GlobeHandoff.tileZoom(span, w, lat)
            val back = GlobeHandoff.globeZoom(GlobeHandoff.mapSpanDegrees(z, w, lat), w, h)
            assertEquals("at $lat", 2.5f, back, 0.02f)
        }
    }

    /**
     * The uncorrected conversion, kept so the size of the mistake is
     * on the record rather than only the fix.
     */
    @Test
    fun ignoringTheProjectionLandedTwiceAsCloseAtSixtyNorth() {
        val naive = GlobeHandoff.tileZoom(22.0, w, 0.0)
        val ground = GlobeHandoff.mapSpanDegrees(naive, w, 60.0)
        assertTrue("expected roughly half of 22 degrees, got $ground", ground in 10.0..12.0)
    }

    /**
     * The visible radius reaches the corner, not the side.
     *
     * A point at angle theta from the camera lands at
     * radius*sin(theta) from the centre of the disc, in whatever
     * direction it happens to lie — so on a portrait screen the
     * furthest point still in frame is in a corner, further out
     * than the half-width the span measures. Culling markers to the
     * width dropped everything along the top and bottom edges.
     */
    @Test
    fun theVisibleRadiusCoversTheCornersOfAPortraitView() {
        listOf(1.2f, 2f, 4f, 8f).forEach { zoom ->
            val span = GlobeHandoff.spanDegrees(w, h, zoom)
            val radius = GlobeHandoff.visibleRadiusDegrees(w, h, zoom)
            assertTrue(
                "zoom $zoom: radius $radius must reach past half the width span $span",
                radius > span / 2.0 - 1e-9
            )
        }
    }

    @Test
    fun aCornerOffTheGlobeMeansNothingIsCulled() {
        // Zoomed out, the whole disc is inside the view and the
        // corner is off the sphere entirely.
        assertEquals(180.0, GlobeHandoff.visibleRadiusDegrees(1080, 2000, 0.6f), 0.0)
        // And a view with no size yet cannot cull anything.
        assertEquals(180.0, GlobeHandoff.visibleRadiusDegrees(0, 0, 2f), 0.0)
    }
}
