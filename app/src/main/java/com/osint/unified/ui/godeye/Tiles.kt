package com.osint.unified.ui.godeye

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Which slippy tiles cover what the globe is looking at.
 *
 * The app used to answer this question by handing the whole view to
 * a second widget with its own camera, its own gestures and its own
 * marker overlay, and calling the result a map. Two views meant two
 * of everything, a hysteresis band to stop them fighting over the
 * boundary, and a moment — right where the person was pinching —
 * when the thing under their thumb changed character.
 *
 * There is only one view now. Past the zoom where a whole-Earth
 * mosaic runs out of pixels, the sphere is painted with the same
 * satellite tiles the map used to show, projected through the same
 * camera as everything else on it. The arithmetic for choosing and
 * placing them is here, where it can be tested without a screen.
 */
internal object Tiles {

    /** One tile, by the three numbers that name it. */
    data class Ref(val z: Int, val x: Int, val y: Int) {
        /** Packed for use as a cache key without allocating. */
        val key: Long get() = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()
    }

    /** West edge of tile column [x] at zoom [z], in degrees. */
    fun lonOfTile(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    /** North edge of tile row [y] at zoom [z], in degrees. */
    fun latOfTile(y: Int, z: Int): Double {
        val n = PI * (1.0 - 2.0 * y.toDouble() / (1 shl z))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** The fractional tile column a longitude falls in. */
    fun tileXOf(lon: Double, z: Int): Double =
        (Orthographic.normaliseLon(lon) + 180.0) / 360.0 * (1 shl z)

    /** The fractional tile row a latitude falls in. */
    fun tileYOf(lat: Double, z: Int): Double {
        val phi = Math.toRadians(lat.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT))
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0 * (1 shl z)
    }

    /**
     * The zoom whose tile pixels are about the size of screen pixels.
     *
     * Any coarser and the imagery is visibly soft; any finer and four
     * times as many tiles are fetched for detail the screen cannot
     * resolve, on somebody's mobile data.
     */
    fun zoomFor(spanDegrees: Double, width: Int, latitude: Double): Int =
        Math.round(GlobeHandoff.tileZoom(spanDegrees, width, latitude)).toInt()
            .coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)

    /**
     * Every tile the view can see, nearest the middle first.
     *
     * Ordered that way because they arrive one at a time over a
     * phone connection, and the middle of the screen is where the
     * person is looking. Bounded by [MAX_TILES]: rather than refuse
     * to draw, it steps out a zoom level, which covers the same
     * ground with a quarter as many tiles at half the detail — the
     * same trade a slippy map makes while its own tiles load.
     */
    fun cover(
        lat: Double,
        lon: Double,
        spanDegrees: Double,
        width: Int,
        height: Int,
        zoom: Int
    ): List<Ref> {
        if (width <= 0 || height <= 0 || spanDegrees <= 0.0) return emptyList()
        var z = zoom.coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        while (true) {
            val refs = coverAt(lat, lon, spanDegrees, width, height, z)
            // The floor is a floor. At the lowest zoom the whole world
            // is sixteen tiles and a tall screen asks for all of them,
            // so stepping out again is not available and the cap has
            // to be applied by truncation — nearest the middle first,
            // which is what the sort above is for. It only arises at
            // spans where the imagery is fully transparent anyway, but
            // a bound that is not a bound is worse than no bound.
            if (z <= MIN_TILE_ZOOM) return refs.take(MAX_TILES)
            if (refs.size <= MAX_TILES) return refs
            z--
        }
    }

    private fun coverAt(
        lat: Double,
        lon: Double,
        spanDegrees: Double,
        width: Int,
        height: Int,
        z: Int
    ): List<Ref> {
        val side = 1 shl z
        // Ground degrees across and down. The globe's span is ground
        // in both directions — it is orthographic — so the vertical
        // one is simply scaled by the aspect, and only the horizontal
        // one has to be turned back into longitude.
        val latSpan = spanDegrees * height / width
        val lonSpan = spanDegrees / max(cos(Math.toRadians(lat.coerceIn(-89.0, 89.0))), MIN_COS)
        val north = (lat + latSpan / 2.0).coerceAtMost(MERCATOR_LIMIT)
        val south = (lat - latSpan / 2.0).coerceAtLeast(-MERCATOR_LIMIT)
        if (north <= south) return emptyList()

        val y0 = floor(tileYOf(north, z)).toInt().coerceIn(0, side - 1)
        val y1 = floor(tileYOf(south, z)).toInt().coerceIn(0, side - 1)
        // Whole world across, which happens at low zoom and in
        // landscape near the poles.
        val wholeWorld = lonSpan >= 360.0
        val x0 = if (wholeWorld) 0 else floor(tileXOf(lon - lonSpan / 2.0, z)).toInt()
        val x1 = if (wholeWorld) side - 1 else floor(tileXOf(lon + lonSpan / 2.0, z)).toInt()
        val columns = if (wholeWorld) {
            (0 until side).toList()
        } else {
            // Crossing the date line means x1 wrapped below x0, so
            // the run is counted rather than ranged.
            val count = ((x1 - x0 + side) % side) + 1
            (0 until min(count, side)).map { (x0 + it).mod(side) }
        }
        val centreX = tileXOf(lon, z)
        val centreY = tileYOf(lat, z)
        val out = ArrayList<Ref>(columns.size * (y1 - y0 + 1))
        for (y in y0..y1) for (x in columns) out.add(Ref(z, x, y))
        // Nearest the middle first, so the first tiles to arrive are
        // the ones being looked at.
        return out.sortedBy { r ->
            val dx = (r.x + 0.5) - centreX
            val dy = (r.y + 0.5) - centreY
            dx * dx + dy * dy
        }
    }

    /**
     * The span below which the bundled mosaic has nothing left to
     * give and tiles are worth their bytes.
     *
     * The whole-Earth image is a few thousand pixels across, so by
     * the time a screen is showing a large country it is being
     * magnified past its own resolution. This is the same number the
     * old handoff switched views at, which is not a coincidence: it
     * is where the imagery ran out, and that was always the real
     * reason for the switch.
     */
    const val TILES_FROM_SPAN = 22.0

    /** Fully opaque by here, so the change is a fade and not a cut. */
    const val TILES_FULL_SPAN = 11.0

    /** Below this there is no point asking for tiles at all. */
    private const val MIN_TILE_ZOOM = 2

    /** Esri's World Imagery stops here. */
    const val MAX_TILE_ZOOM = 19

    /**
     * At most this many tiles for one view.
     *
     * Forty-eight was arithmetic done on a square screen, and a
     * phone is not one: 1080 by 2340 is five columns by eleven rows
     * at the zoom whose pixels match the screen's, which is
     * fifty-five. So the cap was hit on every portrait view, the
     * code stepped out a zoom level to fit, and the globe was
     * permanently rendering at half the detail it had asked for.
     * The test that caught it was checking something else.
     *
     * Ninety-six covers a tall screen with a margin, and is about
     * twelve megabytes of bitmap and two of somebody's data if none
     * of them are cached.
     */
    private const val MAX_TILES = 96

    /** Where the Mercator projection is cut off, as every slippy map does. */
    private const val MERCATOR_LIMIT = 85.0511

    private const val MIN_COS = 0.05
}
