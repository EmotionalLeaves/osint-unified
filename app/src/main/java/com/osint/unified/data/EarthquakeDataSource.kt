package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Quake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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
                if (!resp.isSuccessful) throw IOException("USGS HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("USGS returned nothing")
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: throw IOException("USGS answered in an unexpected shape")
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val coords = (o["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val props = o["properties"] as? JsonObject
                    fun p(k: String) = (props?.get(k) as? JsonPrimitive)
                    val mag = p("mag")?.doubleOrNull ?: 0.0
                    Quake(
                        lat = lat,
                        lon = lon,
                        mag = mag,
                        // USGS already names the place and times the event;
                        // the layer was throwing both away.
                        place = p("place")?.contentOrNull.orEmpty(),
                        atMillis = p("time")?.longOrNull,
                        depthKm = (coords.getOrNull(2) as? JsonPrimitive)?.doubleOrNull,
                        url = p("url")?.contentOrNull
                    )
                }
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("USGS unavailable: ${e.message}", e)
        }
    }
}
