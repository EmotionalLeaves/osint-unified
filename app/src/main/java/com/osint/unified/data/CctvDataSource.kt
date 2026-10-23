package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cameras: everywhere from OpenStreetMap, and live pictures where there
 * are any.
 *
 * This layer used to be Caltrans alone — about fifteen hundred traffic
 * cameras, every one of them in California, which is an empty map
 * anywhere else and most places are anywhere else. Caltrans is still
 * here, because a live still frame is worth more than a dot, but the
 * layer's coverage now comes from OpenStreetMap's mapped surveillance
 * cameras, which exist worldwide and carry who operates them and which
 * way they point.
 */
class CctvDataSource(
    private val client: OkHttpClient = Http.feedClient,
    private val surveillance: SurveillanceDataSource = SurveillanceDataSource()
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val districts = listOf("3", "4", "5", "6", "7", "8", "10", "11", "12")

    @Volatile private var cache: List<GeoBlip> = emptyList()
    @Volatile private var fetchedAt = 0L

    /**
     * Cameras around where the globe is pointed, plus the Californian
     * ones with pictures.
     *
     * Both, rather than one or the other: the mapped set gives coverage
     * and provenance, the Caltrans set gives an image you can look at.
     * Either failing leaves the other.
     */
    suspend fun near(lat: Double, lon: Double): List<GeoBlip> = withContext(Dispatchers.IO) {
        val mapped = runCatching { surveillance.near(lat, lon) }.getOrDefault(emptyList())
        val live = runCatching { fetch() }.getOrDefault(emptyList())
        val merged = mapped + live
        if (merged.isEmpty()) {
            throw java.io.IOException("no camera source answered")
        }
        merged
    }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        // The inventory changes rarely; refresh at most every six hours.
        val age = System.currentTimeMillis() - fetchedAt
        if (cache.isNotEmpty() && age < 6 * 60 * 60 * 1000L) return@withContext cache
        val merged = coroutineScope {
            districts.map { d -> async { runCatching { district(d) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
        }
        if (merged.isNotEmpty()) { cache = merged; fetchedAt = System.currentTimeMillis() }
        merged
    }

    private fun district(d: String): List<GeoBlip> {
        val padded = if (d.length == 1) "0$d" else d
        val url = "https://cwwp2.dot.ca.gov/data/d$d/cctv/cctvStatusD$padded.json"
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
            val out = ArrayList<GeoBlip>(data.size)
            data.forEach { el ->
                val cctv = (el as? JsonObject)?.get("cctv") as? JsonObject ?: return@forEach
                val loc = cctv["location"] as? JsonObject ?: return@forEach
                fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
                val lat = s(loc, "latitude")?.toDoubleOrNull() ?: return@forEach
                val lon = s(loc, "longitude")?.toDoubleOrNull() ?: return@forEach
                if (lat == 0.0 && lon == 0.0) return@forEach
                val inService = s(cctv["imageData"] as? JsonObject, "imageDescription")
                val name = s(loc, "locationName") ?: "camera"
                val place = s(loc, "nearbyPlace")
                val route = s(loc, "route")
                val image = s((cctv["imageData"] as? JsonObject)?.get("static") as? JsonObject, "currentImageURL")
                val label = buildString {
                    append("CAM ")
                    append(name)
                    if (!place.isNullOrBlank()) append(" · $place")
                    if (!route.isNullOrBlank()) append(" · $route")
                    if (!inService.isNullOrBlank()) append(" · $inService")
                }
                out.add(GeoBlip(lat, lon, label, image))
            }
            return out
        }
    }
}
