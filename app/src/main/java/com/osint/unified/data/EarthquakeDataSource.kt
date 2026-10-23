package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Quake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Everything that shook in the last day, from both agencies that
 * publish it openly.
 *
 * The USGS catalogue is the one everybody uses and it is genuinely
 * global, but it is a US agency reporting the world: small events far
 * from its own networks arrive late or not at all. The EMSC collects
 * the same ocean from European, Mediterranean and central Asian
 * networks and publishes a magnitude 2 in Greece within minutes of it
 * happening, which the USGS feed may never carry.
 *
 * So both, merged on time and place rather than on id — the two
 * agencies number the same earthquake differently, and a map that
 * draws one event twice is telling you there were two.
 */
class EarthquakeDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<Quake> = withContext(Dispatchers.IO) {
        val usgs = runCatching { usgs() }
        val emsc = runCatching { emsc() }
        if (usgs.isFailure && emsc.isFailure) {
            throw IOException(
                "No seismic agency could be reached: " +
                    (usgs.exceptionOrNull()?.message ?: "USGS failed")
            )
        }
        merge(usgs.getOrDefault(emptyList()), emsc.getOrDefault(emptyList()))
    }

    /**
     * One event reported by both agencies is one event.
     *
     * They do not share identifiers, so the match is physical: within
     * two minutes, a quarter of a degree, and half a magnitude. Looser
     * than that starts merging aftershocks into their mainshock, which
     * on a map of a sequence is the interesting part thrown away.
     */
    internal fun merge(primary: List<Quake>, extra: List<Quake>): List<Quake> {
        val out = ArrayList(primary)
        extra.forEach { candidate ->
            val duplicate = out.any { held ->
                kotlin.math.abs(held.lat - candidate.lat) < 0.25 &&
                    kotlin.math.abs(held.lon - candidate.lon) < 0.25 &&
                    kotlin.math.abs(held.mag - candidate.mag) < 0.5 &&
                    nearInTime(held.atMillis, candidate.atMillis)
            }
            if (!duplicate) out.add(candidate)
        }
        return out
    }

    private fun nearInTime(a: Long?, b: Long?): Boolean =
        if (a == null || b == null) true else kotlin.math.abs(a - b) < 120_000L

    private fun usgs(): List<Quake> {
        return try {
            val request = Request.Builder()
                .url("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("USGS HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("USGS returned nothing")
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: throw IOException("USGS answered in an unexpected shape")
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val coords = (o["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                    val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val props = o["properties"] as? JsonObject
                    fun p(k: String) = (props?.get(k) as? JsonPrimitive)
                    val mag = p("mag")?.doubleOrNull ?: 0.0
                    Quake(
                        lat = lat,
                        lon = lon,
                        mag = mag,
                        // USGS already names the place and times the event;
                        // the layer was throwing both away.
                        place = p("place")?.contentOrNull.orEmpty(),
                        atMillis = p("time")?.longOrNull,
                        depthKm = (coords.getOrNull(2) as? JsonPrimitive)?.doubleOrNull,
                        url = p("url")?.contentOrNull
                    )
                }
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("USGS unavailable: ${e.message}", e)
        }
    }

    /**
     * The EMSC, which reports the eastern hemisphere faster.
     *
     * Its FDSN service answers GeoJSON, but not the USGS's GeoJSON:
     * the coordinates are longitude, latitude, depth in kilometres as
     * a negative number, and the magnitude, region and origin time are
     * properties under different names. Parsed separately rather than
     * fed through one lenient reader, because a reader loose enough to
     * take both would also accept a depth as a magnitude.
     */
    private fun emsc(): List<Quake> {
        val request = Request.Builder()
            .url(
                "https://www.seismicportal.eu/fdsnws/event/1/query" +
                    "?format=json&limit=800&orderby=time"
            )
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IOException("EMSC HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("EMSC returned nothing")
            val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                ?: throw IOException("EMSC answered in an unexpected shape")
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            features.mapNotNull { f ->
                val o = f.jsonObject
                val props = o["properties"] as? JsonObject
                fun p(k: String) = (props?.get(k) as? JsonPrimitive)
                val lat = p("lat")?.doubleOrNull ?: return@mapNotNull null
                val lon = p("lon")?.doubleOrNull ?: return@mapNotNull null
                val at = Freshness.parse(p("time")?.contentOrNull)
                // The same day the USGS feed covers, so the two layers
                // describe the same window and the count means something.
                if (at != null && at < cutoff) return@mapNotNull null
                Quake(
                    lat = lat,
                    lon = lon,
                    mag = p("mag")?.doubleOrNull ?: 0.0,
                    place = p("flynn_region")?.contentOrNull.orEmpty(),
                    atMillis = at,
                    // EMSC gives depth below the surface as a negative
                    // number; everything else here means kilometres down.
                    depthKm = p("depth")?.doubleOrNull?.let { kotlin.math.abs(it) },
                    url = p("unid")?.contentOrNull
                        ?.let { "https://www.seismicportal.eu/eventdetails.html?unid=$it" }
                )
            }
        }
    }
}
