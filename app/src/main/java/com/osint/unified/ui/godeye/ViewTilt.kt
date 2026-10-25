package com.osint.unified.ui.godeye

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning and tilting the view, so a street can be looked along rather
 * than only down on.
 *
 * The globe is drawn as it always was — straight down, north up — into
 * what is called here the flat picture. This takes that picture to the
 * screen: rotated by the bearing about the middle, then tipped back by
 * the tilt as a camera would see a plane tipped away from it. That
 * mapping of a plane through a pinhole camera is a homography, one 3×3
 * matrix, exact for everything on the ground, and it can be handed to the
 * canvas so every tile, line and marker follows without being touched.
 *
 * What is not on the ground — a roof — goes through [View.project], the
 * same camera with height, which agrees with the matrix at height zero.
 * That is what makes buildings stand up properly when the view is tipped:
 * looked at from the side, their height is finally visible.
 *
 * In the flat picture, x is right and y is down (south, at no bearing).
 * Height is toward the viewer. The camera is [distance] pixels from the
 * middle of the screen; the scale at the middle is exactly one, so with
 * no tilt and no bearing the screen is the flat picture.
 */
internal object ViewTilt {

    /** Past this the horizon comes onto the screen, and there is no sky to draw. */
    const val MAX_TILT = 60f

    /** Tilt fades in from this span of view down to [TILT_FULL_SPAN]. */
    const val TILT_FROM_SPAN = 45.0
    const val TILT_FULL_SPAN = 15.0

    /**
     * How much of the asked-for tilt applies at this span of view.
     *
     * A tipped camera is for streets. Tipping the whole planet just
     * squashes it, so the tilt fades out as the view widens past a
     * region and is gone by the time a continent is in view.
     */
    fun effective(tilt: Float, spanDegrees: Double): Float {
        if (tilt <= 0f) return 0f
        val f = ((TILT_FROM_SPAN - spanDegrees) / (TILT_FROM_SPAN - TILT_FULL_SPAN)).coerceIn(0.0, 1.0)
        return (tilt.coerceAtMost(MAX_TILT) * f).toFloat()
    }

    fun of(width: Int, height: Int, bearing: Float, tilt: Float): View =
        View(width / 2f, height / 2f, bearing, tilt, CAMERA_DISTANCE * maxOf(width, height, 1))

    class View(
        val cx: Float,
        val cy: Float,
        /** Degrees clockwise: the direction that is up the screen. */
        val bearing: Float,
        /** Degrees back from straight down. */
        val tilt: Float,
        val distance: Float
    ) {
        private val cb = cos(Math.toRadians(bearing.toDouble())).toFloat()
        private val sb = sin(Math.toRadians(bearing.toDouble())).toFloat()
        private val ct = cos(Math.toRadians(tilt.toDouble())).toFloat()
        private val st = sin(Math.toRadians(tilt.toDouble())).toFloat()

        /** Nothing to do: the screen is the flat picture. */
        val identity: Boolean = abs(bearing % 360f) < 0.01f && tilt < 0.01f

        /** Flat picture to screen, row-major, as android.graphics.Matrix takes it. */
        val matrix: FloatArray = run {
            val d = distance
            // T(c) · P · R(bearing) · T(-c), multiplied out.
            // R: u' = cb·u − sb·v, v' = sb·u + cb·v
            // P: X = d·u', Y = d·ct·v', W = d − st·v'
            val r00 = cb; val r01 = -sb; val r10 = sb; val r11 = cb
            // P·R as rows acting on (u, v, 1).
            val p0 = floatArrayOf(d * r00, d * r01, 0f)
            val p1 = floatArrayOf(d * ct * r10, d * ct * r11, 0f)
            val p2 = floatArrayOf(-st * r10, -st * r11, d)
            // Then translate the output by (cx, cy): X += cx·W, Y += cy·W.
            val a = floatArrayOf(
                p0[0] + cx * p2[0], p0[1] + cx * p2[1], p0[2] + cx * p2[2],
                p1[0] + cy * p2[0], p1[1] + cy * p2[1], p1[2] + cy * p2[2],
                p2[0], p2[1], p2[2]
            )
            // And the input is (x − cx, y − cy): fold that into the last column.
            floatArrayOf(
                a[0], a[1], a[2] - a[0] * cx - a[1] * cy,
                a[3], a[4], a[5] - a[3] * cx - a[4] * cy,
                a[6], a[7], a[8] - a[6] * cx - a[7] * cy
            )
        }

        /** Screen to flat picture. */
        val inverse: FloatArray = invert(matrix)

        /** A flat-picture point to the screen. False if it is behind the camera. */
        fun toScreen(x: Float, y: Float, out: FloatArray): Boolean = apply(matrix, x, y, out)

        /** A screen point back to the flat picture. False above the horizon. */
        fun toFlat(x: Float, y: Float, out: FloatArray): Boolean {
            if (!apply(inverse, x, y, out)) return false
            // A screen point above the horizon maps to the ground behind
            // the camera; check by going forward again.
            val w = matrix[6] * out[0] + matrix[7] * out[1] + matrix[8]
            return w > 0f
        }

        /**
         * A point [height] pixels above the flat-picture point, on the
         * screen. False if it is at or behind the camera.
         */
        fun project(x: Float, y: Float, height: Float, out: FloatArray): Boolean {
            val u = x - cx
            val v = y - cy
            val uu = cb * u - sb * v
            val vv = sb * u + cb * v
            val w = distance - st * vv - ct * height
            if (w <= distance * NEAR) return false
            out[0] = cx + distance * uu / w
            out[1] = cy + distance * (ct * vv - st * height) / w
            return true
        }

        /** How far from the camera a flat-picture point is, for drawing far things first. */
        fun depth(x: Float, y: Float): Float {
            val u = x - cx
            val v = y - cy
            return distance - st * (sb * u + cb * v)
        }

        /**
         * Screen pixels per flat-picture pixel around this screen point:
         * one at the middle, less toward the horizon. What decides how
         * coarse a tile out there can be.
         */
        fun scaleAt(screenX: Float, screenY: Float): Float {
            if (identity) return 1f
            val f = FloatArray(2)
            if (!toFlat(screenX, screenY, f)) return 0f
            val a = FloatArray(2)
            val b = FloatArray(2)
            val c = FloatArray(2)
            if (!toScreen(f[0], f[1], a) || !toScreen(f[0] + 1f, f[1], b) || !toScreen(f[0], f[1] + 1f, c)) return 0f
            // The longer of the two sides a flat pixel becomes: a tipped
            // view is squashed one way only, and the imagery has to be
            // fine enough for the way it is not.
            val sx = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]))
            val sy = sqrt((c[0] - a[0]) * (c[0] - a[0]) + (c[1] - a[1]) * (c[1] - a[1]))
            return maxOf(sx, sy)
        }
    }

    private fun apply(m: FloatArray, x: Float, y: Float, out: FloatArray): Boolean {
        val w = m[6] * x + m[7] * y + m[8]
        if (abs(w) < 1e-9f) return false
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
        return w > 0f
    }

    private fun invert(m: FloatArray): FloatArray {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-12) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        // Scaled by the determinant's sign only: a homography is defined up
        // to scale, and keeping W positive in front of the camera matters.
        val s = 1.0 / det
        return floatArrayOf(
            (A * s).toFloat(), (-(b * i - c * h) * s).toFloat(), ((b * f - c * e) * s).toFloat(),
            (B * s).toFloat(), ((a * i - c * g) * s).toFloat(), (-(a * f - c * d) * s).toFloat(),
            (C * s).toFloat(), (-(a * h - b * g) * s).toFloat(), ((a * e - b * d) * s).toFloat()
        )
    }

    /** The camera's distance, in screen sizes. Further is flatter perspective. */
    private const val CAMERA_DISTANCE = 1.6f

    /** Nothing closer than this fraction of the camera distance is drawn. */
    private const val NEAR = 0.08f
}
