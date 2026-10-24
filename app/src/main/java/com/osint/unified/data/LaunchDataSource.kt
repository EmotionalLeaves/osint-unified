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
 * What is going to space, and from where.
 *
 * Rockets leave from about thirty places on Earth and the schedule is
 * public, so this is one of the few genuinely worldwide feeds that
 * needs nothing but a request. For an investigator it is the same
 * class of fact as a NOTAM: a launch closes airspace and sea lanes,
 * puts ships in particular places, and explains an unusual amount of
 * activity around a pad.
 *
 * The window is deliberately narrow. A launch three months out is a
 * plan rather than an event, and a map covered in plans is a map
 * nobody reads.
 */
class LaunchDataSource(
    private val store: DiskCache? = null,
    private val client: OkHttpClient = Http.feedClient
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val body = load() ?: throw IOException("Launch schedule unavailable")
        parse(body, System.currentTimeMillis())
    }

    private fun load(): String? {
        store?.read(CACHE_KEY, CACHE_TTL_MS)?.let { return it }
        val fetched = runCatching {
            val request = Request.Builder()
                .url(SOURCE)
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
        if (!fetched.isNullOrBlank() && fetched.trimStart().startsWith("{")) {
            store?.write(CACHE_KEY, fetched)
            return fetched
        }
        return store?.readStale(CACHE_KEY)
    }

    /**
     * Launches into markers, one per pad.
     *
     * The coordinates are the pad's, which is the honest position:
     * the rocket is at the pad until it is not, and after that it is
     * not anywhere a map marker could follow.
     */
    internal fun parse(body: String, nowMs: Long): List<GeoBlip> {
        // Not "no rocket is launching anywhere in the next three
        // months", which is what an empty list from here says.
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw java.io.IOException("the launch API answered in an unexpected shape")
        val results = root["results"]?.jsonArray
            ?: throw java.io.IOException("the launch API returned no results block")
        val horizon = nowMs + WINDOW_MS

        data class Pad(val lat: Double, val lon: Double, val place: String) {
            val key: String get() = "%.3f,%.3f".format(lat, lon)
        }

        val byPad = LinkedHashMap<String, Pair<Pad, MutableList<String>>>()
        results.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            fun s(obj: JsonObject?, key: String) =
                (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

            val at = Freshness.parse(s(o, "net"))
            // A launch three months out is a plan, not an event.
            if (at != null && at > horizon) return@forEach

            val padObject = o["pad"] as? JsonObject
            val location = padObject?.get("location") as? JsonObject
            // The API sends the coordinates as strings on the pad, and
            // the names on the location inside it.
            val lat = (padObject?.get("latitude") as? JsonPrimitive)
                ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?: return@forEach
            val lon = (padObject["longitude"] as? JsonPrimitive)
                ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?: return@forEach
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach

            val place = s(location, "name") ?: s(padObject, "name") ?: "launch site"
            val name = s(o, "name") ?: "launch"
            val status = ((o["status"] as? JsonObject)?.get("abbrev") as? JsonPrimitive)
                ?.contentOrNull
            val when0 = at?.let { Freshness.ago(it) ?: Freshness.until(it) }

            val described = buildString {
                append(name)
                if (!status.isNullOrBlank() && !status.equals("Go", ignoreCase = true)) {
                    append(" (").append(status).append(")")
                }
                when0?.let { append(" · ").append(it) }
            }
            val pad = Pad(lat, lon, place)
            byPad.getOrPut(pad.key) { pad to mutableListOf() }.second.add(described)
        }

        return byPad.values.map { (pad, launches) ->
            val label = buildString {
                append(pad.place)
                append(" · ")
                append(launches.first())
                if (launches.size > 1) append(" +${launches.size - 1} more")
            }
            GeoBlip(pad.lat, pad.lon, label, "https://nextspaceflight.com/")
        }
    }

    private companion object {
        const val SOURCE =
            "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?limit=40&hide_recent_previous=true"
        const val CACHE_KEY = "launch-schedule"

        /** The schedule moves in hours, not minutes, and the service
         *  asks politely for restraint. */
        const val CACHE_TTL_MS = 60 * 60_000L

        /** Three weeks. Far enough to be worth knowing, near enough to
         *  still be an event rather than an intention. */
        const val WINDOW_MS = 21 * 24 * 3600_000L
    }
}
