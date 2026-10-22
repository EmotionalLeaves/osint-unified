package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.OverlayPoint
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
import java.io.IOException

/**
 * Fetches a user-supplied GeoJSON URL and plots its Point features as a custom
 * globe layer. Lets the user bring any point dataset (a CSV-to-GeoJSON export,
 * an agency feed, their own markers) onto the God's Eye View.
 */
class OverlayDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.feedClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<OverlayPoint> = withContext(Dispatchers.IO) {
        val url = settings.overlayUrl.trim()
        if (url.isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder().url(url).header("User-Agent", "OSINT-Unified/1.0").build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("custom overlay HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("custom overlay returned nothing")
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: throw IOException("custom overlay answered in an unexpected shape")
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val geom = o["geometry"] as? JsonObject ?: return@mapNotNull null
                    if ((geom["type"] as? JsonPrimitive)?.contentOrNull != "Point") return@mapNotNull null
                    val coords = geom["coordinates"]?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val props = o["properties"] as? JsonObject
                    val label = listOf("name", "title", "label", "id").firstNotNullOfOrNull {
                        (props?.get(it) as? JsonPrimitive)?.contentOrNull
                    } ?: "point"
                    OverlayPoint(lat, lon, label)
                }.take(5000)
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("custom overlay unavailable: ${e.message}", e)
        }
    }
}
