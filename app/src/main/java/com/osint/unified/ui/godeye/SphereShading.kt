package com.osint.unified.ui.godeye

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning an equirectangular world image into a lit sphere, one pixel
 * at a time.
 *
 * The globe used to be a radial gradient with coastlines stroked on
 * top, because an outline is all a vector file can give. NASA's Blue
 * Marble is a photograph of the whole Earth in exactly the projection
 * this needs — latitude and longitude are row and column — so the only
 * work is the inverse orthographic, and that is cheap enough to do per
 * pixel on a phone.
 *
 * No Android in this file on purpose: this is the part that can be
 * wrong in a way a person cannot see by looking (a hemisphere flipped,
 * a terminator on the wrong side, a seam at the antimeridian), so it is
 * the part that has to be testable without a device.
 */
object SphereShading {

    /** An equirectangular image: row 0 is 90°N, column 0 is 180°W. */
    class Texture(val pixels: IntArray, val width: Int, val height: Int) {
        init {
            require(pixels.size >= width * height) { "texture smaller than its size" }
        }
    }

    /**
     * Writes a lit globe of diameter [d] into [out] as ARGB.
     *
     * [out] must hold d*d ints; pixels outside the disc are left fully
     * transparent so the caller can draw a star field behind it.
     *
     * [bilinear] is for the still frame. While the globe is being
     * dragged it is rendered small and scaled up, and sampling four
     * texels to blur something that is already being thrown away costs
     * frames for nothing.
     */
    fun render(
        out: IntArray,
        d: Int,
        day: Texture,
        night: Texture?,
        lat0: Double,
        lon0: Double,
        sunLat: Double,
        sunLon: Double,
        bilinear: Boolean = true
    ) {
        if (d <= 0 || out.size < d * d) return
        val r = d / 2.0
        val sinLat0 = sin(Math.toRadians(lat0))
        val cosLat0 = cos(Math.toRadians(lat0))
        val lon0Rad = Math.toRadians(lon0)
        val sinSun = sin(Math.toRadians(sunLat))
        val cosSun = cos(Math.toRadians(sunLat))
        val sunLonRad = Math.toRadians(sunLon)

        var i = 0
        for (py in 0 until d) {
            // Screen y grows downward; the projection's y grows north.
            val y = (r - py - 0.5) / r
            val yy = y * y
            for (px in 0 until d) {
                val x = (px - r + 0.5) / r
                val rho2 = x * x + yy
                if (rho2 > 1.0) {
                    out[i++] = 0
                    continue
                }
                // sin c = rho and cos c = sqrt(1 - rho^2), which removes
                // the arcsine the textbook inverse starts with: the
                // whole projection is one sqrt, one asin and one atan2.
                val cosC = sqrt(1.0 - rho2)
                val sinLat = cosC * sinLat0 + y * cosLat0
                val lat = asin(sinLat.coerceIn(-1.0, 1.0))
                val lon = lon0Rad + atan2(x, cosC * cosLat0 - y * sinLat0)

                val u = (lon / TWO_PI + 0.5)
                // The texture wraps in longitude, so a point a hair past
                // the antimeridian samples the far edge rather than
                // clamping into a seam.
                val uw = u - Math.floor(u)
                val v = 0.5 - lat / Math.PI

                val base = if (bilinear) sampleSmooth(day, uw, v) else sample(day, uw, v)

                // Lambert on a sphere, from the real subsolar point.
                val cosLat = sqrt((1.0 - sinLat * sinLat).coerceAtLeast(0.0))
                val lit = (sinLat * sinSun + cosLat * cosSun * cos(lon - sunLonRad))
                val dayMix = smoothstep(-0.09, 0.13, lit)
                val lambert = 0.28 + 0.72 * lit.coerceIn(0.0, 1.0)

                var rr = ((base ushr 16) and 0xFF) * lambert * dayMix
                var gg = ((base ushr 8) and 0xFF) * lambert * dayMix
                var bb = (base and 0xFF) * lambert * dayMix

                if (dayMix < 1.0) {
                    val dark = 1.0 - dayMix
                    // The night side is not black: the continents stay
                    // faintly visible under the lights, the way they do
                    // from orbit, and without that the dark half reads
                    // as a hole cut in the planet.
                    rr += ((base ushr 16) and 0xFF) * 0.055 * dark
                    gg += ((base ushr 8) and 0xFF) * 0.055 * dark
                    bb += (base and 0xFF) * 0.075 * dark
                    if (night != null) {
                        val lights = if (bilinear) sampleSmooth(night, uw, v)
                        else sample(night, uw, v)
                        // Added rather than mixed: city light is light,
                        // and a mix would dim the land it sits on.
                        rr += ((lights ushr 16) and 0xFF) * dark * 0.95
                        gg += ((lights ushr 8) and 0xFF) * dark * 0.80
                        bb += (lights and 0xFF) * dark * 0.55
                    }
                }

                // Limb darkening. A sphere is dimmer at the edge because
                // you are looking through it at a glancing angle, and
                // this one line is most of what stops the disc reading
                // as a printed circle.
                val edge = 1.0 - 0.38 * rho2 * rho2
                rr *= edge
                gg *= edge
                bb *= edge

                out[i++] = 0xFF000000.toInt() or
                    (clamp255(rr) shl 16) or (clamp255(gg) shl 8) or clamp255(bb)
            }
        }
    }

    private fun sample(t: Texture, u: Double, v: Double): Int {
        val x = (u * t.width).toInt().coerceIn(0, t.width - 1)
        val y = (v * t.height).toInt().coerceIn(0, t.height - 1)
        return t.pixels[y * t.width + x]
    }

    /**
     * Bilinear, wrapping in longitude and clamping in latitude.
     *
     * Wrapping matters: without it the column either side of the
     * antimeridian blends with the opposite edge of the image and
     * paints a bright seam down the Pacific.
     */
    private fun sampleSmooth(t: Texture, u: Double, v: Double): Int {
        val fx = u * t.width - 0.5
        val fy = v * t.height - 0.5
        val x0 = Math.floor(fx).toInt()
        val y0 = Math.floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val x1 = Math.floorMod(x0 + 1, t.width)
        val xa = Math.floorMod(x0, t.width)
        val ya = y0.coerceIn(0, t.height - 1)
        val y1 = (y0 + 1).coerceIn(0, t.height - 1)

        val p00 = t.pixels[ya * t.width + xa]
        val p10 = t.pixels[ya * t.width + x1]
        val p01 = t.pixels[y1 * t.width + xa]
        val p11 = t.pixels[y1 * t.width + x1]

        fun lerp(shift: Int): Int {
            val a = ((p00 ushr shift) and 0xFF) * (1 - tx) + ((p10 ushr shift) and 0xFF) * tx
            val b = ((p01 ushr shift) and 0xFF) * (1 - tx) + ((p11 ushr shift) and 0xFF) * tx
            return (a * (1 - ty) + b * ty).toInt().coerceIn(0, 255)
        }
        return (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    /** Hermite fade, so the terminator is a soft band and not a cut. */
    internal fun smoothstep(from: Double, to: Double, at: Double): Double {
        if (to <= from) return if (at >= to) 1.0 else 0.0
        val t = ((at - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun clamp255(v: Double): Int =
        if (v <= 0.0) 0 else if (v >= 255.0) 255 else v.toInt()

    private const val TWO_PI = Math.PI * 2
}
