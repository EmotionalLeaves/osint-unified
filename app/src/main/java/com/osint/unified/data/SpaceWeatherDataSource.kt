package com.osint.unified.data

import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** Current space weather, condensed into one HUD line plus active warnings. */
data class SpaceWeather(
    val kp: Double = 0.0,
    val xrayClass: String = "",
    val alerts: List<String> = emptyList()
) {
    /** e.g. "Kp 4.0 · X-ray C2.1 · 2 alerts" */
    val summary: String
        get() = buildString {
            append("Kp %.1f".format(Locale.US, kp))
            if (xrayClass.isNotBlank()) append(" · X-ray $xrayClass")
            if (alerts.isNotEmpty()) append(" · ${alerts.size} alert${if (alerts.size == 1) "" else "s"}")
        }

    /** Geomagnetic storm level from the planetary K index. */
    val stormLevel: String
        get() = when {
            // The scale stopped at G4, so the largest storm there is —
            // the one that puts aurora over the tropics and takes grids
            // down — was reported as the second largest.
            kp >= 9 -> "G5 EXTREME"
            kp >= 8 -> "G4 SEVERE"
            kp >= 7 -> "G3 STRONG"
            kp >= 6 -> "G2 MODERATE"
            kp >= 5 -> "G1 MINOR"
            else -> "QUIET"
        }
}

/**
 * NOAA SWPC, keyless: planetary K index, GOES X-ray flux (converted to the
 * familiar A/B/C/M/X class) and the live space weather warning feed.
 */
class SpaceWeatherDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): SpaceWeather = withContext(Dispatchers.IO) {
        var kp = 0.0
        var xray = ""
        val alerts = ArrayList<String>()

        runCatching {
            val arr = json.parseToJsonElement(
                get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
            ) as? JsonArray
            // Newest sample last; the feed is an array of objects.
            (arr?.lastOrNull() as? JsonObject)?.let { o ->
                kp = (o["Kp"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            }
        }

        runCatching {
            // The 6-hour file is small; fall back to the 1-day file, which is
            // the one verified to exist, if it ever disappears.
            val body = runCatching {
                get("https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json")
            }.getOrElse {
                get("https://services.swpc.noaa.gov/json/goes/primary/xrays-1-day.json")
            }
            val arr = json.parseToJsonElement(body) as? JsonArray
            // Use the long channel (0.1-0.8nm), which defines the flare class.
            val last = arr?.lastOrNull { el ->
                ((el as? JsonObject)?.get("energy") as? JsonPrimitive)?.contentOrNull == "0.1-0.8nm"
            } as? JsonObject
            ((last?.get("flux") as? JsonPrimitive)?.doubleOrNull)?.let { xray = flareClass(it) }
        }

        runCatching {
            val arr = json.parseToJsonElement(
                get("https://services.swpc.noaa.gov/products/alerts.json")
            ) as? JsonArray
            arr?.take(40)?.forEach { el ->
                val msg = ((el as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val headline = msg.lineSequence().firstOrNull {
                    it.contains("WARNING", true) || it.contains("ALERT", true) || it.contains("WATCH", true)
                }?.trim()
                if (!headline.isNullOrBlank() && alerts.none { it == headline }) alerts.add(headline)
            }
        }

        SpaceWeather(kp, xray, alerts.take(8))
    }

    /** W/m² to the NOAA flare class letter. */
    private fun flareClass(flux: Double): String {
        val (letter, scale) = when {
            flux >= 1e-4 -> "X" to 1e-4
            flux >= 1e-5 -> "M" to 1e-5
            flux >= 1e-6 -> "C" to 1e-6
            flux >= 1e-7 -> "B" to 1e-7
            else -> "A" to 1e-8
        }
        return "%s%.1f".format(Locale.US, letter, flux / scale)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}
