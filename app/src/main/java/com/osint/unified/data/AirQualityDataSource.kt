package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Air-quality (European AQI) at major world cities via Open-Meteo (keyless),
 * fetched in one multi-location request and plotted as the AQI layer.
 */
class AirQualityDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // lat,lon of ~30 major world cities.
    private val cities = listOf(
        40.71 to -74.01, 34.05 to -118.24, 41.88 to -87.63, 29.76 to -95.37,
        19.43 to -99.13, -23.55 to -46.63, -34.60 to -58.38, 51.51 to -0.13,
        48.86 to 2.35, 52.52 to 13.40, 41.90 to 12.50, 40.42 to -3.70,
        55.75 to 37.62, 41.01 to 28.98, 30.04 to 31.24, 6.52 to 3.38,
        -26.20 to 28.05, 25.20 to 55.27, 28.61 to 77.21, 19.08 to 72.88,
        13.76 to 100.50, 1.35 to 103.82, -6.21 to 106.85, 31.23 to 121.47,
        39.90 to 116.41, 37.57 to 126.98, 35.68 to 139.69, -33.87 to 151.21,
        -37.81 to 144.96, 33.69 to 73.05
    )

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val lats = cities.joinToString(",") { it.first.toString() }
            val lons = cities.joinToString(",") { it.second.toString() }
            val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lats&longitude=$lons&current=european_aqi"
            val request = Request.Builder().url(url).header("User-Agent", "OSINT-Unified/1.0").build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val root = json.parseToJsonElement(body)
                val items = (root as? JsonArray) ?: JsonArray(listOf(root))
                items.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val aqi = ((o["current"] as? JsonObject)?.get("european_aqi") as? JsonPrimitive)?.doubleOrNull
                    GeoBlip(lat, lon, "AQI ${aqi?.toInt() ?: "?"}")
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
