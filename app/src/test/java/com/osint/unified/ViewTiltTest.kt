package com.osint.unified

import com.osint.unified.ui.godeye.ViewTilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTiltTest {

    private val w = 1080
    private val h = 2340
    private val out = FloatArray(2)

    @Test
    fun noTurnAndNoTiltIsTheFlatPicture() {
        val v = ViewTilt.of(w, h, 0f, 0f)
        assertTrue(v.identity)
        v.toScreen(100f, 300f, out)
        assertEquals(100f, out[0], 1e-3f); assertEquals(300f, out[1], 1e-3f)
    }

    /** The middle of the screen stays put whatever the camera does. */
    @Test
    fun theMiddleIsFixed() {
        val v = ViewTilt.of(w, h, 37f, 50f)
        v.toScreen(540f, 1170f, out)
        assertEquals(540f, out[0], 1e-2f); assertEquals(1170f, out[1], 1e-2f)
    }

    @Test
    fun screenToFlatAndBackIsTheSamePoint() {
        val v = ViewTilt.of(w, h, -63f, 45f)
        for ((x, y) in listOf(10f to 10f, 1000f to 2300f, 540f to 300f, 200f to 1900f)) {
            val flat = FloatArray(2)
            assertTrue(v.toFlat(x, y, flat))
            v.toScreen(flat[0], flat[1], out)
            assertEquals(x, out[0], 0.05f); assertEquals(y, out[1], 0.05f)
        }
    }

    /** A building's foot, projected in 3D, lands where the ground matrix puts it. */
    @Test
    fun heightZeroAgreesWithTheGround() {
        val v = ViewTilt.of(w, h, 20f, 55f)
        val a = FloatArray(2)
        v.toScreen(700f, 900f, a)
        v.project(700f, 900f, 0f, out)
        assertEquals(a[0], out[0], 1e-2f); assertEquals(a[1], out[1], 1e-2f)
    }

    /** Tipped back, the far side shrinks and a roof rises up the screen. */
    @Test
    fun tiltingShowsHeight() {
        val flat = ViewTilt.of(w, h, 0f, 0f)
        val tipped = ViewTilt.of(w, h, 0f, 50f)
        // Straight down, a roof is over its own foot.
        flat.project(540f, 1170f, 200f, out)
        assertEquals(1170f, out[1], 1e-2f)
        // Tipped, it is higher up the screen.
        tipped.project(540f, 1170f, 200f, out)
        assertTrue(out[1] < 1100f)
        // The far side is smaller than the near side.
        assertTrue(tipped.scaleAt(540f, 200f) < tipped.scaleAt(540f, 2200f))
        assertEquals(1f, tipped.scaleAt(540f, 1170f), 0.01f)
    }

    /** A quarter turn puts what was to the right at the top. */
    @Test
    fun bearingTurnsThePicture() {
        val v = ViewTilt.of(w, h, -90f, 0f)
        v.toScreen(640f, 1170f, out)
        assertEquals(540f, out[0], 1e-2f)
        assertEquals(1070f, out[1], 1e-2f)
    }

    @Test
    fun theTiltIsForStreetsNotPlanets() {
        assertEquals(0f, ViewTilt.effective(45f, 10.0), 0f)
        assertEquals(45f, ViewTilt.effective(45f, 0.1), 0f)
        assertEquals(ViewTilt.MAX_TILT, ViewTilt.effective(80f, 0.01), 0f)
        assertFalse(ViewTilt.effective(45f, 0.6) in listOf(0f, 45f))
    }
}
