package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * NOAA SWPC OVATION aurora forecast (keyless): a global grid of aurora
 * probability. We keep only strong cells and sample them so the globe shows
 * where aurora is likely right now, without thousands of markers.
 */
class AuroraDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val coords = json.parseToJsonElement(body).jsonObject["coordinates"]?.jsonArray
                    ?: return@withContext emptyList()
                val out = ArrayList<GeoBlip>()
                var i = 0
                for (c in coords) {
                    val a = c as? JsonArray ?: continue
                    val value = (a.getOrNull(2) as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    if (value < 12) continue        // below this it is noise, not aurora          // only meaningful probability
                    if (i++ % 2 != 0) continue        // sample the dense grid
                    var lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: continue
                    val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: continue
                    if (lon > 180) lon -= 360          // SWPC uses 0..360
                    out.add(GeoBlip(lat, lon, "Aurora ${value.toInt()}% chance"))
                    if (out.size >= 6000) break
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
