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
import java.io.IOException

/**
 * Live military-class ADS-B tracks worldwide from adsb.fi open data (keyless):
 * aircraft squawking military/interesting codes. Plotted as the MIL layer.
 */
class MilAircraftDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://opendata.adsb.fi/api/v2/mil")
                .header("User-Agent", "OSINT-Unified/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                // Named, because this is the failure the layer
                // actually has. adsb.fi refuses a second request
                // from the same address within a few seconds, and a
                // phone behind carrier NAT shares that address with
                // strangers — so "it does not work" is usually "the
                // queue for this address is full", which is a
                // different thing and worth saying.
                if (resp.code == 429) {
                    throw IOException(
                        "adsb.fi is rate limiting this address. It is not that " +
                            "there are no military aircraft; it is that this " +
                            "phone has been asked to wait."
                    )
                }
                if (!resp.isSuccessful) throw IOException("adsb.fi military HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("adsb.fi military returned nothing")
                val ac = json.parseToJsonElement(body).jsonObject["ac"]?.jsonArray
                    ?: throw IOException("adsb.fi military answered in an unexpected shape")
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
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("adsb.fi military unavailable: ${e.message}", e)
        }
    }
}
