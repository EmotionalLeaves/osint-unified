package com.osint.unified.ui.godeye

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * How far a finger moves the Earth.
 *
 * The rule a globe has to obey is that the piece of ground under your
 * thumb stays under your thumb. Break it and nothing else about the
 * view can make it feel right: a sphere that runs ahead of the finger
 * reads as slippery, one that lags reads as stuck, and either way the
 * thing being dragged stops feeling like an object.
 *
 * All of it is plain arithmetic on doubles, deliberately. It is the
 * part of the globe most likely to be wrong in a way nobody can see in
 * a screenshot, so it is the part that has to be testable without a
 * device.
 */
internal object GlobeGestures {

    /**
     * Degrees of latitude per pixel of vertical drag.
     *
     * The disc is the sphere seen from infinitely far away, so near the
     * centre an arc of one radian spans exactly one radius of screen.
     * One pixel is therefore 1/r radians, which is 57.3/r degrees.
     *
     * It was 90/r, which is pi/2 where it should have been 180/pi — the
     * two are close enough to look deliberate and are 57% apart. The
     * globe outran the thumb by that much on every drag.
     */
    fun perPixelLat(radius: Float): Double = DEG / max(radius.toDouble(), 1.0)

    /**
     * Degrees of longitude per pixel of horizontal drag.
     *
     * Longitude lines converge, so the same drag is more degrees the
     * further north you are: a pixel near Svalbard is several times the
     * longitude of a pixel on the equator. Without the cosine the globe
     * goes stiff as you approach a pole, which is where a person is
     * most likely to be dragging because it is where the projection
     * puts the least on screen.
     *
     * Floored, because the cosine reaches zero at the pole and the
     * honest answer there is infinite. [MIN_COS] caps a drag at about
     * seven times equator speed, which is fast without being a flick
     * across the date line.
     */
    fun perPixelLon(radius: Float, lat: Float): Double = perPixelLon(radius, lat.toDouble())

    fun perPixelLon(radius: Float, lat: Double): Double {
        val shrink = max(cos(Math.toRadians(lat)), MIN_COS)
        return DEG / (max(radius.toDouble(), 1.0) * shrink)
    }

    /**
     * Where the camera has to go for a pinch to zoom about the fingers
     * rather than about the middle of the screen.
     *
     * Pinching on Tokyo and watching Tokyo slide away while the Pacific
     * grows is the single most obviously wrong thing a map can do, and
     * it is what happens when scale is applied around the view centre.
     *
     * The fix does not need the projection inverted. Ask what ground is
     * under the fingers at the old scale, ask again at the new one, and
     * move the camera by the difference: whatever the first answer was
     * is then back where it started. At the centre both answers are the
     * camera itself and nothing moves, which is the behaviour a pinch
     * in the middle should have.
     *
     * Returns the camera unchanged when the fingers are off the disc —
     * there is no ground under them to keep in place.
     */
    fun anchorPinch(
        lat: Float,
        lon: Float,
        oldRadius: Float,
        newRadius: Float,
        focusX: Float,
        focusY: Float,
        centreX: Float,
        centreY: Float
    ): Pair<Float, Float> {
        val (a, b) = anchorPinch(
            lat.toDouble(), lon.toDouble(), oldRadius, newRadius, focusX, focusY, centreX, centreY
        )
        return a.toFloat() to b.toFloat()
    }

    /** The same, at the precision the camera is held in. */
    fun anchorPinch(
        lat: Double,
        lon: Double,
        oldRadius: Float,
        newRadius: Float,
        focusX: Float,
        focusY: Float,
        centreX: Float,
        centreY: Float
    ): Pair<Double, Double> {
        if (oldRadius <= 0f || newRadius <= 0f) return lat to lon
        if (abs(oldRadius - newRadius) < 1e-4f) return lat to lon
        val before = Orthographic.unproject(
            focusX, focusY, lat, lon, oldRadius, centreX, centreY
        ) ?: return lat to lon
        val after = Orthographic.unproject(
            focusX, focusY, lat, lon, newRadius, centreX, centreY
        ) ?: return lat to lon
        val dLat = before.first - after.first
        val dLon = wrapDegrees(before.second - after.second)
        return (lat + dLat).coerceIn(-89.0, 89.0) to wrapDegrees(lon + dLon)
    }

    /** Degrees folded back into -180..180, for sums that crossed it. */
    fun wrapDegrees(value: Double): Double {
        var v = value
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }

    /** Radians to degrees, written out so the constant cannot be mistyped. */
    private val DEG = 180.0 / Math.PI

    /** The smallest the longitude cosine is allowed to get. */
    private const val MIN_COS = 0.15
}
