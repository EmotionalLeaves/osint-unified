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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * METAR airport-weather observations from the Aviation Weather Center (keyless):
 * station position plus flight category (VFR/MVFR/IFR/LIFR) and temperature.
 */
class MetarDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://aviationweather.gov/api/data/metar?format=json&bbox=-90,-180,90,180")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val arr = json.parseToJsonElement(body).jsonArray
                val out = ArrayList<GeoBlip>()
                for (el in arr) {
                    if (out.size >= 8000) break
                    val o = el as? JsonObject ?: continue
                    fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                    val lat = d("lat") ?: continue
                    val lon = d("lon") ?: continue
                    val id = s("icaoId") ?: "METAR"
                    val cat = s("fltCat")?.let { " $it" } ?: ""
                    out.add(GeoBlip(lat, lon, "$id$cat"))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
