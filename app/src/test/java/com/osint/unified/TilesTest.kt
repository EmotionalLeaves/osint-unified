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
            assertTrue("span $span produced ${refs.size} tiles", refs.size <= 96)
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
}
