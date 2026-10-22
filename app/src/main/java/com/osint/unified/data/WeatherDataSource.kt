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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Active severe-weather alerts from the US National Weather Service (keyless).
 * Alerts carry a polygon; we plot each at the polygon's centroid, labelled by
 * event type (Tornado Warning, Severe Thunderstorm Warning, …).
 */
class WeatherDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.weather.gov/alerts/active?status=actual&message_type=alert")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: return@withContext emptyList()
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val geom = o["geometry"] as? JsonObject ?: return@mapNotNull null
                    val type = (geom["type"] as? JsonPrimitive)?.contentOrNull
                    val ring = when (type) {
                        "Polygon" -> geom["coordinates"]?.jsonArray?.getOrNull(0)?.jsonArray
                        else -> return@mapNotNull null
                    } ?: return@mapNotNull null
                    var sumLat = 0.0; var sumLon = 0.0; var n = 0
                    ring.forEach { p ->
                        val a = p as? JsonArray ?: return@forEach
                        val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                        val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                        sumLat += lat; sumLon += lon; n++
                    }
                    if (n == 0) return@mapNotNull null
                    val event = ((o["properties"] as? JsonObject)?.get("event") as? JsonPrimitive)?.contentOrNull ?: "Alert"
                    GeoBlip(sumLat / n, sumLon / n, event)
                }.take(3000)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
