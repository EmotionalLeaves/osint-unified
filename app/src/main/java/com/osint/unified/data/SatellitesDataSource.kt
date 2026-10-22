package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Live positions of a handful of notable satellites (ISS, Tiangong, Hubble,
 * Terra, NOAA-20) from the keyless wheretheiss.at API.
 */
class SatellitesDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Notable tracked satellites (NORAD IDs): stations, observatories and
    // earth-observation birds. Requested in small chunks so one unknown ID
    // can't lose the whole set.
    private val noradIds = listOf(
        25544, 48274, 20580, 25994, 43013,   // ISS, Tiangong, Hubble, Terra, NOAA-20
        27424, 33591, 39084, 49260, 37849,   // Aqua, NOAA-19, Landsat-8/9, Suomi NPP
        40697, 42063, 28376, 29108, 41240,   // Sentinel-2A/2B, Aura, CALIPSO, Jason-3
        43613, 43476, 41866, 51850, 38771,   // ICESat-2, GRACE-FO, GOES-16/18, MetOp-B
        40069, 36744, 28654, 25338, 33105    // MetOp-A/C-class, NOAA-18/15, others
    )

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val out = ArrayList<GeoBlip>()
        noradIds.chunked(5).forEach { chunk ->
            runCatching {
                val request = Request.Builder()
                    .url("https://api.wheretheiss.at/v1/satellites/" + chunk.joinToString(","))
                    .header("User-Agent", "OSINT-Unified/1.0")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        json.parseToJsonElement(body).jsonArray.forEach { el ->
                            val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                            val lat = d("latitude") ?: return@forEach
                            val lon = d("longitude") ?: return@forEach
                            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "sat"
                            val alt = d("altitude")?.toInt()
                            out.add(GeoBlip(lat, lon, if (alt != null) "$name · ${alt}km" else name))
                        }
                    }
                }
            }
        }
        out
    }
}
