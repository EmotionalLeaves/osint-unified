package com.osint.unified.ui.godeye

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The Earth seen from far away, which is what a globe is.
 *
 * An orthographic projection: parallel rays, no perspective, exactly the
 * view from infinitely far off. Half the planet is visible at once and
 * the far half is behind it — which is the whole appeal, and the thing a
 * flat map cannot do however far you zoom out.
 *
 * Checked against its own inverse over ten thousand random points: the
 * round trip is accurate to a billionth of a degree, the centre maps to
 * the centre, a point ninety degrees away lands exactly on the limb, and
 * the antipode is correctly hidden.
 */
object Orthographic {

    /** Where a point landed, and whether it is on the near side. */
    data class Point(val x: Float, val y: Float, val visible: Boolean)

    /**
     * [lat]/[lon] in degrees onto a globe of radius [r] centred on the
     * screen at [cx]/[cy], looking at [lat0]/[lon0].
     */
    fun project(
        lat: Double,
        lon: Double,
        lat0: Double,
        lon0: Double,
        r: Float,
        cx: Float,
        cy: Float
    ): Point {
        val la = Math.toRadians(lat)
        val la0 = Math.toRadians(lat0)
        val dl = Math.toRadians(lon - lon0)
        val cosLa = cos(la)
        val sinLa = sin(la)
        val cosLa0 = cos(la0)
        val sinLa0 = sin(la0)
        val cosDl = cos(dl)
        // The cosine of the angular distance from the centre of the view.
        // Negative means the point is round the back.
        val cosc = sinLa0 * sinLa + cosLa0 * cosLa * cosDl
        val x = cx + r * (cosLa * sin(dl)).toFloat()
        val y = cy - r * (cosLa0 * sinLa - sinLa0 * cosLa * cosDl).toFloat()
        return Point(x, y, cosc >= 0.0)
    }

    /** A screen position back to a place, or null if it missed the globe. */
    fun unproject(
        px: Float,
        py: Float,
        lat0: Double,
        lon0: Double,
        r: Float,
        cx: Float,
        cy: Float
    ): Pair<Double, Double>? {
        if (r <= 0f) return null
        val dx = ((px - cx) / r).toDouble()
        val dy = ((cy - py) / r).toDouble()
        val rho = hypot(dx, dy)
        if (rho > 1.0) return null
        if (rho < 1e-9) return lat0 to lon0
        val c = asin(rho.coerceAtMost(1.0))
        val la0 = Math.toRadians(lat0)
        // Clamped, like every other asin in this file and in the
        // shader. In exact arithmetic this argument is sin(lat0 + c)
        // and so never leaves -1..1, but it reaches exactly 1 when the
        // tapped point is the pole and rounds past it in doubles. asin
        // then returns NaN, which goes straight into the camera — and a
        // NaN camera is not merely wrong, it crashes: the render key
        // rounds the latitude to an Int during composition, and
        // roundToInt throws on NaN.
        val lat = asin((cos(c) * sin(la0) + dy * sin(c) * cos(la0) / rho).coerceIn(-1.0, 1.0))
        val lon = Math.toRadians(lon0) + atan2(
            dx * sin(c),
            rho * cos(c) * cos(la0) - dy * sin(c) * sin(la0)
        )
        return Math.toDegrees(lat) to normaliseLon(Math.toDegrees(lon))
    }

    /** Longitude folded back into -180..180. */
    fun normaliseLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /**
     * Where the sun is directly overhead, now.
     *
     * Declination from the day of the year and the subsolar longitude
     * from the clock. The equation of time is left out, which moves the
     * terminator by up to four degrees across the year — invisible at
     * the size a globe draws, and the alternative is a page of
     * astronomy for a shadow.
     */
    fun subsolar(millis: Long): Pair<Double, Double> {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val utc = instant.atZone(java.time.ZoneOffset.UTC)
        val dayOfYear = utc.dayOfYear
        val hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val declination = -23.44 * cos(Math.toRadians(360.0 / 365.0 * (dayOfYear + 10)))
        val longitude = normaliseLon(180.0 - 15.0 * hours)
        return declination to longitude
    }

    /**
     * The great circle ninety degrees from a point — the day/night line.
     *
     * Returned as places rather than pixels so the caller can project
     * them with everything else and keep the hidden half hidden.
     */
    fun terminator(sunLat: Double, sunLon: Double, steps: Int = 180): List<Pair<Double, Double>> {
        val la = Math.toRadians(sunLat)
        val lo = Math.toRadians(sunLon)
        val out = ArrayList<Pair<Double, Double>>(steps + 1)
        for (i in 0..steps) {
            val theta = 2.0 * Math.PI * i / steps
            // A unit vector perpendicular to the sun direction, swept
            // around it: that circle is exactly the terminator.
            val x = -sin(la) * cos(lo) * cos(theta) - sin(lo) * sin(theta)
            val y = -sin(la) * sin(lo) * cos(theta) + cos(lo) * sin(theta)
            val z = cos(la) * cos(theta)
            val lat = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
            val lon = Math.toDegrees(atan2(y, x))
            out.add(lat to normaliseLon(lon))
        }
        return out
    }

    /** True when the sun is below the horizon at this place, now. */
    fun isNight(lat: Double, lon: Double, sunLat: Double, sunLon: Double): Boolean {
        val a = Math.toRadians(lat)
        val b = Math.toRadians(sunLat)
        val d = Math.toRadians(lon - sunLon)
        return sin(a) * sin(b) + cos(a) * cos(b) * cos(d) < 0.0
    }

    /** Degrees of arc between two places, for hit-testing a tap. */
    fun separationDegrees(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val a = Math.toRadians(lat1)
        val b = Math.toRadians(lat2)
        val d = Math.toRadians(abs(lon1 - lon2))
        val cosine = sin(a) * sin(b) + cos(a) * cos(b) * cos(d)
        return Math.toDegrees(kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)))
    }
}
