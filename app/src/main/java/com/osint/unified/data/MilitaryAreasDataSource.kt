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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Military and restricted ground, anywhere on Earth.
 *
 * The airspace layer is the FAA's, which means the United States and
 * nowhere else — outside it the toggle lit up an empty map, which reads
 * as "nothing restricted here" rather than "this source has never
 * covered here".
 *
 * OpenStreetMap maps military land worldwide: bases, ranges, danger
 * areas, naval yards. It is not airspace and does not pretend to be;
 * it is the ground underneath, which for most investigative purposes is
 * the more useful of the two. Queried around the view, like the cameras,
 * because the worldwide set is far too large to hold.
 */
class MilitaryAreasDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cached: Cached? = null

    private data class Cached(val key: String, val at: Long, val blips: List<GeoBlip>)

    suspend fun near(lat: Double, lon: Double, radiusKm: Double = 150.0): List<GeoBlip> =
        withContext(Dispatchers.IO) {
            val key = "%.0f,%.0f,%.0f".format(Locale.US, lat, lon, radiusKm)
            cached?.let {
                if (it.key == key && System.currentTimeMillis() - it.at < CACHE_TTL_MS) {
                    return@withContext it.blips
                }
            }
            val radius = (radiusKm * 1000).toInt().coerceIn(10_000, 400_000)
            // Centre points rather than polygons: the globe plots points,
            // and `out center` makes Overpass do the reduction rather than
            // sending every vertex of every perimeter fence.
            val query = """
                [out:json][timeout:30];
                (
                  way["landuse"="military"](around:$radius,$lat,$lon);
                  relation["landuse"="military"](around:$radius,$lat,$lon);
                  way["military"](around:$radius,$lat,$lon);
                  relation["military"](around:$radius,$lat,$lon);
                );
                out center $MAX_RESULTS;
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

        val seen = HashSet<String>()
        return elements.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            // `out center` puts the centroid under "center" for ways and
            // relations, and at the top level for nodes.
            val centre = node["center"] as? JsonObject
            val lat = ((centre ?: node)["lat"] as? JsonPrimitive)?.doubleOrNull
                ?: return@mapNotNull null
            val lon = ((centre ?: node)["lon"] as? JsonPrimitive)?.doubleOrNull
                ?: return@mapNotNull null
            val tags = node["tags"] as? JsonObject
            fun tag(name: String) = (tags?.get(name) as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            val name = tag("name") ?: tag("name:en")
            val kind = tag("military") ?: tag("landuse") ?: "military"
            // An unnamed fence segment tagged military is noise; a named
            // base is a place.
            if (name == null && kind == "military") return@mapNotNull null
            // One marker per place, not one per mapped building inside it.
            val fingerprint = name?.lowercase()
                ?: "%.3f,%.3f".format(Locale.US, lat, lon)
            if (!seen.add(fingerprint)) return@mapNotNull null

            val label = buildString {
                append(name ?: kind.replace('_', ' '))
                if (name != null) append(" · ").append(kind.replace('_', ' '))
                tag("operator")?.let { append(" · ").append(it.take(40)) }
            }
            val id = (node["id"] as? JsonPrimitive)?.contentOrNull
            val type = (node["type"] as? JsonPrimitive)?.contentOrNull ?: "way"
            GeoBlip(
                lat, lon, label,
                id?.let { "https://www.openstreetmap.org/$type/$it" }
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val MAX_RESULTS = 600
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
