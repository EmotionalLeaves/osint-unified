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

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.wheretheiss.at/v1/satellites/25544,48274,20580,25994,43013")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                    val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                    val lat = d("latitude") ?: return@mapNotNull null
                    val lon = d("longitude") ?: return@mapNotNull null
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "sat"
                    GeoBlip(lat, lon, name)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
