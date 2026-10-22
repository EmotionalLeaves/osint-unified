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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Live military-class ADS-B tracks worldwide from adsb.fi open data (keyless):
 * aircraft squawking military/interesting codes. Plotted as the MIL layer.
 */
class MilAircraftDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://opendata.adsb.fi/api/v2/mil")
                .header("User-Agent", "OSINT-Unified/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val ac = json.parseToJsonElement(body).jsonObject["ac"]?.jsonArray
                    ?: return@withContext emptyList()
                val out = ArrayList<GeoBlip>()
                ac.forEach { el ->
                    if (out.size >= 5000) return@forEach
                    val o = el as? JsonObject ?: return@forEach
                    fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                    val lat = d("lat") ?: return@forEach
                    val lon = d("lon") ?: return@forEach
                    val label = (s("flight")?.trim()?.takeIf { it.isNotBlank() } ?: s("hex") ?: "MIL") +
                        (s("t")?.let { " · $it" } ?: "")
                    out.add(GeoBlip(lat, lon, label))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
