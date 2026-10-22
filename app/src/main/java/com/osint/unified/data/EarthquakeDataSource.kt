package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Quake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches recent earthquakes (USGS, last 24h) for the native globe. */
class EarthquakeDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<Quake> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson")
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
                    val mag = ((o["properties"] as? JsonObject)?.get("mag") as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    Quake(lat, lon, mag)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
