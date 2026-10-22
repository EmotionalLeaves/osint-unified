package com.osint.unified.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns published mean orbital elements into a sub-satellite point.
 *
 * Kepler's equation plus the J2 secular drift of the node and perigee —
 * not full SGP4, and in particular nothing for atmospheric drag. Measured
 * against an observed ISS position with element sets 38 hours old it lands
 * within about four kilometres; the error is mostly along-track and grows
 * with the age of the elements, which is why the satellite layer refuses to
 * plot low orbits from elements more than a few days old rather than
 * pretending the number still means something.
 *
 * Kept here, separate from the data source, so the maths can be tested
 * against a known position rather than trusted. The latitude it reports is
 * geodetic — the one maps use — not the angle at the Earth's centre, which
 * is a different number by up to 0.18° and put every marker 20 km from
 * where every other source said it was.
 */
object OrbitPropagator {

    const val MU = 398600.4418            // km^3/s^2
    const val EARTH_RADIUS_KM = 6378.137  // WGS-84 equatorial radius
    const val J2 = 1.08262668e-3

    /** WGS-84 flattening, and the eccentricity squared it implies. */
    private const val FLATTENING = 1.0 / 298.257223563
    private const val E2 = FLATTENING * (2.0 - FLATTENING)

    /** Mean elements as published by Celestrak's GP feed. */
    data class Elements(
        val meanMotion: Double,      // revolutions per day
        val eccentricity: Double,
        val inclination: Double,     // degrees
        val raan: Double,            // degrees
        val argPerigee: Double,      // degrees
        val meanAnomaly: Double,     // degrees
        val epochMillis: Long
    )

    /** Latitude and longitude in degrees, altitude in km. */
    data class Position(val lat: Double, val lon: Double, val altitudeKm: Double)

    fun propagate(e: Elements, atMillis: Long): Position? {
        if (e.meanMotion <= 0.0 || e.eccentricity < 0.0 || e.eccentricity >= 1.0) return null
        val dt = (atMillis - e.epochMillis) / 1000.0

        val n = e.meanMotion * 2.0 * PI / 86400.0
        val a = cbrt(MU / (n * n))
        val ecc = e.eccentricity
        val inc = Math.toRadians(e.inclination)

        val p = a * (1 - ecc * ecc)
        if (p <= 0) return null
        val drift = 1.5 * J2 * (EARTH_RADIUS_KM / p).pow(2) * n
        val raan = Math.toRadians(e.raan) - drift * cos(inc) * dt
        val argP = Math.toRadians(e.argPerigee) +
            drift * 0.5 * (5 * cos(inc) * cos(inc) - 1) * dt

        var m = Math.toRadians(e.meanAnomaly) + n * dt
        m = ((m % TAU) + TAU) % TAU
        var ea = m
        repeat(12) {
            val delta = (ea - ecc * sin(ea) - m) / (1 - ecc * cos(ea))
            ea -= delta
            if (abs(delta) < 1e-12) return@repeat
        }

        val trueAnomaly = 2.0 * atan2(
            sqrt(1 + ecc) * sin(ea / 2.0),
            sqrt(1 - ecc) * cos(ea / 2.0)
        )
        val r = a * (1 - ecc * cos(ea))

        val u = argP + trueAnomaly
        val x = r * (cos(raan) * cos(u) - sin(raan) * sin(u) * cos(inc))
        val y = r * (sin(raan) * cos(u) + cos(raan) * sin(u) * cos(inc))
        val z = r * (sin(u) * sin(inc))

        val ground = geodetic(sqrt(x * x + y * y), z)
        var lon = Math.toDegrees(atan2(y, x) - gmst(atMillis))
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (ground.first.isNaN() || lon.isNaN()) return null
        return Position(ground.first, lon, ground.second)
    }

    /**
     * Geodetic latitude and height, rather than geocentric.
     *
     * The Earth is 21 km wider than it is tall, so the angle subtended at
     * its centre is not the latitude anyone means by latitude: at 45° the
     * two differ by 0.18°, which puts a satellite 20 km from where every
     * map and every other source says it is. Altitude was wrong by the
     * same reasoning — measured from a sphere rather than from the ground
     * underneath — and read about 9 km low over the mid-latitudes.
     *
     * Five turns of the standard iteration; measured against a forward
     * conversion it settles to better than a micro-degree by the second,
     * at every latitude from the equator to the pole and from the ground
     * to geostationary.
     */
    fun geodetic(hyp: Double, z: Double): Pair<Double, Double> {
        var lat = atan2(z, hyp)
        var height = 0.0
        repeat(5) {
            val s = sin(lat)
            val curvature = EARTH_RADIUS_KM / sqrt(1.0 - E2 * s * s)
            lat = atan2(z + curvature * E2 * s, hyp)
            height = hyp / cos(lat) - curvature
        }
        return Math.toDegrees(lat) to height
    }

    /** Greenwich mean sidereal time, in radians. */
    fun gmst(millis: Long): Double {
        val jd = millis / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        var deg = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - t * t * t / 38710000.0
        deg %= 360.0
        if (deg < 0) deg += 360.0
        return Math.toRadians(deg)
    }

    private const val TAU = 2.0 * PI
}
