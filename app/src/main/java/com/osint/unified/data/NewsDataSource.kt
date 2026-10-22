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
import java.net.URLEncoder

/**
 * Ambient global-news hotspots from GDELT 2.0 (keyless): places mentioned in
 * breaking news around conflict, protest, disaster and crisis in the last day.
 */
class NewsDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("(protest OR conflict OR disaster OR crisis OR attack OR earthquake)", "UTF-8")
            val request = Request.Builder()
                .url("https://api.gdeltproject.org/api/v2/geo/geo?query=$query&format=GeoJSON")
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
                    val name = ((o["properties"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: "news"
                    GeoBlip(lat, lon, name)
                }.take(150)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
