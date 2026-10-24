package com.osint.unified

import com.osint.unified.ui.godeye.SphereShading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The globe, which is a photograph of the Earth resampled into a
 * sphere one pixel at a time.
 *
 * This is the part that can be wrong in a way nobody can see by
 * looking at it: a hemisphere flipped, the terminator on the wrong
 * side, a seam down the Pacific. So it is tested against a texture
 * whose every pixel says where it came from.
 */
class SphereShadingTest {

    /** Red is the column, green is the row. Any pixel names its origin. */
    private fun atlas(w: Int = 360, h: Int = 180): SphereShading.Texture {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or ((x % 256) shl 16) or ((y % 256) shl 8)
            }
        }
        return SphereShading.Texture(pixels, w, h)
    }

    private fun white(w: Int = 8, h: Int = 4) =
        SphereShading.Texture(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h)

    private fun black(w: Int = 8, h: Int = 4) =
        SphereShading.Texture(IntArray(w * h) { 0xFF000000.toInt() }, w, h)

    private val d = 101

    private fun render(
        lat0: Double,
        lon0: Double,
        sunLat: Double = 0.0,
        sunLon: Double = 0.0,
        day: SphereShading.Texture = atlas(),
        night: SphereShading.Texture? = null
    ): IntArray {
        val out = IntArray(d * d)
        SphereShading.render(out, d, day, night, lat0, lon0, sunLat, sunLon, bilinear = false)
        return out
    }

    private fun centre(px: IntArray) = px[(d / 2) * d + d / 2]

    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF

    /**
     * The middle of the disc is the point the camera is over. With the
     * sun there too, the shading is exactly one, so the pixel is the
     * texel and nothing else.
     */
    @Test
    fun theCentreOfTheDiscIsThePointTheCameraIsOver() {
        val px = centre(render(lat0 = 0.0, lon0 = 0.0, sunLat = 0.0, sunLon = 0.0))
        // u = 0.5 of 360 columns, v = 0.5 of 180 rows.
        assertEquals(180, red(px))
        assertEquals(90, green(px))
    }

    @Test
    fun turningTheGlobeMovesWhichTexelIsUnderTheCamera() {
        val east = centre(render(lat0 = 0.0, lon0 = 90.0, sunLat = 0.0, sunLon = 90.0))
        // u = 0.75 of 360 columns is column 270, which is 14 in a byte.
        assertEquals(270 % 256, red(east))
        val north = centre(render(lat0 = 45.0, lon0 = 0.0, sunLat = 45.0, sunLon = 0.0))
        // v = 0.5 - 45/180 = 0.25 of 180 rows.
        assertEquals(45, green(north))
    }

    /** Outside the disc nothing is drawn, so a sky can show through. */
    @Test
    fun theCornersAreTransparent() {
        val px = render(0.0, 0.0)
        assertEquals(0, px[0])
        assertEquals(0, px[d - 1])
        assertEquals(0, px[(d - 1) * d])
        assertEquals(0, px[d * d - 1])
        assertTrue((centre(px) ushr 24) and 0xFF == 0xFF)
    }

    /**
     * The night side is dark, and it is the side away from the sun —
     * not the bottom of the screen, not the left.
     */
    @Test
    fun theSunDecidesWhichHalfIsLit() {
        val px = render(
            lat0 = 0.0, lon0 = 0.0, sunLat = 0.0, sunLon = 90.0, day = white()
        )
        val row = (d / 2) * d
        val east = px[row + d - 6]
        val west = px[row + 5]
        assertTrue("east should be lit", red(east) > red(west) + 40)
    }

    /** With the sun behind the planet the whole disc is night. */
    @Test
    fun theFarSideIsNotBlackButItIsClose() {
        val px = centre(
            render(lat0 = 0.0, lon0 = 0.0, sunLat = 0.0, sunLon = 180.0, day = white())
        )
        assertTrue("land stays faintly visible", red(px) in 1..40)
    }

    /** City lights are added on the dark side and nowhere else. */
    @Test
    fun cityLightsOnlyBurnAtNight() {
        val night = white()
        val lit = centre(
            render(0.0, 0.0, sunLat = 0.0, sunLon = 0.0, day = black(), night = night)
        )
        val dark = centre(
            render(0.0, 0.0, sunLat = 0.0, sunLon = 180.0, day = black(), night = night)
        )
        assertEquals(0, red(lit))
        assertTrue("lights on the night side", red(dark) > 180)
    }

    /**
     * The antimeridian is where a wrapping texture tears if the sampler
     * clamps instead of wrapping, and it tears as a bright line down
     * the Pacific that looks like a rendering artefact rather than a
     * bug in arithmetic.
     */
    @Test
    fun thereIsNoSeamAtTheAntimeridian() {
        val w = 64
        val h = 32
        // Black everywhere except the two columns either side of the
        // wrap, which are opposite extremes: a clamping sampler blends
        // them and produces a line, a wrapping one does not.
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        for (y in 0 until h) {
            pixels[y * w] = 0xFFFFFFFF.toInt()
            pixels[y * w + w - 1] = 0xFF000000.toInt()
        }
        val texture = SphereShading.Texture(pixels, w, h)
        val out = IntArray(d * d)
        SphereShading.render(
            out, d, texture, null, 0.0, 180.0, 0.0, 180.0, bilinear = true
        )
        // The camera is on the wrap. Nothing either side of the centre
        // may be brighter than the white column itself.
        val row = (d / 2) * d
        for (x in d / 2 - 3..d / 2 + 3) {
            assertTrue("no overshoot at the wrap", red(out[row + x]) <= 255)
        }
        // And the white column must actually be found there.
        assertTrue(red(out[row + d / 2]) > 100)
    }

    @Test
    fun smoothstepIsAFadeAndNotAStep() {
        assertEquals(0.0, SphereShading.smoothstep(0.0, 1.0, -1.0), 1e-9)
        assertEquals(1.0, SphereShading.smoothstep(0.0, 1.0, 2.0), 1e-9)
        assertEquals(0.5, SphereShading.smoothstep(0.0, 1.0, 0.5), 1e-9)
        assertTrue(SphereShading.smoothstep(0.0, 1.0, 0.25) < 0.25)
        // Degenerate range: a hard step rather than a divide by zero.
        assertEquals(1.0, SphereShading.smoothstep(1.0, 1.0, 1.0), 1e-9)
        assertEquals(0.0, SphereShading.smoothstep(1.0, 1.0, 0.5), 1e-9)
    }
}

/**
 * The allocation-free projection has to be the projection.
 *
 * drawLines calls it once per vertex of every outline on every
 * frame, so it hoists the camera's own trig out of the loop and
 * writes into a caller-owned array instead of returning an
 * object. Neither of those may change where a point lands.
 */
class ProjectIntoTest {

    @org.junit.Test
    fun itAgreesWithTheProjectionItReplaces() {
        val out = FloatArray(2)
        val cameras = listOf(0.0 to 0.0, 51.5 to -0.1, -33.9 to 151.2, 89.9 to 10.0, -90.0 to 0.0)
        val points = listOf(
            0.0 to 0.0, 10.0 to 20.0, -45.0 to 170.0, 89.0 to -179.0,
            51.5 to -0.1, -60.0 to 60.0, 0.0 to 180.0
        )
        cameras.forEach { (lat0, lon0) ->
            val la0 = Math.toRadians(lat0)
            val sin0 = kotlin.math.sin(la0)
            val cos0 = kotlin.math.cos(la0)
            points.forEach { (lat, lon) ->
                val expected = com.osint.unified.ui.godeye.Orthographic.project(
                    lat, lon, lat0, lon0, 500f, 540f, 960f
                )
                val visible = com.osint.unified.ui.godeye.Orthographic.projectInto(
                    lat, lon, sin0, cos0, lon0, 500f, 540f, 960f, out
                )
                val where = "camera $lat0,$lon0 point $lat,$lon"
                org.junit.Assert.assertEquals(where, expected.visible, visible)
                org.junit.Assert.assertEquals(where, expected.x, out[0], 1e-3f)
                org.junit.Assert.assertEquals(where, expected.y, out[1], 1e-3f)
            }
        }
    }
}
