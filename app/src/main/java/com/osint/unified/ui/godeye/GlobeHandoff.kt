package com.osint.unified.ui.godeye

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the globe stops and the map starts.
 *
 * The two views were a toggle, which made them two places: you turned
 * the Earth until you found something, pressed MAP, and arrived over
 * the Atlantic at a zoom nobody asked for. What a person actually
 * wants is one view that happens to be a sphere when you are far away
 * and tiles when you are close, and the switch to be something they
 * never have to think about.
 *
 * So the camera is converted rather than reset. A globe turned to a
 * place and zoomed in hands the map that place at the zoom that shows
 * the same amount of ground; a map zoomed out far enough hands the
 * globe back the same way.
 *
 * The thresholds do not overlap, deliberately. Switching at one
 * boundary in both directions means a pinch that lands on it flips the
 * view back and forth under the thumb.
 *
 * Every span here is **ground**, not longitude, and that distinction is
 * the whole of the hard part. The globe is orthographic, so the span it
 * shows is a great-circle angle and means the same thing everywhere.
 * A slippy map is Mercator, where a pixel is a fixed amount of
 * longitude and therefore cos(latitude) as much ground. Converting one
 * to the other without that factor sent a globe showing 22 degrees of
 * Norway to a map showing 11 — the view jumped twice as close as the
 * person had pinched, at exactly the moment the handoff is meant to be
 * invisible.
 */
internal object GlobeHandoff {

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

    /** Close enough that a sphere is the wrong thing to be looking at. */
    fun shouldHandToMap(width: Int, height: Int, zoom: Float): Boolean =
        spanDegrees(width, height, zoom) < TO_MAP_SPAN

    /**
     * Close enough in that the map is plainly what the person wants.
     *
     * Reaching this is what earns the map the right to hand itself
     * back later. Without it, a map that merely opened wide counted as
     * "zoomed out" the moment it appeared.
     */
    fun closeEnoughToArm(tileZoom: Double, width: Int, latitude: Double = 0.0): Boolean =
        mapSpanDegrees(tileZoom, width, latitude) < TO_ARM_SPAN

    /** Far enough out that tiles are the wrong thing to be looking at. */
    fun shouldReturnToGlobe(tileZoom: Double, width: Int, latitude: Double = 0.0): Boolean =
        mapSpanDegrees(tileZoom, width, latitude) > TO_GLOBE_SPAN

    /** How much of the smaller screen dimension the globe fills. */
    private const val GLOBE_FILL = 0.92

    private const val TILE = 256.0

    /** The smallest the longitude-to-ground factor is allowed to get. */
    private const val MIN_COS = 0.05

    /** Roughly a large country across the screen. */
    private const val TO_MAP_SPAN = 22.0

    /** Deliberately a little wider than the handoff boundary. A pinch
     *  that crosses into the map lands exactly on that boundary, and
     *  "exactly on it" is not "inside it" — so arming at the same
     *  number would leave the commonest arrival of all unarmed. */
    private const val TO_ARM_SPAN = 30.0

    /** Roughly a continent and then some. The gap between this and the
     *  handoff threshold is the hysteresis that stops it flickering. */
    private const val TO_GLOBE_SPAN = 70.0
}
