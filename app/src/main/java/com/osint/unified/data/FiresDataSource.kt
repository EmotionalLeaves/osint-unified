package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
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

/** Active wildfires worldwide from NASA EONET (keyless), as their own layer. */
class FiresDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://eonet.gsfc.nasa.gov/api/v3/events?category=wildfires&status=open&days=4&limit=2000")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val events = json.parseToJsonElement(body).jsonObject["events"]?.jsonArray
                    ?: return@withContext emptyList()
                events.mapNotNull { e ->
                    val o = e.jsonObject
                    val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: "Wildfire"
                    val geom = (o["geometry"] as? JsonArray)?.lastOrNull { g ->
                        ((g as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "Point"
                    } ?: return@mapNotNull null
                    val coords = (geom as JsonObject)["coordinates"]?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    // EONET keeps events open long after they stop burning,
                    // so the last observation date decides whether this is
                    // still live, and the label carries the age.
                    val seen = Freshness.parse((geom["date"] as? JsonPrimitive)?.contentOrNull)
                    // A fire last observed four days ago is history, not a map.
                    if (!Freshness.within(seen, 72)) return@mapNotNull null
                    val age = Freshness.ago(seen)
                    GeoBlip(lat, lon, if (age != null) "$title · $age" else title)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
