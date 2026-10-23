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
import java.util.Locale

/**
 * Controlled places, anywhere on Earth.
 *
 * Border crossings, diplomatic missions, prisons and the substations
 * the lights depend on. The map already knows where the airports and
 * the power stations are; these are the other four kinds of place a
 * person looking at an area actually asks about, and all four are
 * mapped worldwide in OpenStreetMap and nowhere else that is open.
 *
 * Queried around the view rather than held, like the cameras and the
 * military ground, because the worldwide set is far too large and
 * because almost all of it is irrelevant to wherever you are looking.
 * Off by default: it is a question, and asking Overpass costs a
 * volunteer-run server something.
 */
class SitesDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cached: Cached? = null

    private data class Cached(val key: String, val at: Long, val blips: List<GeoBlip>)

    suspend fun near(lat: Double, lon: Double, radiusKm: Double = 120.0): List<GeoBlip> =
        withContext(Dispatchers.IO) {
            val key = "%.1f,%.1f,%.0f".format(Locale.US, lat, lon, radiusKm)
            cached?.let {
                if (it.key == key && System.currentTimeMillis() - it.at < CACHE_TTL_MS) {
                    return@withContext it.blips
                }
            }
            val radius = (radiusKm * 1000).toInt().coerceIn(10_000, 300_000)
            // `out center` so Overpass reduces a perimeter to a point
            // rather than sending every vertex of every fence line.
            val query = """
                [out:json][timeout:30];
                (
                  node["barrier"="border_control"](around:$radius,$lat,$lon);
                  way["barrier"="border_control"](around:$radius,$lat,$lon);
                  node["amenity"="embassy"](around:$radius,$lat,$lon);
                  way["amenity"="embassy"](around:$radius,$lat,$lon);
                  node["office"="diplomatic"](around:$radius,$lat,$lon);
                  way["office"="diplomatic"](around:$radius,$lat,$lon);
                  way["amenity"="prison"](around:$radius,$lat,$lon);
                  relation["amenity"="prison"](around:$radius,$lat,$lon);
                  node["power"="substation"](around:$radius,$lat,$lon);
                  way["power"="substation"](around:$radius,$lat,$lon);
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

    /**
     * Elements into markers, named by what they are first.
     *
     * "Border crossing · Kapitan Andreevo" is useful at a glance;
     * "Kapitan Andreevo" is a word. The kind leads, because on a map
     * of five kinds of place the kind is the thing being looked for.
     */
    internal fun parse(body: String): List<GeoBlip> {
        val elements = runCatching {
            json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        val seen = HashSet<String>()
        return elements.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val centre = o["center"] as? JsonObject
            fun d(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.doubleOrNull
            val lat = d(o, "lat") ?: d(centre, "lat") ?: return@mapNotNull null
            val lon = d(o, "lon") ?: d(centre, "lon") ?: return@mapNotNull null

            val tags = o["tags"] as? JsonObject
            fun t(key: String) = (tags?.get(key) as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            val kind = when {
                t("barrier") == "border_control" -> "Border crossing"
                t("amenity") == "embassy" || t("office") == "diplomatic" ->
                    t("diplomatic")?.replaceFirstChar(Char::uppercase) ?: "Diplomatic mission"
                t("amenity") == "prison" -> "Prison"
                t("power") == "substation" -> "Substation"
                else -> return@mapNotNull null
            }
            val name = t("name") ?: t("name:en") ?: t("operator")
            val country = t("country") ?: t("target")
            val label = buildString {
                append(kind)
                name?.let { append(" · ").append(it) }
                country?.let { append(" · ").append(it) }
                t("voltage")?.let { append(" · ").append(it).append(" V") }
            }
            // The same site is often a node and a way, and Overpass
            // returns both. Two markers on one building is two sites
            // to whoever is counting.
            val identity = "%s|%.4f|%.4f".format(Locale.US, kind, lat, lon)
            if (!seen.add(identity)) return@mapNotNull null
            GeoBlip(lat, lon, label, "https://www.openstreetmap.org/#map=17/$lat/$lon")
        }
    }

    private companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val MAX_RESULTS = 1200
        const val CACHE_TTL_MS = 30 * 60_000L
    }
}
