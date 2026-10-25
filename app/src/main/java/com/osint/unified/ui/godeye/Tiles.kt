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
     * Every tile the globe can see at zoom [zoom], found by looking.
     *
     * [cover] estimated a rectangle of latitude and longitude from the
     * centre and the span, which is right at the equator and wrong
     * everywhere the view is not a rectangle in those terms: the top of
     * a portrait view near the pole needs more longitude than its centre
     * does, a whole-Earth view is a disc, and across a pole every
     * longitude is in view. The corners it missed drew black.
     *
     * So the screen is sampled — a grid inside, denser along the edges
     * and around the planet's rim, where tiles are thin — and each
     * sample is turned back into a place and then a tile. A tile that is
     * on screen is hit, whatever shape the view is.
     *
     * Bounded like [cover]: more than [MAX_TILES] and it steps out a
     * level, which covers the same ground with a quarter of the tiles.
     */
    fun visible(
        zoom: Int,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        lat0: Double,
        lon0: Double,
        /** How much finer than the screen the quality setting asks for. */
        detail: Float = 1f,
        /**
         * The turn and tilt between the flat picture and the screen, if
         * any. Tipped back, the far ground is smaller on the screen, and
         * gets coarser tiles: up to four levels coarser at the horizon.
         */
        view: ViewTilt.View? = null
    ): List<Ref> {
        if (width <= 0 || height <= 0 || radius <= 0f) return emptyList()
        var z = zoom.coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        val tilted = view != null && !view.identity
        while (true) {
            val refs = sample(z, width, height, cx, cy, radius, lat0, lon0, if (tilted) view else null)
            val cap = capFor(z, (width * detail).toInt(), (height * detail).toInt()) * (if (tilted) 2 else 1)
            if (refs.size <= cap || z <= MIN_TILE_ZOOM) {
                // Finest and nearest first, which is the order they are
                // fetched in; drawn in reverse, so coarse and far go down
                // first and the detail lands on top.
                return refs.sortedWith(
                    compareByDescending<Ref> { it.z }.thenBy { r ->
                        val side = 1 shl r.z
                        var dx = (r.x + 0.5) - tileXOf(lon0, r.z)
                        if (dx > side / 2.0) dx -= side
                        if (dx < -side / 2.0) dx += side
                        val dy = (r.y + 0.5) - tileYOf(lat0, r.z)
                        dx * dx + dy * dy
                    }
                )
            }
            z--
        }
    }

    private fun sample(
        z: Int,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        lat0: Double,
        lon0: Double,
        view: ViewTilt.View?
    ): Set<Ref> {
        val out = HashSet<Ref>(256)
        val flat = FloatArray(2)
        // A point in the flat picture, at a level.
        fun hitFlat(fx: Float, fy: Float, level: Int) {
            val place = Orthographic.unproject(fx, fy, lat0, lon0, radius, cx, cy) ?: return
            if (place.first > MERCATOR_LIMIT || place.first < -MERCATOR_LIMIT) return
            val side = 1 shl level
            val x = floor(tileXOf(place.second, level)).toInt().coerceIn(0, side - 1)
            val y = floor(tileYOf(place.first, level)).toInt().coerceIn(0, side - 1)
            out.add(Ref(level, x, y))
        }
        // A point on the screen: through the view, at the level its distance allows.
        fun hit(px: Float, py: Float) {
            if (view == null) {
                hitFlat(px, py, z)
                return
            }
            if (!view.toFlat(px, py, flat)) return
            val scale = view.scaleAt(px, py)
            if (scale <= 0f) return
            val drop = Math.round(-ln(scale.toDouble()) / ln(2.0)).toInt().coerceIn(0, MAX_DROP)
            hitFlat(flat[0], flat[1], (z - drop).coerceAtLeast(MIN_TILE_ZOOM))
        }
        // Inside: a tile is two hundred pixels or more across at the zoom
        // chosen for it, so a grid finer than that cannot step over one.
        var py = 0f
        while (py <= height) {
            var px = 0f
            while (px <= width) {
                hit(px, py)
                px += GRID_PX
            }
            hit(width.toFloat(), py)
            py += GRID_PX
        }
        // The edges, finely: a tile that only just reaches into the view
        // is a sliver along one of them.
        var e = 0f
        while (e <= width) {
            hit(e, 0f); hit(e, height.toFloat()); e += EDGE_PX
        }
        e = 0f
        while (e <= height) {
            hit(0f, e); hit(width.toFloat(), e); e += EDGE_PX
        }
        // And rings just inside the rim, where a whole-Earth view squeezes
        // tiles into slivers thinner than the grid. One ring missed about
        // one pixel in a hundred there; four miss none. Never tilted: the
        // tilt has faded out long before the rim is on screen.
        if (view == null && radius < width + height) {
            for (ring in RIM_RINGS) {
                val rimR = radius * ring
                for (i in 0 until RIM_SAMPLES) {
                    val a = 2.0 * PI * i / RIM_SAMPLES
                    val px = cx + rimR * kotlin.math.cos(a).toFloat()
                    val py2 = cy + rimR * kotlin.math.sin(a).toFloat()
                    if (px in 0f..width.toFloat() && py2 in 0f..height.toFloat()) hitFlat(px, py2, z)
                }
            }
        }
        return out
    }

    /**
     * The zoom whose tile pixels are at least as fine as the screen's, at
     * the middle of the globe.
     *
     * Measured at the middle, where an orthographic globe is largest: a
     * degree there is radius·π/180 pixels. The old choice measured the
     * span across the screen's width instead, which on a whole-Earth view
     * is the average over a hemisphere — so the middle of the planet, the
     * part being looked at, was drawn from tiles a level and a half too
     * coarse. That was the blur in the Blue Marble.
     *
     * Bundled levels, which cost nothing, are rounded up; live ones are
     * rounded up unless within a third of a level of the one below.
     */
    fun zoomForRadius(radius: Float, latitude: Double, detail: Float): Int {
        if (radius <= 0f) return MIN_TILE_ZOOM
        val perScreen = 2.0 * PI * radius * detail / 256.0
        val bundled = ln(perScreen) / ln(2.0)
        if (kotlin.math.ceil(bundled) <= BUNDLED_MAX_ZOOM) {
            return kotlin.math.ceil(bundled).toInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        }
        // Mercator is finer toward the poles by 1/cos(latitude).
        val shrink = kotlin.math.max(cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))), 0.05)
        val exact = ln(perScreen * shrink) / ln(2.0)
        return kotlin.math.ceil(exact - LIVE_ROUND_DOWN).toInt()
            .coerceIn(BUNDLED_MAX_ZOOM, MAX_TILE_ZOOM)
    }

    /**
     * The tiles one or more levels up that contain these.
     *
     * A parent holds its four children's ground at half the detail, so
     * drawing parents first gives every not-yet-arrived tile something
     * to show — exact, and computed by shifting, not by looking again.
     */
    fun ancestors(refs: List<Ref>, level: Int): List<Ref> {
        if (refs.isEmpty()) return emptyList()
        return refs.asSequence()
            .filter { it.z >= level }
            .map { Ref(level, it.x shr (it.z - level), it.y shr (it.z - level)) }
            .distinct()
            .toList()
    }

    /** The fractional latitude of a tile row, for bending a tile to the sphere. */
    fun latOfRow(row: Double, z: Int): Double {
        val n = PI * (1.0 - 2.0 * row / (1 shl z))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Zoom levels bundled with the app, which cost no data and no wait. */
    const val BUNDLED_MAX_ZOOM = 5

    private const val GRID_PX = 48f

    /** The most levels coarser the far distance of a tipped view may go. */
    private const val MAX_DROP = 4

    /** Within this of the level below, the level below is sharp enough. */
    private const val LIVE_ROUND_DOWN = 0.33
    private const val EDGE_PX = 16f
    private const val RIM_SAMPLES = 360
    private val RIM_RINGS = floatArrayOf(0.999f, 0.995f, 0.985f, 0.96f, 0.92f)

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
    private const val MIN_TILE_ZOOM = 1

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
    // Sized so a tall or high-resolution screen never has to step out a
    // level. At 96 a 1080x2340 portrait screen routinely did — every
    // tile then drawn at twice its size, which on its own makes the
    // imagery soft — and a 1440x3120 one needs up to about 190 at the
    // zoom that rounds up.
    private const val MAX_TILES = 200

    /**
     * The bundled levels are allowed more.
     *
     * The cap exists for mobile data, and bundled tiles cost none. It
     * was stepping the half-zoomed globe — the rim in view, where tiles
     * crowd together — from zoom 5 down to 4, a quarter of the detail,
     * to save reading a few more files out of the APK. Every view
     * measured on a 1080 x 2340 phone needs fewer than this; the tile
     * memory holds 400, and bundled levels draw nothing beneath them.
     */
    private const val MAX_BUNDLED_TILES = 600

    /**
     * The live cap, scaled to the screen.
     *
     * The number of tiles a view needs goes with its pixel count, and
     * [MAX_TILES] was measured on a 1080 x 2340 phone. On a 1440 x 3120
     * one the same cap stepped twelve views in eight hundred down a
     * zoom — half the sharpness, on the phones with the sharpest
     * screens. Never below the measured figure, never above what the
     * tile memory holds with room for the level beneath.
     */
    private fun capFor(z: Int, width: Int, height: Int): Int {
        if (z <= BUNDLED_MAX_ZOOM) return MAX_BUNDLED_TILES
        val scale = width.toDouble() * height / (1080.0 * 2340.0)
        return (MAX_TILES * scale).toInt().coerceIn(MAX_TILES / 2, MAX_LIVE_TILES)
    }

    /** The most live tiles one view may ask for, whatever the screen. */
    private const val MAX_LIVE_TILES = 420

    /** Where the Mercator projection is cut off, as every slippy map does. */
    private const val MERCATOR_LIMIT = 85.0511

    private const val MIN_COS = 0.05
}
