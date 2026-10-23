package com.osint.unified

import com.osint.unified.ui.godeye.GlobeHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the globe stops and the map starts.
 *
 * The two used to be a toggle, which made them two places: you turned
 * the Earth until you found something, pressed MAP, and arrived over
 * the Atlantic. They are one view now, and the arithmetic that keeps
 * the camera is what makes that true.
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
     * The whole point: a camera converted to the other view and back
     * must be the camera you started with.
     */
    @Test
    fun theCameraSurvivesTheRoundTrip() {
        listOf(2.0f, 3.5f, 5.0f, 6.0f).forEach { zoom ->
            val span = GlobeHandoff.spanDegrees(w, h, zoom)
            val tile = GlobeHandoff.tileZoom(span, w)
            val back = GlobeHandoff.globeZoom(GlobeHandoff.mapSpanDegrees(tile, w), w, h)
            assertEquals("round trip at $zoom", zoom, back, 0.01f)
        }
    }

    /**
     * The thresholds must not overlap. Switching at one boundary in
     * both directions means a pinch that lands on it flips the view
     * back and forth under the thumb.
     */
    @Test
    fun theTwoThresholdsLeaveAGapBetweenThem() {
        // The globe hands over somewhere around zoom six.
        assertFalse(GlobeHandoff.shouldHandToMap(w, h, 5f))
        assertTrue(GlobeHandoff.shouldHandToMap(w, h, 6f))

        // At the tile zoom it hands over to, the map must not want to
        // hand straight back.
        val tile = GlobeHandoff.tileZoom(GlobeHandoff.spanDegrees(w, h, 6f), w)
        assertFalse(GlobeHandoff.shouldReturnToGlobe(tile, w))

        // And the globe zoom the map hands back must not immediately
        // want to hand over again.
        val returning = 4.0
        assertTrue(GlobeHandoff.shouldReturnToGlobe(returning, w))
        val globe = GlobeHandoff.globeZoom(GlobeHandoff.mapSpanDegrees(returning, w), w, h)
        assertFalse(GlobeHandoff.shouldHandToMap(w, h, globe))
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
     * The map opens at whatever zoom it was last left at, and that is
     * usually wide. The test on its own says "hand back to the globe"
     * for most of the range a map is ever at — which is why arriving
     * on the map sent you straight back to the sphere and the toggle
     * looked broken. The arithmetic was right and the rule around it
     * was not: it takes a deliberate zoom out now.
     */
    @Test
    fun aMapAtItsUsualZoomWantsToBeAGlobe() {
        assertTrue(GlobeHandoff.shouldReturnToGlobe(2.5, w))
        assertTrue(GlobeHandoff.shouldReturnToGlobe(4.0, w))
        assertFalse(GlobeHandoff.shouldReturnToGlobe(5.0, w))
    }
}
