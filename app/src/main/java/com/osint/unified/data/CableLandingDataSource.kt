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
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Submarine cable landing points worldwide (TeleGeography's public map data,
 * keyless) — where intercontinental internet traffic physically comes ashore.
 * Critical infrastructure, and a standing target in conflict analysis.
 */
class CableLandingDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cache: List<GeoBlip> = emptyList()

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        if (cache.isNotEmpty()) return@withContext cache
        try {
            val request = Request.Builder()
                .url("https://www.submarinecablemap.com/api/v3/landing-point/landing-point-geo.json")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray
                    ?: return@withContext emptyList()
                val out = ArrayList<GeoBlip>(features.size)
                features.forEach { f ->
                    val o = f as? JsonObject ?: return@forEach
                    val coords = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
                        ?: return@forEach
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                    val props = o["properties"] as? JsonObject
                    val name = (props?.get("name") as? JsonPrimitive)?.contentOrNull ?: "landing point"
                    val id = (props?.get("id") as? JsonPrimitive)?.contentOrNull
                    out.add(
                        GeoBlip(
                            lat, lon, "CABLE $name",
                            id?.let { "https://www.submarinecablemap.com/landing-point/$it" }
                        )
                    )
                }
                if (out.isNotEmpty()) cache = out
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
