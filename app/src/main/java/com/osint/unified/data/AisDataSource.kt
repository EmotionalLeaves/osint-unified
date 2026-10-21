package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Vessel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext mock()
                json.decodeFromString<List<AisVessel>>(body)
                    .filter { it.lat != null && it.lon != null }
                    .map {
                        Vessel(
                            mmsi = it.mmsi ?: "",
                            name = it.name?.trim().orEmpty(),
                            lat = it.lat!!,
                            lon = it.lon!!,
                            course = it.course ?: 0.0,
                            speed = it.speed ?: 0.0
                        )
                    }
                    .ifEmpty { mock() }
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
