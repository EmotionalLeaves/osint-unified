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
 * Aircraft on the FAA's Limited Aircraft Data Displayed list — owners who have
 * asked to be hidden from public flight tracking — still visible on the open
 * ADS-B network (adsb.lol, keyless).
 *
 * The interesting signal is not the flight but the fact that someone paid to
 * suppress it, so the label leads with the registration.
 */
class LaddAircraftDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.adsb.lol/v2/ladd")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
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
                    val reg = s("r")?.trim()?.takeIf { it.isNotBlank() }
                    val call = s("flight")?.trim()?.takeIf { it.isNotBlank() }
                    val label = buildString {
                        append("LADD ")
                        append(reg ?: s("hex")?.uppercase() ?: "?")
                        if (call != null && call != reg) append(" · $call")
                        s("t")?.let { append(" · $it") }
                        d("alt_baro")?.let { append(" · ${it.toInt()}ft") }
                    }
                    out.add(GeoBlip(lat, lon, label, reg?.let { "https://globe.adsb.lol/?reg=$it" }))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
