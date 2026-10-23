package com.osint.unified.domain

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import java.util.Locale

/**
 * Self-contained WGS84 lat/lon -> MGRS (Military Grid Reference System)
 * conversion, so the app shows a real military grid with no external library.
 * Covers the standard -80..84 latitude range; returns null outside it.
 */
object Mgrs {

    private const val A = 6378137.0                 // WGS84 semi-major axis
    private const val F = 1.0 / 298.257223563       // flattening
    private const val K0 = 0.9996                   // UTM scale factor
    private val e2 = F * (2 - F)                     // eccentricity squared
    private val ep2 = e2 / (1 - e2)                  // second eccentricity squared

    private const val LAT_BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val e100k = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private val n100k = arrayOf("ABCDEFGHJKLMNPQRSTUV", "FGHJKLMNPQRSTUVABCDE")

    fun from(lat: Double, lon: Double): String? {
        if (lat < -80 || lat > 84 || lon < -180 || lon > 180) return null
        return try { convert(lat, lon) } catch (e: Exception) { null }
    }

    /**
     * The UTM zone, including the two places where the grid is not
     * regular.
     *
     * South-west Norway has zone 32 widened westward to cover the coast
     * rather than splitting it, and around Svalbard four zones are
     * widened and the two between them do not exist. These are part of
     * the standard, not a nicety: a grid reference computed from the
     * arithmetic zone is simply a different square from the one on the
     * map, by up to several hundred kilometres.
     */
    internal fun zoneFor(lat: Double, lon: Double): Int {
        val arithmetic = (floor((lon + 180) / 6).toInt() % 60) + 1
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) return 32
        // Band X runs 72 to 84 INCLUSIVE — it is the one band that is
        // twelve degrees tall. `lat < 84` let the top edge fall
        // through to the arithmetic zone and emit 32X, 34X or 36X,
        // which are grid zones that do not exist: at 84.0N, 7E the
        // answer is 31X and this said 32X.
        if (lat >= 72 && lat <= 84) {
            return when {
                lon >= 0 && lon < 9 -> 31
                lon >= 9 && lon < 21 -> 33
                lon >= 21 && lon < 33 -> 35
                lon >= 33 && lon < 42 -> 37
                else -> arithmetic
            }
        }
        return arithmetic
    }

    private fun convert(rawLat: Double, rawLon: Double): String {
        // 180 and -180 are the same meridian, and only one of them is in
        // any zone. Left alone, a longitude of exactly 180 landed in zone
        // 1 and was then measured 357 degrees from zone 1's centre, which
        // produced an easting in the millions and a grid square on the
        // other side of the world.
        val lat = rawLat
        val lon = ((rawLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val latRad = Math.toRadians(lat)
        val zone = zoneFor(lat, lon)
        val lonOrigin = (zone - 1) * 6 - 180 + 3
        val lonOriginRad = Math.toRadians(lonOrigin.toDouble())

        val n = A / sqrt(1 - e2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ep2 * cos(latRad).pow(2)
        val aa = cos(latRad) * (Math.toRadians(lon) - lonOriginRad)
        val m = A * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latRad
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latRad)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latRad)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * latRad))

        val easting = K0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120) + 500000.0
        var northing = K0 * (m + n * tan(latRad) * (aa * aa / 2 +
            (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720))
        if (lat < 0) northing += 10_000_000.0

        // Inside a zone the easting is always between 100 and 900 km of
        // the false origin. Anything else means the point was measured
        // against the wrong meridian, and the letters below would quietly
        // clamp to the edge of the alphabet and hand back a plausible
        // reference for somewhere else. Better to answer nothing.
        if (easting < 100_000.0 || easting >= 900_000.0) {
            throw IllegalStateException("easting $easting outside zone $zone")
        }
        if (northing < 0.0 || northing >= 10_000_000.0) {
            throw IllegalStateException("northing $northing outside the grid")
        }

        val band = LAT_BANDS[((lat + 80) / 8).toInt().coerceIn(0, LAT_BANDS.length - 1)]
        val colLetters = e100k[(zone - 1) % 3]
        val colIdx = (floor(easting / 100000).toInt() - 1).coerceIn(0, colLetters.length - 1)
        val col = colLetters[colIdx]
        val rowLetters = n100k[(zone - 1) % 2]
        val rowIdx = (floor(northing / 100000).toInt() % 20 + 20) % 20
        val row = rowLetters[rowIdx.coerceIn(0, rowLetters.length - 1)]

        val e = (easting % 100000).toInt()
        val nr = (northing % 100000).toInt()
        return "%d%c %c%c %05d %05d".format(Locale.US, zone, band, col, row, e, nr)
    }
}
