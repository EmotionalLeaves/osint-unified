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
import java.io.IOException
import java.util.Locale

/**
 * The height of the sea, everywhere somebody is measuring it.
 *
 * The IOC of UNESCO runs the global sea-level station network — the
 * same instruments that confirm a tsunami actually happened, and the
 * only live, worldwide, keyless measurement of the ocean there is.
 * Several hundred stations report every few minutes, from Aberdeen to
 * Abashiri.
 *
 * It is worth having for the obvious reason and for a less obvious
 * one. A tsunami bulletin is a warning; a tide gauge is an
 * observation, and the difference between the two is the difference
 * between "the Pacific centre thinks" and "the sea at this harbour is
 * two metres higher than it was". Storm surge shows up here first as
 * well.
 *
 * One request gives the whole network with each station's latest
 * reading, which is the only reason this is affordable: the per-station
 * endpoint would be six hundred requests.
 */
class SeaLevelDataSource(
    private val store: DiskCache? = null,
    private val client: OkHttpClient = Http.bulkClient
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val body = load() ?: throw IOException("IOC sea level network unavailable")
        val stations = parse(body, System.currentTimeMillis())
        if (stations.isEmpty()) {
            throw IOException("The sea level network answered with no live stations")
        }
        stations
    }

    private fun load(): String? {
        store?.read(CACHE_KEY, CACHE_TTL_MS)?.let { return it }
        val fetched = runCatching {
            val request = Request.Builder().url(SOURCE).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
        if (!fetched.isNullOrBlank() && fetched.trimStart().startsWith("[")) {
            store?.write(CACHE_KEY, fetched)
            return fetched
        }
        return store?.readStale(CACHE_KEY)
    }

    /**
     * Stations that are actually reporting.
     *
     * The list holds every station the programme has ever registered,
     * including ones that stopped sending in 2011. A gauge drawn on a
     * live map is read as a live reading, so a station whose last
     * value is older than a day is left off entirely rather than
     * plotted in a different colour — the map has no room to explain
     * itself and the explanation would not be read.
     */
    internal fun parse(body: String, nowMs: Long): List<GeoBlip> {
        val rows = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return emptyList()
        val cutoff = nowMs - LIVE_WINDOW_MS
        return rows.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "null" }
            fun d(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull

            val lat = d("lat") ?: d("Lat") ?: return@mapNotNull null
            val lon = d("lon") ?: d("Lon") ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val value = d("lastvalue") ?: return@mapNotNull null
            val at = Freshness.parse(s("lasttime")) ?: return@mapNotNull null
            if (at < cutoff) return@mapNotNull null

            val place = s("Location") ?: s("location") ?: s("Code") ?: "Tide gauge"
            val label = buildString {
                append(place)
                s("countryname")?.let { append(" · ").append(it) }
                append(" · ")
                append("%.2f m".format(Locale.US, value))
                Freshness.ago(at)?.let { append(" · ").append(it) }
            }
            GeoBlip(lat, lon, label, "https://www.ioc-sealevelmonitoring.org/")
        }
    }

    private companion object {
        const val SOURCE = "https://www.ioc-sealevelmonitoring.org/" +
            "service.php?query=stationlist&showall=all&format=json"
        const val CACHE_KEY = "ioc-sea-level"

        /** The stations report every few minutes; the file is two
         *  megabytes and the sea does not move that fast. */
        const val CACHE_TTL_MS = 10 * 60_000L

        /** Older than this and the station is not reporting, whatever
         *  the register says about it. */
        const val LIVE_WINDOW_MS = 24 * 3600_000L
    }
}
