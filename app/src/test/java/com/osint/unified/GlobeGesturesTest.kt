package com.osint.unified

import com.osint.unified.ui.godeye.GlobeGestures
import com.osint.unified.ui.godeye.Orthographic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Does the ground stay under the thumb?
 *
 * Every test here answers that one question in pixels, by moving the
 * camera the way a gesture would and then projecting the piece of
 * ground that was under the finger to see where it ended up. A test
 * that asserted the formula instead of the outcome would have passed
 * on the old constant, which was out by 57% and felt like it.
 */
class GlobeGesturesTest {

    private val r = 500f
    private val cx = 540f
    private val cy = 1000f

    /** Forward orthographic, so the tests can check where a point lands. */
    private fun project(
        lat: Double, lon: Double, lat0: Double, lon0: Double
    ): Pair<Float, Float>? {
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val la0 = Math.toRadians(lat0)
        val lo0 = Math.toRadians(lon0)
        val cosC = sin(la0) * sin(la) + cos(la0) * cos(la) * cos(lo - lo0)
        if (cosC < 0) return null
        val x = r * cos(la) * sin(lo - lo0)
        val y = r * (cos(la0) * sin(la) - sin(la0) * cos(la) * cos(lo - lo0))
        return (cx + x).toFloat() to (cy - y).toFloat()
    }

    private fun slipAfterDrag(lat0: Float, panX: Float, panY: Float): Double {
        val ground = Orthographic.unproject(cx, cy, lat0.toDouble(), 0.0, r, cx, cy)!!
        val perLat = GlobeGestures.perPixelLat(r)
        val perLon = GlobeGestures.perPixelLon(r, lat0)
        val newLat = (lat0 + panY * perLat).coerceIn(-89.0, 89.0)
        val newLon = GlobeGestures.wrapDegrees(0.0 - panX * perLon)
        val landed = project(ground.first, ground.second, newLat, newLon) ?: return 1e9
        // Where the finger now is: it started at the centre of the disc.
        return hypot((landed.first - (cx + panX)).toDouble(), (landed.second - (cy + panY)).toDouble())
    }

    @Test
    fun aDragCarriesTheGroundWithIt() {
        // Vertical is exact at every latitude: tilting the camera north
        // moves the centre of the disc along a meridian, which projects
        // to a straight line down the screen.
        for (lat0 in listOf(0f, 30f, 60f, 80f)) {
            assertTrue("vertical slipped at lat $lat0", slipAfterDrag(lat0, 0f, 40f) < 0.2)
        }
        // Horizontal is exact on the equator and approaches it
        // elsewhere. See the test below for why it is not exact
        // everywhere and why that is the right answer.
        assertTrue(slipAfterDrag(0f, 40f, 0f) < 0.2)
        for (lat0 in listOf(15f, 30f, 45f, 60f)) {
            assertTrue(
                "horizontal slipped too far at lat $lat0",
                slipAfterDrag(lat0, 40f, 0f) < 40f * 0.08
            )
        }
    }

    /**
     * Why a sideways drag is not pixel-exact away from the equator.
     *
     * Turning the globe sideways means rotating it about its own axis,
     * and away from the equator that carries the point under the thumb
     * along a small circle rather than along the screen. At sixty north
     * a forty-pixel drag leaves the ground about three pixels high of
     * the finger. The error is in the sideways direction only — the
     * horizontal distance is right — and it is path-independent, so it
     * is the geometry rather than an accumulation of rounding.
     *
     * It could be made exact by rotating about whichever axis is
     * perpendicular to the drag, which is what a trackball does. That
     * is worse: it rolls the globe, north stops being up, and every
     * label and meridian on screen tilts with it. A map keeps north up
     * and accepts the three pixels.
     */
    @Test
    fun aSidewaysDragNearThePoleCurves() {
        val near = slipAfterDrag(60f, 40f, 0f)
        assertTrue("the curve has vanished — did the axis change?", near > 1.0)
        assertTrue("and it should still be small", near < 4.0)
        // Path-independent: twenty frames of two pixels land in the
        // same place as one frame of forty, which is what tells you
        // this is the rotation and not drift.
        assertTrue(kotlin.math.abs(slipInSteps(60f, 40f, 20) - near) < 0.05)
    }

    /** The same drag delivered as [steps] frames rather than one jump. */
    private fun slipInSteps(lat0: Float, panX: Float, steps: Int): Double {
        val ground = Orthographic.unproject(cx, cy, lat0.toDouble(), 0.0, r, cx, cy)!!
        var lon = 0.0
        val each = panX / steps
        repeat(steps) { lon -= each * GlobeGestures.perPixelLon(r, lat0) }
        val landed = project(ground.first, ground.second, lat0.toDouble(), lon) ?: return 1e9
        return hypot((landed.first - (cx + panX)).toDouble(), (landed.second - cy).toDouble())
    }

    /**
     * The bug this replaced, kept as a test so it cannot come back: a
     * constant of 90/r instead of 180/pi/r makes the globe outrun the
     * thumb by more than half a finger-width on a short drag.
     */
    @Test
    fun theOldConstantOvershotByHalfAgain() {
        val ground = Orthographic.unproject(cx, cy, 0.0, 0.0, r, cx, cy)!!
        val wrong = 90.0 / r
        val landed = project(ground.first, ground.second, 40.0 * wrong, 0.0)!!
        val slip = abs(landed.second - (cy + 40f))
        assertTrue("the old constant was fine after all?", slip > 20f)
        // And the new one is not.
        assertTrue(slipAfterDrag(0f, 0f, 40f) < 1.0)
    }

    /** Near a pole a sideways drag has to cover more longitude per pixel
     *  or the globe goes stiff exactly where there is least on screen. */
    @Test
    fun longitudeSpeedsUpTowardsThePoles() {
        assertTrue(GlobeGestures.perPixelLon(r, 60f) > GlobeGestures.perPixelLon(r, 0f) * 1.9)
        // But not without limit: at the pole the honest answer is
        // infinite and a map that flings itself round the world is not
        // an improvement on one that sticks.
        assertTrue(GlobeGestures.perPixelLon(r, 89.9f) < GlobeGestures.perPixelLon(r, 0f) * 7.0)
    }

    @Test
    fun aPinchInTheMiddleMovesNothing() {
        val (lat, lon) = GlobeGestures.anchorPinch(20f, 10f, r, r * 1.2f, cx, cy, cx, cy)
        assertEquals(20f, lat, 1e-4f)
        assertEquals(10f, lon, 1e-4f)
    }

    @Test
    fun aPinchOffCentreKeepsTheGroundUnderTheFingers() {
        val focusX = cx + 200f
        val focusY = cy - 100f
        val ground = Orthographic.unproject(focusX, focusY, 20.0, 0.0, r, cx, cy)!!
        val grown = r * 1.1f
        val (lat, lon) = GlobeGestures.anchorPinch(20f, 0f, r, grown, focusX, focusY, cx, cy)
        // Projected at the new scale, which the helper below does not
        // know about — so compare against the unanchored case instead,
        // which is the comparison that matters.
        val anchored = Orthographic.unproject(
            focusX, focusY, lat.toDouble(), lon.toDouble(), grown, cx, cy
        )!!
        val unanchored = Orthographic.unproject(focusX, focusY, 20.0, 0.0, grown, cx, cy)!!
        fun off(p: Pair<Double, Double>) =
            hypot(p.first - ground.first, GlobeGestures.wrapDegrees(p.second - ground.second))
        assertTrue("anchoring made it worse", off(anchored) < off(unanchored) / 4.0)
        assertTrue("still drifting", off(anchored) < 0.4)
    }

    /** Fingers off the edge of the disc have no ground under them. */
    @Test
    fun aPinchOffTheGlobeLeavesTheCameraAlone() {
        val (lat, lon) = GlobeGestures.anchorPinch(20f, 10f, r, r * 1.2f, cx + 900f, cy, cx, cy)
        assertEquals(20f, lat, 1e-6f)
        assertEquals(10f, lon, 1e-6f)
    }
}
