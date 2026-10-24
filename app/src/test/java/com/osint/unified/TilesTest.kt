package com.osint.unified

import com.osint.unified.ui.godeye.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The arithmetic that lets one view go from a whole planet to a
 * rooftop.
 *
 * The app used to answer the close half of that range with a second
 * widget, which is why there were two cameras, a conversion between
 * them and a hysteresis band to stop them fighting. All that is left
 * of it is this: which tiles cover what the globe is looking at.
 */
class TilesTest {

    @Test
    fun tileEdgesAreWhereEveryOtherSlippyMapPutsThem() {
        // Zoom 0 is one tile holding the world.
        assertEquals(-180.0, Tiles.lonOfTile(0, 0), 1e-9)
        assertEquals(180.0, Tiles.lonOfTile(1, 0), 1e-9)
        assertEquals(85.0511, Tiles.latOfTile(0, 0), 1e-3)
        assertEquals(-85.0511, Tiles.latOfTile(1, 0), 1e-3)
        // And the Greenwich/equator corner is the middle of it.
        assertEquals(0.0, Tiles.lonOfTile(1, 1), 1e-9)
        assertEquals(0.0, Tiles.latOfTile(1, 1), 1e-9)
    }

    @Test
    fun aPlaceLandsInTheTileWhoseEdgesStraddleIt() {
        // Trafalgar Square, at a zoom where a tile is a few streets.
        val lat = 51.508
        val lon = -0.128
        for (z in 4..18) {
            val x = Tiles.tileXOf(lon, z).toInt()
            val y = Tiles.tileYOf(lat, z).toInt()
            assertTrue("z$z lon", Tiles.lonOfTile(x, z) <= lon && lon < Tiles.lonOfTile(x + 1, z))
            // North edge is the larger latitude, so the test is the
            // other way about.
            assertTrue("z$z lat", Tiles.latOfTile(y, z) >= lat && lat > Tiles.latOfTile(y + 1, z))
        }
    }

    @Test
    fun theChosenZoomPutsATilePixelAboutOnAScreenPixel() {
        val width = 1080
        // A tile is 256 pixels, so the number of tiles across the
        // screen should be about width/256 whatever the span.
        listOf(20.0, 5.0, 1.0, 0.05, 0.004).forEach { span ->
            val z = Tiles.zoomFor(span, width, 0.0)
            val across = span / 360.0 * (1 shl z) * 256.0
            assertTrue(
                "span $span chose z$z, which is $across px across a $width px screen",
                across > width / 2.5 && across < width * 2.5
            )
        }
    }

    @Test
    fun theCoverIsBoundedHoweverWideTheViewIs() {
        listOf(0.002, 0.5, 20.0, 120.0, 359.0).forEach { span ->
            val z = Tiles.zoomFor(span, 1080, 0.0)
            val refs = Tiles.cover(51.5, -0.12, span, 1080, 2340, z)
            assertTrue("span $span produced ${refs.size} tiles", refs.size <= 200)
            assertTrue("span $span produced nothing", refs.isNotEmpty())
            // Every one of them is a real tile at its own zoom.
            refs.forEach {
                val side = 1 shl it.z
                assertTrue(it.x in 0 until side)
                assertTrue(it.y in 0 until side)
            }
            // And they are all at the same zoom, because a mixed set
            // would draw at two scales over each other.
            assertEquals(1, refs.map { it.z }.distinct().size)
        }
    }

    @Test
    fun theMiddleOfTheScreenIsFetchedFirst() {
        val z = Tiles.zoomFor(2.0, 1080, 51.5)
        val refs = Tiles.cover(51.5, -0.12, 2.0, 1080, 2340, z)
        val first = refs.first()
        // Against the zoom actually used, not the one asked for:
        // `cover` steps out a level when the view needs more tiles
        // than the cap allows, and comparing with the requested
        // zoom made this test fail for a reason that had nothing to
        // do with ordering.
        assertEquals(Tiles.tileXOf(-0.12, first.z).toInt(), first.x)
        assertEquals(Tiles.tileYOf(51.5, first.z).toInt(), first.y)
    }

    @Test
    fun theDateLineIsCrossedRatherThanWrappedAround() {
        val z = 6
        val refs = Tiles.cover(0.0, 179.6, 5.0, 1080, 1080, z)
        val side = 1 shl z
        assertTrue(refs.isNotEmpty())
        // A naive x0..x1 range would be empty or the whole world; the
        // real answer is a handful of columns either side of the seam.
        assertTrue("${refs.size} columns", refs.map { it.x }.distinct().size <= 6)
        assertTrue(refs.any { it.x == side - 1 })
        assertTrue(refs.any { it.x == 0 })
    }

    @Test
    fun thePolesDoNotAskForTilesThatDoNotExist() {
        val z = Tiles.zoomFor(10.0, 1080, 89.0)
        val refs = Tiles.cover(89.0, 0.0, 10.0, 1080, 2340, z)
        refs.forEach {
            val side = 1 shl it.z
            assertTrue("y ${it.y} at z${it.z}", it.y in 0 until side)
        }
    }

    @Test
    fun aKeyIsUniquePerTile() {
        val seen = HashSet<Long>()
        for (z in 0..19 step 4) {
            val side = 1 shl z
            // Distinct, because at zoom 0 there is one tile and
            // 0, side/3 and side-1 are all 0 — the first run of
            // this test failed on its own duplicate.
            val xs = listOf(0, side / 3, side - 1).distinct()
            val ys = listOf(0, side / 2, side - 1).distinct()
            xs.forEach { x ->
                ys.forEach { y ->
                    assertTrue("$z/$x/$y", seen.add(Tiles.Ref(z, x, y).key))
                }
            }
        }
    }

    /**
     * The globe's zoom and the tile zoom have to agree, or the fade
     * happens at one scale and the imagery arrives at another.
     */
    @Test
    fun theFadeBandSitsWhereTheMosaicRunsOut() {
        assertTrue(Tiles.TILES_FULL_SPAN < Tiles.TILES_FROM_SPAN)
        val width = 1080
        val coarse = Tiles.zoomFor(Tiles.TILES_FROM_SPAN, width, 0.0)
        val fine = Tiles.zoomFor(Tiles.TILES_FULL_SPAN, width, 0.0)
        // One octave of span is one level of zoom.
        assertTrue(abs((fine - coarse) - 1) <= 1)
        assertTrue("coarse was $coarse", coarse in 5..9)
    }

    // ---- the visible set, found by looking ----

    private fun everyPixelCovered(lat: Double, lon: Double, zoom: Float): Pair<Int, Int> {
        val w = 1080; val h = 2340
        val r = minOf(w, h) / 2f * 0.92f * zoom
        val span = com.osint.unified.ui.godeye.GlobeHandoff.spanDegrees(w, h, zoom)
        val refs = Tiles.visible(Tiles.zoomFor(span, w, lat), w, h, w / 2f, h / 2f, r, lat, lon)
        val z = refs.first().z
        val set = refs.map { it.y.toLong() * (1L shl z) + it.x }.toHashSet()
        var miss = 0; var total = 0
        var py = 0f
        while (py < h) {
            var px = 0f
            while (px < w) {
                val pl = com.osint.unified.ui.godeye.Orthographic.unproject(px, py, lat, lon, r, w / 2f, h / 2f)
                if (pl != null && kotlin.math.abs(pl.first) < 85.05) {
                    total++
                    val x = kotlin.math.floor(Tiles.tileXOf(pl.second, z)).toInt().coerceIn(0, (1 shl z) - 1)
                    val y = kotlin.math.floor(Tiles.tileYOf(pl.first, z)).toInt().coerceIn(0, (1 shl z) - 1)
                    if (y.toLong() * (1L shl z) + x !in set) miss++
                }
                px += 9f
            }
            py += 9f
        }
        return miss to total
    }

    /**
     * Every pixel of the planet on screen lands in a tile that is drawn.
     *
     * The rectangle estimate this replaced left the top corners of a
     * portrait view near the poles uncovered, and they drew black.
     */
    @Test
    fun nothingOnScreenIsLeftWithoutATile() {
        listOf(
            Triple(0.0, 0.0, 1f), Triple(51.5, -0.1, 1f), Triple(80.0, 0.0, 1.5f),
            Triple(0.0, 179.9, 4f), Triple(60.0, 10.0, 16f), Triple(-75.0, 100.0, 6f),
            Triple(51.5, -0.12, 48000f)
        ).forEach { (lat, lon, zoom) ->
            val (miss, total) = everyPixelCovered(lat, lon, zoom)
            assertTrue("$lat,$lon zoom $zoom: $miss of $total pixels uncovered", miss * 1000 <= total)
        }
    }

    /** A tall screen does not get stepped out a level, which doubles every tile's size. */
    @Test
    fun aPortraitScreenGetsTheZoomItAskedFor() {
        val w = 1080; val h = 2340
        listOf(4f, 8f, 16f, 2000f).forEach { zoom ->
            val r = minOf(w, h) / 2f * 0.92f * zoom
            val span = com.osint.unified.ui.godeye.GlobeHandoff.spanDegrees(w, h, zoom)
            val want = Tiles.zoomFor(span, w, 0.0)
            val got = Tiles.visible(want, w, h, w / 2f, h / 2f, r, 0.0, 179.9).first().z
            assertEquals("zoom $zoom", want, got)
        }
    }

    @Test
    fun parentsContainTheirChildren() {
        val kids = listOf(Tiles.Ref(6, 32, 21), Tiles.Ref(6, 33, 21), Tiles.Ref(6, 33, 22))
        assertEquals(listOf(Tiles.Ref(5, 16, 10), Tiles.Ref(5, 16, 11)), Tiles.ancestors(kids, 5))
        assertEquals(listOf(Tiles.Ref(0, 0, 0)), Tiles.ancestors(kids, 0))
    }

    @Test
    fun aTileRowMapsBackToItsLatitude() {
        for (z in 1..10) for (y in listOf(0, (1 shl z) / 3, (1 shl z) - 1)) {
            assertEquals(Tiles.latOfTile(y, z), Tiles.latOfRow(y.toDouble(), z), 1e-9)
        }
    }
}
