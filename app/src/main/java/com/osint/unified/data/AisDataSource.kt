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
import kotlin.random.Random

/**
 * Provides maritime vessel positions for the God's Eye View.
 *
 * If an AIS feed URL is configured it fetches a JSON array of vessels
 * (`[{"mmsi","name","lat","lon","course","speed"}, ...]`). Otherwise, or on any
 * failure, it returns mock vessels near major ports so the globe shows ships.
 */
class AisDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<Vessel> = withContext(Dispatchers.IO) {
        val url = settings.aisUrl.trim()
        if (url.isBlank()) return@withContext mock()
        try {
            val request = Request.Builder().url(url).header("User-Agent", "OSINT-Unified/1.0").build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext mock()
                val root = json.parseToJsonElement(body)
                // GeoJSON FeatureCollection (e.g. Digitraffic open AIS) …
                val features = (root as? JsonObject)?.get("features")?.jsonArray
                if (features != null) {
                    features.mapNotNull { f ->
                        val fo = f.jsonObject
                        val coords = (fo["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                        val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        val props = fo["properties"] as? JsonObject
                        fun p(k: String) = (props?.get(k) as? JsonPrimitive)
                        val mmsi = p("mmsi")?.contentOrNull ?: (fo["mmsi"] as? JsonPrimitive)?.contentOrNull ?: ""
                        Vessel(
                            mmsi = mmsi,
                            name = p("name")?.contentOrNull?.trim().orEmpty(),
                            lat = lat, lon = lon,
                            course = p("cog")?.doubleOrNull ?: 0.0,
                            speed = p("sog")?.doubleOrNull ?: 0.0
                        )
                    }.take(800).ifEmpty { mock() }
                } else {
                    // … or a plain array of vessel objects.
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
                        .take(800).ifEmpty { mock() }
                }
            }
        } catch (e: Exception) {
            mock()
        }
    }

    private fun mock(): List<Vessel> {
        val ports = listOf(
            Triple("Singapore", 1.26, 103.84),
            Triple("Rotterdam", 51.95, 4.14),
            Triple("LA/LongBeach", 33.74, -118.26),
            Triple("Shanghai", 31.23, 121.50)
        )
        return ports.flatMap { (name, lat, lon) ->
            List(5) { i ->
                Vessel(
                    mmsi = "%09d".format(Random.nextInt(100_000_000, 999_999_999)),
                    name = "$name-${i + 1}",
                    lat = lat + Random.nextDouble(-1.5, 1.5),
                    lon = lon + Random.nextDouble(-1.5, 1.5),
                    course = Random.nextDouble(0.0, 360.0),
                    speed = Random.nextDouble(0.0, 22.0)
                )
            }
        }
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
