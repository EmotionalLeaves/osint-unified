package com.osint.unified.ui.godeye

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The maths between what the globe shows and which tiles show the same
 * ground.
 *
 * This used to decide a handoff — the globe was one view and a slippy
 * map was another, and these functions worked out when to swap and
 * where to land so the seam between them was invisible. There is one
 * view now; the globe paints itself with tiles when it is close
 * enough, so what is left is only the scale conversion: how much
 * ground the sphere is showing, and the tile zoom whose pixels match
 * it.
 *
 * Every span here is **ground**, not longitude, and that distinction is
 * the whole of the hard part. The globe is orthographic, so the span it
 * shows is a great-circle angle and means the same thing everywhere.
 * Tiles are Mercator, where a pixel is a fixed amount of longitude and
 * therefore cos(latitude) as much ground. Converting one to the other
 * without that factor asked for twice the tile detail at high latitude
 * as the view could show — a globe of 22 degrees of Norway fetching
 * tiles for 11.
 */
internal object GlobeHandoff {

    /**
     * The angle from the centre of the globe to the furthest corner
     * of the view.
     *
     * spanDegrees measures across the width, because that is the
     * number the handoff needs. It is the wrong number for deciding
     * what is visible: a point at angular distance θ from the camera
     * lands at radius·sin(θ) from the centre of the disc in whatever
     * direction it likes, so the furthest point still on screen is
     * at a corner, not at the left edge. Using the width meant
     * culling markers along the top and bottom of a portrait globe
     * — the same symptom as culling nothing at all, moved from the
     * middle to the edges.
     */
    fun visibleRadiusDegrees(width: Int, height: Int, zoom: Float): Double {
        if (width <= 0 || height <= 0 || zoom <= 0f) return 180.0
        val radius = min(width, height) / 2.0 * GLOBE_FILL * zoom
        val corner = hypot(width.toDouble(), height.toDouble()) / 2.0
        val sine = corner / radius
        // The corner is off the globe entirely, so everything on the
        // near hemisphere is potentially in frame.
        if (sine >= 1.0) return 180.0
        return Math.toDegrees(asin(sine))
    }

    /** How much of the Earth the globe is showing across the screen. */
    fun spanDegrees(width: Int, height: Int, zoom: Float): Double {
        if (width <= 0 || height <= 0 || zoom <= 0f) return 180.0
        val radius = min(width, height) / 2.0 * GLOBE_FILL * zoom
        val sine = (width / 2.0) / radius
        // Past this the screen is narrower than the globe is wide, so
        // the whole visible hemisphere is on it and there is no more
        // Earth to show.
        if (sine >= 1.0) return 180.0
        return 2.0 * Math.toDegrees(asin(sine))
    }

    /**
     * The slippy-map zoom that shows the same ground at this latitude.
     *
     * At zoom Z the whole world is 256·2^Z pixels across, so a viewport
     * of w pixels spans 360·w/(256·2^Z) degrees of *longitude* — which
     * at latitude phi is only cos(phi) as much ground. [spanDegrees] is
     * ground, so it has to be divided back up into longitude before the
     * logarithm.
     */
    fun tileZoom(spanDegrees: Double, width: Int, latitude: Double = 0.0): Double {
        if (spanDegrees <= 0.0 || width <= 0) return 2.0
        val lonSpan = spanDegrees / shrink(latitude)
        val z = ln(360.0 * width / (TILE * lonSpan)) / ln(2.0)
        return z.coerceIn(2.0, 19.0)
    }

    /** How much ground a slippy map at this zoom and latitude shows. */
    fun mapSpanDegrees(tileZoom: Double, width: Int, latitude: Double = 0.0): Double {
        if (width <= 0) return 360.0
        return 360.0 * width / (TILE * Math.pow(2.0, tileZoom)) * shrink(latitude)
    }

    /**
     * How much less ground a degree of longitude is here than on the
     * equator.
     *
     * Floored rather than allowed to reach zero. Above the floor the
     * conversion is exact; below it the map is within a few degrees of
     * the pole, where Mercator is not showing anything anyone is
     * reading off and an unfloored cosine would ask for a tile zoom in
     * the thirties.
     */
    private fun shrink(latitude: Double): Double =
        max(cos(Math.toRadians(latitude.coerceIn(-89.0, 89.0))), MIN_COS)

    /** The globe zoom that shows the same ground as that span. */
    fun globeZoom(spanDegrees: Double, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val half = Math.toRadians(spanDegrees.coerceIn(1.0, 179.0) / 2.0)
        val radius = (width / 2.0) / sin(half)
        val zoom = radius / (min(width, height) / 2.0 * GLOBE_FILL)
        return zoom.coerceIn(0.6, 8.0).toFloat()
    }

    /** How much of the smaller screen dimension the globe fills. */
    private const val GLOBE_FILL = 0.92

    private const val TILE = 256.0

    /** The smallest the longitude-to-ground factor is allowed to get. */
    private const val MIN_COS = 0.05

}
