package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.NaturalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * Open natural events worldwide from NASA EONET v3 (keyless): wildfires,
 * severe storms, volcanoes, floods, icebergs, etc. Used as a live globe layer.
 */
class NaturalEventsDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<NaturalEvent> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=10&limit=2000")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("EONET events HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("EONET events returned nothing")
                val events = json.parseToJsonElement(body).jsonObject["events"]?.jsonArray
                    ?: throw IOException("EONET events answered in an unexpected shape")
                events.mapNotNull { e ->
                    val o = e.jsonObject
                    val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val category = (o["categories"] as? JsonArray)?.firstOrNull()
                        ?.jsonObject?.get("title")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "Event"
                    // Use the most recent Point geometry.
                    val geom = (o["geometry"] as? JsonArray)?.lastOrNull { g ->
                        ((g as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "Point"
                    } ?: return@mapNotNull null
                    val coords = (geom as JsonObject)["coordinates"]?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    // The geometry's own date is when the event was last
                    // observed there, which is the only honest timestamp.
                    val seen = Freshness.parse((geom["date"] as? JsonPrimitive)?.contentOrNull)
                    if (!Freshness.within(seen, 10 * 24)) return@mapNotNull null
                    val age = Freshness.ago(seen)
                    NaturalEvent(lat, lon, if (age != null) "$title · $age" else title, category)
                }
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("EONET events unavailable: ${e.message}", e)
        }
    }
}
