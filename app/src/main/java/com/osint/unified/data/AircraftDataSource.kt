package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/**
 * Provides aircraft positions for the God's Eye View.
 *
 * If a tar1090 feed URL is configured in [AppSettings], it fetches and parses
 * the standard tar1090 `aircraft.json`. Otherwise (or on any failure) it falls
 * back to mock positions so the globe is never empty.
 */
class AircraftDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<Aircraft> = withContext(Dispatchers.IO) {
        val url = settings.tar1090Url.trim()
        if (url.isBlank()) return@withContext mock()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext mock()
                val envelope = json.decodeFromString<Tar1090Envelope>(body)
                envelope.aircraft
                    .filter { it.lat != null && it.lon != null }
                    .map { ac ->
                        Aircraft(
                            icao = ac.hex.orEmpty(),
                            callsign = ac.flight?.trim().orEmpty(),
                            lat = ac.lat!!,
                            lon = ac.lon!!,
                            altitude = (ac.alt_baro as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                            track = ac.track ?: 0.0,
                            speed = ac.gs ?: 0.0
                        )
                    }
                    .ifEmpty { mock() }
            }
        } catch (e: Exception) {
            mock()
        }
    }

    private fun mock(): List<Aircraft> {
        val hubs = listOf(
            Triple("KLAX", 33.94, -118.40),
            Triple("KJFK", 40.64, -73.78),
            Triple("EGLL", 51.47, -0.45),
            Triple("RJTT", 35.55, 139.78),
            Triple("OMDB", 25.25, 55.36)
        )
        return hubs.flatMap { (code, lat, lon) ->
            List(6) {
                Aircraft(
                    icao = "%06X".format(Random.nextInt(0xFFFFFF)),
                    callsign = "$code${Random.nextInt(100, 999)}",
                    lat = lat + Random.nextDouble(-4.0, 4.0),
                    lon = lon + Random.nextDouble(-4.0, 4.0),
                    altitude = Random.nextDouble(2_000.0, 39_000.0),
                    track = Random.nextDouble(0.0, 360.0),
                    speed = Random.nextDouble(180.0, 540.0)
                )
            }
        }
    }

    @Serializable
    private data class Tar1090Envelope(val aircraft: List<Tar1090Ac> = emptyList())

    @Serializable
    private data class Tar1090Ac(
        val hex: String? = null,
        val flight: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val alt_baro: JsonElement? = null,
        val track: Double? = null,
        val gs: Double? = null
    )
}
