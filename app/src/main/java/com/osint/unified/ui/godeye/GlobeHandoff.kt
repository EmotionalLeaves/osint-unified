package com.osint.unified.ui.godeye

import kotlin.math.asin
import kotlin.math.ln
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
     * The slippy-map zoom that shows the same ground.
     *
     * At zoom Z the whole world is 256·2^Z pixels across, so a
     * viewport of w pixels shows 360·w/(256·2^Z) degrees.
     */
    fun tileZoom(spanDegrees: Double, width: Int): Double {
        if (spanDegrees <= 0.0 || width <= 0) return 2.0
        val z = ln(360.0 * width / (TILE * spanDegrees)) / ln(2.0)
        return z.coerceIn(2.0, 19.0)
    }

    /** How much ground a slippy map at this zoom is showing. */
    fun mapSpanDegrees(tileZoom: Double, width: Int): Double {
        if (width <= 0) return 360.0
        return 360.0 * width / (TILE * Math.pow(2.0, tileZoom))
    }

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

    /** Far enough out that tiles are the wrong thing to be looking at. */
    fun shouldReturnToGlobe(tileZoom: Double, width: Int): Boolean =
        mapSpanDegrees(tileZoom, width) > TO_GLOBE_SPAN

    /** How much of the smaller screen dimension the globe fills. */
    private const val GLOBE_FILL = 0.92

    private const val TILE = 256.0

    /** Roughly a large country across the screen. */
    private const val TO_MAP_SPAN = 22.0

    /** Roughly a continent and then some. The gap between this and the
     *  handoff threshold is the hysteresis that stops it flickering. */
    private const val TO_GLOBE_SPAN = 70.0
}
