package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Vessel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Maritime vessel positions, from as many open feeds as there are.
 *
 * There is no network publishing live AIS worldwide without an
 * account, so this is built out of national authorities publishing
 * their own waters and adding them up. The setting lists feed URLs,
 * one per line; each is fetched and merged, and a vessel reported by
 * two feeds is kept once.
 *
 * They do not agree on shape. Fintraffic sends a point per vessel with
 * `sog` and `cog`; the Norwegian Coastal Administration sends the last
 * few minutes of each vessel's track as a LineString with `ship_name`
 * and `speed`. So the reader takes the last coordinate of whatever
 * geometry it is given — the end of a track is where the ship is now —
 * and looks for each field under every name anyone uses for it.
 */
class AisDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.feedClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<Vessel> = withContext(Dispatchers.IO) {
        val urls = settings.aisUrl.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return@withContext emptyList()
        val merged = LinkedHashMap<String, Vessel>()
        for (url in urls) {
            if (merged.size >= 20000) break
            runCatching { fetchOne(url) }.getOrNull()?.forEach { v ->
                merged.putIfAbsent(v.mmsi.ifBlank { "${v.lat},${v.lon}" }, v)
            }
        }
        merged.values.toList()
    }

    private fun fetchOne(url: String): List<Vessel> {
        val request = Request.Builder().url(url).header("User-Agent", "OSINT-Unified/1.0").build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val root = json.parseToJsonElement(body)
            val features = (root as? JsonObject)?.get("features")?.jsonArray
            if (features != null) {
                features.mapNotNull { f ->
                    val fo = f.jsonObject
                    val geometry = fo["geometry"] as? JsonObject ?: return@mapNotNull null
                    val (lat, lon) = position(geometry) ?: return@mapNotNull null
                    val props = fo["properties"] as? JsonObject
                    fun p(vararg keys: String): JsonPrimitive? {
                        keys.forEach { k -> (props?.get(k) as? JsonPrimitive)?.let { return it } }
                        return null
                    }
                    val mmsi = p("mmsi")?.contentOrNull
                        ?: (fo["mmsi"] as? JsonPrimitive)?.contentOrNull ?: ""
                    Vessel(
                        mmsi = mmsi,
                        name = p("name", "ship_name", "shipname", "vesselName")
                            ?.contentOrNull?.trim().orEmpty(),
                        lat = lat, lon = lon,
                        course = p("cog", "course", "courseOverGround")?.doubleOrNull ?: 0.0,
                        speed = p("sog", "speed", "speedOverGround")?.doubleOrNull ?: 0.0
                    )
                }.take(20000)
            } else {
                json.decodeFromString<List<AisVessel>>(body)
                    .filter { it.lat != null && it.lon != null }
                    .map {
                        Vessel(
                            mmsi = it.mmsi ?: "",
                            name = it.name?.trim().orEmpty(),
                            lat = it.lat!!, lon = it.lon!!,
                            course = it.course ?: 0.0, speed = it.speed ?: 0.0
                        )
                    }
                    .take(20000)
            }
        }
    }


    /**
     * Where the vessel is, from whatever geometry the feed sent.
     *
     * A Point is the position. A LineString or MultiPoint is the track
     * it has just travelled, and the last coordinate of that is where
     * it is now — taking the first would plot every ship where it was
     * several minutes ago, which on a map of moving things is a
     * mistake nobody would ever see.
     */
    internal fun position(geometry: JsonObject): Pair<Double, Double>? {
        val coordinates = geometry["coordinates"]?.jsonArray ?: return null
        val pair = when (geometry["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "Point" -> coordinates
            "LineString", "MultiPoint" ->
                coordinates.lastOrNull()?.let { it as? kotlinx.serialization.json.JsonArray }
            // An unfamiliar geometry is not guessed at. A polygon's
            // first vertex is a corner of something, not a ship.
            else -> if (coordinates.firstOrNull() is JsonPrimitive) coordinates else null
        } ?: return null
        val lon = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    @Serializable
    private data class AisVessel(
        val mmsi: String? = null,
        val name: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val course: Double? = null,
        val speed: Double? = null
    )
}
