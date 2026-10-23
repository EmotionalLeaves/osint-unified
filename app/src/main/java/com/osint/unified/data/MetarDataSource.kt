package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * METAR airport-weather observations from the Aviation Weather Center (keyless):
 * station position plus flight category (VFR/MVFR/IFR/LIFR) and temperature.
 */
class MetarDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * One global bounding box returns only a couple of hundred stations —
     * the service caps each response — so the world is swept in regional
     * boxes and merged. Observations older than two hours are dropped: a
     * stale METAR looks exactly like a current one on a map.
     */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val out = java.util.concurrent.ConcurrentHashMap<String, GeoBlip>()
        val answered = coroutineScope {
            BOXES.map { box ->
                async { runCatching { fetchBox(box, out) } }
            }.map { it.await() }.any { it.isSuccess }
        }
        // Two dozen boxes all failing used to return an empty list and
        // report success, which the pill and the world brief then
        // state as a fact: no airport weather report anywhere on
        // Earth. That is not a thing that happens, so it must not be
        // something this can say by accident.
        if (!answered) throw IOException("no METAR box answered")
        out.values.toList()
    }

    private fun fetchBox(box: String, into: MutableMap<String, GeoBlip>) {
        val request = Request.Builder()
            .url("https://aviationweather.gov/api/data/metar?format=json&bbox=$box")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            // Thrown, not returned: the caller counts a quiet return
            // as this box having answered.
            if (!resp.isSuccessful) throw IOException("METAR HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("METAR box returned nothing")
            val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                ?: throw IOException("METAR box answered in an unexpected shape")
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val lat = d("lat") ?: continue
                val lon = d("lon") ?: continue
                val id = s("icaoId") ?: continue
                val observed = Freshness.parse(s("reportTime"))
                if (!Freshness.within(observed, 2)) continue
                val label = buildString {
                    append(id)
                    s("fltCat")?.let { append(" $it") }
                    d("temp")?.let { append(" · ${it.toInt()}°C") }
                    d("wspd")?.let { append(" · wind ${it.toInt()}kt") }
                    s("name")?.let { append(" · ${it.take(40)}") }
                    Freshness.ago(observed)?.let { append(" · $it") }
                }
                into[id] = GeoBlip(lat, lon, label, "https://aviationweather.gov/data/metar/?id=$id")
            }
        }
    }

    private companion object {
        /** minLat,minLon,maxLat,maxLon tiles covering the inhabited world. */
        val BOXES: List<String> = buildList {
            var lat = -60
            while (lat < 80) {
                var lon = -180
                while (lon < 180) {
                    add("$lat,$lon,${lat + 35},${lon + 60}")
                    lon += 60
                }
                lat += 35
            }
        }
    }
}
