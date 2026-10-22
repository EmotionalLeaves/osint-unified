package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GDACS global disaster alerts (keyless): earthquakes, cyclones, floods,
 * droughts, volcanoes and wildfires currently active worldwide, with alert
 * level. Ripped from the gods-eye-view data-source set, native.
 */
class GdacsDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val typeNames = mapOf(
        "EQ" to "Quake", "TC" to "Cyclone", "FL" to "Flood",
        "DR" to "Drought", "VO" to "Volcano", "WF" to "Wildfire"
    )

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.gdacs.org/gdacsapi/api/events/geteventlist/MAP")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: return@withContext emptyList()
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val coords = (o["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val p = o["properties"] as? JsonObject
                    fun s(k: String) = (p?.get(k) as? JsonPrimitive)?.contentOrNull
                    val type = typeNames[s("eventtype")] ?: s("eventtype") ?: "Event"
                    val level = s("alertlevel")?.takeIf { it.isNotBlank() }
                    val name = s("name") ?: s("eventname") ?: type
                    val label = (if (level != null) "[$level] " else "") + "$type: $name"
                    GeoBlip(lat, lon, label)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
