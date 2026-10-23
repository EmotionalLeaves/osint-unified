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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Cameras, anywhere on Earth, from OpenStreetMap.
 *
 * The camera layer was Caltrans: about fifteen hundred live traffic
 * cameras, all of them in California. Useful if you are looking at
 * California and an empty map everywhere else, which is most places.
 *
 * OpenStreetMap has surveillance cameras mapped worldwide —
 * `man_made=surveillance` — with, often, who operates them, which way
 * they point and what they are watching. That is better OSINT than a
 * live picture of a freeway: it says a camera exists at a corner, who
 * owns it, and its direction of view. Queried around wherever the globe
 * is pointed, because the worldwide set is far too large to hold.
 */
class SurveillanceDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cached: Cached? = null

    private data class Cached(val key: String, val at: Long, val blips: List<GeoBlip>)

    /**
     * Cameras within [radiusKm] of a point.
     *
     * The key is the rounded centre, so panning a little reuses the
     * answer and panning a lot asks again. Overpass is a volunteer
     * service with a published rate limit, and a layer that re-queried
     * on every frame of a drag would be abusing it.
     */
    suspend fun near(lat: Double, lon: Double, radiusKm: Double = 25.0): List<GeoBlip> =
        withContext(Dispatchers.IO) {
            val key = "%.1f,%.1f,%.0f".format(java.util.Locale.US, lat, lon, radiusKm)
            cached?.let {
                if (it.key == key && System.currentTimeMillis() - it.at < CACHE_TTL_MS) {
                    return@withContext it.blips
                }
            }
            val radius = (radiusKm * 1000).toInt().coerceIn(1_000, 60_000)
            val query = """
                [out:json][timeout:25];
                (
                  node["man_made"="surveillance"](around:$radius,$lat,$lon);
                  node["highway"="speed_camera"](around:$radius,$lat,$lon);
                );
                out body $MAX_RESULTS;
            """.trimIndent()

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(FormBody.Builder().add("data", query).build())
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Overpass HTTP ${resp.code}")
                resp.body?.string()
            } ?: throw IOException("Overpass returned nothing")

            val blips = parse(body)
            cached = Cached(key, System.currentTimeMillis(), blips)
            blips
        }

    internal fun parse(body: String): List<GeoBlip> {
        val elements = runCatching {
            json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: throw IOException("Overpass answered in an unexpected shape")

        return elements.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val lat = (node["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (node["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val tags = node["tags"] as? JsonObject
            fun tag(name: String) = (tags?.get(name) as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            // What the map actually knows about this camera, which is
            // the point of using OpenStreetMap rather than a picture.
            val kind = tag("surveillance:type") ?: tag("highway") ?: "camera"
            val watching = tag("surveillance") ?: tag("surveillance:zone")
            val operator = tag("operator")
            val direction = tag("camera:direction") ?: tag("direction")
            val mount = tag("camera:mount")
            val id = (node["id"] as? JsonPrimitive)?.contentOrNull

            val label = buildString {
                append(kind.replace('_', ' '))
                watching?.let { append(" · ").append(it) }
                operator?.let { append(" · ").append(it.take(40)) }
                direction?.let { append(" · facing ").append(it) }
                mount?.let { append(" · ").append(it) }
            }
            GeoBlip(
                lat, lon, label,
                id?.let { "https://www.openstreetmap.org/node/$it" }
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"

        /** Enough to show a city's cameras; few enough to draw. */
        const val MAX_RESULTS = 800

        /** Overpass is volunteer-run and publishes a rate limit. */
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
