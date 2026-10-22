package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every tracked satellite, positioned live.
 *
 * The previous source (wheretheiss.at) turned out to track exactly one
 * object — the ISS — so the layer was empty no matter how many NORAD IDs it
 * was asked for. Celestrak instead publishes the mean orbital elements for
 * the whole active catalogue, keyless, so positions are computed here.
 *
 * The propagator is Keplerian with the J2 secular drift of the node and
 * perigee, not full SGP4: good to roughly tens of kilometres for low orbits
 * within a day of the element epoch. That is far below one pixel at the
 * zoom levels a globe is viewed at, and the label says the position is
 * computed rather than observed.
 */
class SatellitesDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Mean elements for one object, as published by Celestrak. */
    private data class Elements(
        val name: String,
        val noradId: Int,
        val epochMillis: Long,
        val meanMotion: Double,      // revolutions per day
        val eccentricity: Double,
        val inclination: Double,     // degrees
        val raan: Double,            // degrees
        val argPerigee: Double,      // degrees
        val meanAnomaly: Double      // degrees
    )

    @Volatile private var catalogue: List<Elements> = emptyList()
    @Volatile private var fetchedAt = 0L

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (catalogue.isEmpty() || now - fetchedAt > CATALOGUE_TTL_MS) {
            val loaded = runCatching { loadCatalogue() }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                catalogue = loaded
                fetchedAt = now
            }
        }
        val snapshot = catalogue
        if (snapshot.isEmpty()) return@withContext emptyList()
        snapshot.mapNotNull { propagate(it, now) }
    }

    private fun loadCatalogue(): List<Elements> {
        val request = Request.Builder()
            .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=json")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val epoch = s("EPOCH") ?: return@mapNotNull null
                val epochMs = runCatching {
                    fmt.parse(epoch.substringBefore('.'))?.time
                }.getOrNull() ?: return@mapNotNull null
                Elements(
                    name = s("OBJECT_NAME") ?: "satellite",
                    noradId = (o["NORAD_CAT_ID"] as? JsonPrimitive)?.intOrNull ?: 0,
                    epochMillis = epochMs,
                    meanMotion = d("MEAN_MOTION") ?: return@mapNotNull null,
                    eccentricity = d("ECCENTRICITY") ?: 0.0,
                    inclination = d("INCLINATION") ?: 0.0,
                    raan = d("RA_OF_ASC_NODE") ?: 0.0,
                    argPerigee = d("ARG_OF_PERICENTER") ?: 0.0,
                    meanAnomaly = d("MEAN_ANOMALY") ?: 0.0
                )
            }
        }
    }

    /** Sub-satellite point and altitude at [nowMillis]. */
    private fun propagate(e: Elements, nowMillis: Long): GeoBlip? {
        if (e.meanMotion <= 0.0 || e.eccentricity >= 1.0) return null
        val dt = (nowMillis - e.epochMillis) / 1000.0          // seconds
        // Elements older than a fortnight have drifted too far to plot.
        if (abs(dt) > 14 * 86400.0) return null

        val n = e.meanMotion * 2.0 * PI / 86400.0              // rad/s
        val a = cbrt(MU / (n * n))                             // km
        val ecc = e.eccentricity
        val inc = Math.toRadians(e.inclination)

        // J2 makes the node regress and the perigee rotate; over hours that
        // dominates the error, so it is worth the few extra lines.
        val p = a * (1 - ecc * ecc)
        if (p <= 0) return null
        val factor = 1.5 * J2 * (EARTH_RADIUS_KM / p).pow(2) * n
        val raan = Math.toRadians(e.raan) - factor * cos(inc) * dt
        val argP = Math.toRadians(e.argPerigee) + factor * 0.5 * (5 * cos(inc) * cos(inc) - 1) * dt

        // Kepler's equation, solved by Newton-Raphson.
        var m = Math.toRadians(e.meanAnomaly) + n * dt
        m = ((m % TAU) + TAU) % TAU
        var ea = m
        repeat(12) {
            val delta = (ea - ecc * sin(ea) - m) / (1 - ecc * cos(ea))
            ea -= delta
            if (abs(delta) < 1e-10) return@repeat
        }

        val trueAnomaly = 2.0 * atan2(
            sqrt(1 + ecc) * sin(ea / 2.0),
            sqrt(1 - ecc) * cos(ea / 2.0)
        )
        val r = a * (1 - ecc * cos(ea))

        // Perifocal to Earth-centred inertial.
        val u = argP + trueAnomaly
        val cosU = cos(u); val sinU = sin(u)
        val cosO = cos(raan); val sinO = sin(raan)
        val cosI = cos(inc); val sinI = sin(inc)
        val x = r * (cosO * cosU - sinO * sinU * cosI)
        val y = r * (sinO * cosU + cosO * sinU * cosI)
        val z = r * (sinU * sinI)

        val lat = Math.toDegrees(asin((z / r).coerceIn(-1.0, 1.0)))
        var lon = Math.toDegrees(atan2(y, x) - gmst(nowMillis))
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (lat.isNaN() || lon.isNaN()) return null

        val altitude = (r - EARTH_RADIUS_KM).toInt()
        if (altitude < 80 || altitude > 100_000) return null   // decayed or junk elements
        return GeoBlip(
            lat, lon,
            "${e.name} · ${altitude}km",
            "https://celestrak.org/satcat/table-satcat.php?CATNR=${e.noradId}"
        )
    }

    /** Greenwich mean sidereal time, in radians. */
    private fun gmst(millis: Long): Double {
        val jd = millis / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        var deg = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - t * t * t / 38710000.0
        deg %= 360.0
        if (deg < 0) deg += 360.0
        return Math.toRadians(deg)
    }

    private companion object {
        const val MU = 398600.4418            // km^3/s^2
        const val EARTH_RADIUS_KM = 6378.137
        const val J2 = 1.08262668e-3
        const val TAU = 2.0 * PI
        const val CATALOGUE_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
