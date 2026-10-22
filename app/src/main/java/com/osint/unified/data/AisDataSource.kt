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
 * Maritime vessel positions for the God's Eye View.
 *
 * The AIS setting may list several feed URLs (comma/newline-separated); they're
 * fetched and merged into one layer, so adding regional AIS sources builds
 * toward a global picture. Each feed may be a GeoJSON FeatureCollection (e.g.
 * Finland's keyless Digitraffic open AIS) or a plain JSON array of vessels.
 * Falls back to demo vessels if no feed returns data.
 */
class AisDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
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
                    val coords = (fo["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val props = fo["properties"] as? JsonObject
                    fun p(k: String) = props?.get(k) as? JsonPrimitive
                    val mmsi = p("mmsi")?.contentOrNull ?: (fo["mmsi"] as? JsonPrimitive)?.contentOrNull ?: ""
                    Vessel(
                        mmsi = mmsi,
                        name = p("name")?.contentOrNull?.trim().orEmpty(),
                        lat = lat, lon = lon,
                        course = p("cog")?.doubleOrNull ?: 0.0,
                        speed = p("sog")?.doubleOrNull ?: 0.0
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

}
