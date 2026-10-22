package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        // A custom tar1090 feed wins; otherwise use keyless OpenSky worldwide,
        // and only fall back to mock data if both are unavailable.
        if (url.isBlank()) return@withContext openSky() ?: mock()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext openSky() ?: mock()
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
                    .ifEmpty { openSky() ?: mock() }
            }
        } catch (e: Exception) {
            openSky() ?: mock()
        }
    }

    /** Keyless global aircraft states from the OpenSky Network. Each state is a
     *  positional array; we read icao24, callsign, lon, lat, alt, velocity,
     *  heading. Capped so the globe stays responsive. */
    private fun openSky(): List<Aircraft>? = try {
        val request = Request.Builder()
            .url("https://opensky-network.org/api/states/all")
            .header("User-Agent", "OSINT-Unified/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) null
            else {
                val states = json.parseToJsonElement(body).jsonObject["states"]?.jsonArray
                val out = ArrayList<Aircraft>()
                states?.forEach { s ->
                    if (out.size >= 500) return@forEach
                    val a = s as? JsonArray ?: return@forEach
                    fun d(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
                    fun str(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull
                    val lon = d(5) ?: return@forEach
                    val lat = d(6) ?: return@forEach
                    out.add(
                        Aircraft(
                            icao = str(0).orEmpty(),
                            callsign = str(1)?.trim().orEmpty(),
                            lat = lat,
                            lon = lon,
                            altitude = d(7) ?: d(13) ?: 0.0,
                            track = d(10) ?: 0.0,
                            speed = d(9) ?: 0.0
                        )
                    )
                }
                out.ifEmpty { null }
            }
        }
    } catch (e: Exception) {
        null
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
