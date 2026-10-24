package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cameras: everywhere from OpenStreetMap, and live pictures where there
 * are any.
 *
 * This layer used to be Caltrans alone — about fifteen hundred traffic
 * cameras, every one of them in California, which is an empty map
 * anywhere else and most places are anywhere else. Caltrans is still
 * here, because a live still frame is worth more than a dot, but the
 * layer's coverage now comes from OpenStreetMap's mapped surveillance
 * cameras, which exist worldwide and carry who operates them and which
 * way they point.
 */
class CctvDataSource(
    private val client: OkHttpClient = Http.feedClient,
    private val surveillance: SurveillanceDataSource = SurveillanceDataSource()
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val districts = listOf("3", "4", "5", "6", "7", "8", "10", "11", "12")

    /**
     * A public camera network, and the box of the world it covers.
     *
     * The request was for a live view of anywhere, and the honest
     * version of that is the cameras their operators meant to be
     * public: roads authorities, cities and national transport
     * agencies that publish their own feeds. Four of them answer
     * without a key or an account, which was all four that were
     * probed.
     *
     * Fetched by where the globe is pointed rather than all at
     * once. London's cameras are a megabyte and Helsinki's half of
     * one, and downloading either of them to look at Tokyo is
     * somebody's data spent on nothing.
     */
    private data class Network(
        val name: String,
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double,
        val fetch: suspend () -> List<GeoBlip>
    ) {
        fun covers(lat: Double, lon: Double, marginDeg: Double = 3.0): Boolean =
            lat >= south - marginDeg && lat <= north + marginDeg &&
                lon >= west - marginDeg && lon <= east + marginDeg
    }

    private val networks: List<Network> by lazy {
        listOf(
            Network("New York City DOT", 40.4, 41.0, -74.3, -73.6) { newYork() },
            Network("Transport for London", 51.2, 51.8, -0.6, 0.4) { london() },
            Network("Fintraffic (Finland)", 59.7, 70.2, 19.0, 31.7) { finland() }
        )
    }

    @Volatile private var cache: List<GeoBlip> = emptyList()
    @Volatile private var fetchedAt = 0L

    private val regional = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<GeoBlip>>>()

    /**
     * Cameras around where the globe is pointed, plus the Californian
     * ones with pictures.
     *
     * Both, rather than one or the other: the mapped set gives coverage
     * and provenance, the Caltrans set gives an image you can look at.
     * Either failing leaves the other.
     */
    suspend fun near(lat: Double, lon: Double): List<GeoBlip> = withContext(Dispatchers.IO) {
        val mapped = runCatching { surveillance.near(lat, lon) }
        // Caltrans only where Caltrans is.
        //
        // Every other network in this file is fetched by where the
        // globe is pointed, with a comment explaining that London's
        // cameras are a megabyte and downloading them to look at
        // Tokyo is somebody's data spent on nothing. The Californian
        // set was merged unconditionally anyway, so the pill over
        // Warsaw counted fifteen hundred cameras in California and
        // the globe drew none of them.
        val wantsCaltrans = CALIFORNIA.covers(lat, lon)
        val live = if (wantsCaltrans) runCatching { fetch() } else null
        // And whichever operator publishes cameras where you are
        // looking. Only those: the test is a box with a few degrees
        // of margin, so panning towards a city picks its network up
        // before you arrive and looking at an ocean fetches nothing.
        val here = networks.filter { it.covers(lat, lon) }
        val local = coroutineScope {
            here.map { net -> async { runCatching { cached(net) } } }.map { it.await() }
        }
        val attempts = listOfNotNull(mapped, live) + local
        val merged = attempts.flatMap { it.getOrDefault(emptyList()) }
        // A zero that was established is a zero. Looking at the middle
        // of the Pacific, where OpenStreetMap has no mapped cameras
        // and no operator publishes any, used to be reported as a feed
        // failure — the inverse of the mistake the rest of this pass
        // is about, and it put a red mark on a layer that had worked.
        if (merged.isEmpty() && attempts.all { it.isFailure }) {
            throw java.io.IOException("no camera source answered")
        }
        merged
    }

    /** The state Caltrans publishes cameras for, with the margin the
     *  other networks use. */
    private val CALIFORNIA = Network("Caltrans", 32.5, 42.1, -124.5, -114.1) { emptyList() }

    /** Six hours, per network, like the Californian set. */
    private suspend fun cached(net: Network): List<GeoBlip> {
        val held = regional[net.name]
        if (held != null && System.currentTimeMillis() - held.first < 6 * 60 * 60 * 1000L) {
            return held.second
        }
        val fresh = net.fetch()
        if (fresh.isNotEmpty()) regional[net.name] = System.currentTimeMillis() to fresh
        return fresh
    }

    /**
     * New York City's traffic cameras — about nine hundred of them,
     * each with a still image endpoint of its own.
     */
    private fun newYork(): List<GeoBlip> {
        val body = body("https://webcams.nyctmc.org/api/cameras") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val lat = s("latitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = s("longitude")?.toDoubleOrNull() ?: return@mapNotNull null
            if (s("isOnline") == "false") return@mapNotNull null
            val label = buildString {
                append("CAM ").append(s("name") ?: "camera")
                s("area")?.let { append(" · ").append(it) }
                append(" · NYC DOT")
            }
            GeoBlip(lat, lon, label, s("imageUrl"))
        }
    }

    /**
     * Transport for London's JamCams.
     *
     * The image URL is buried in an additionalProperties list
     * rather than being a field, which is why this one takes more
     * reading than the others.
     */
    private fun london(): List<GeoBlip> {
        val body = body("https://api.tfl.gov.uk/Place/Type/JamCam") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val lat = (o["lat"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val lon = (o["lon"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val props = o["additionalProperties"] as? JsonArray
            val image = props?.firstNotNullOfOrNull { p ->
                val po = p as? JsonObject ?: return@firstNotNullOfOrNull null
                val key = (po["key"] as? JsonPrimitive)?.contentOrNull
                val value = (po["value"] as? JsonPrimitive)?.contentOrNull
                if (key == "imageUrl" && !value.isNullOrBlank()) value else null
            }
            GeoBlip(
                lat, lon,
                "CAM ${s("commonName") ?: "JamCam"} · Transport for London",
                image
            )
        }
    }

    /** Fintraffic's roadside weather cameras, as GeoJSON. */
    private fun finland(): List<GeoBlip> {
        val body = body("https://tie.digitraffic.fi/api/weathercam/v1/stations")
            ?: return emptyList()
        val features = runCatching {
            json.parseToJsonElement(body).jsonObject["features"] as? JsonArray
        }.getOrNull() ?: return emptyList()
        return features.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val coords = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
                ?: return@mapNotNull null
            val lon = (coords.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val lat = (coords.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val props = o["properties"] as? JsonObject
            fun p(k: String) = (props?.get(k) as? JsonPrimitive)?.contentOrNull
            if (p("collectionStatus") == "REMOVED_PERMANENTLY") return@mapNotNull null
            val id = p("id") ?: return@mapNotNull null
            GeoBlip(
                lat, lon,
                "CAM ${p("name") ?: id} · Fintraffic",
                "https://weathercam.digitraffic.fi/$id.jpg"
            )
        }
    }

    private fun body(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        // The inventory changes rarely; refresh at most every six hours.
        val age = System.currentTimeMillis() - fetchedAt
        if (cache.isNotEmpty() && age < 6 * 60 * 60 * 1000L) return@withContext cache
        val merged = coroutineScope {
            districts.map { d -> async { runCatching { district(d) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
        }
        // An empty answer is cached too, for a tenth as long.
        //
        // It used not to be, and `fetchedAt` was not advanced either,
        // so the six-hour check could never short-circuit: with the
        // Caltrans host unreachable, every settled viewport change —
        // a 250 ms debounce on the flat map — fired twelve parallel
        // district requests, back to back, for as long as the pan
        // lasted, with no backoff. On mobile data.
        cache = merged
        fetchedAt =
            if (merged.isNotEmpty()) System.currentTimeMillis()
            // Backdated, so an empty result is retried in thirty-six
            // minutes rather than in six hours or on the next pan.
            else System.currentTimeMillis() - (6 * 60 * 60 * 1000L * 9 / 10)
        merged
    }

    private fun district(d: String): List<GeoBlip> {
        val padded = if (d.length == 1) "0$d" else d
        val url = "https://cwwp2.dot.ca.gov/data/d$d/cctv/cctvStatusD$padded.json"
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
            val out = ArrayList<GeoBlip>(data.size)
            data.forEach { el ->
                val cctv = (el as? JsonObject)?.get("cctv") as? JsonObject ?: return@forEach
                val loc = cctv["location"] as? JsonObject ?: return@forEach
                fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
                val lat = s(loc, "latitude")?.toDoubleOrNull() ?: return@forEach
                val lon = s(loc, "longitude")?.toDoubleOrNull() ?: return@forEach
                if (lat == 0.0 && lon == 0.0) return@forEach
                val inService = s(cctv["imageData"] as? JsonObject, "imageDescription")
                val name = s(loc, "locationName") ?: "camera"
                val place = s(loc, "nearbyPlace")
                val route = s(loc, "route")
                val image = s((cctv["imageData"] as? JsonObject)?.get("static") as? JsonObject, "currentImageURL")
                val label = buildString {
                    append("CAM ")
                    append(name)
                    if (!place.isNullOrBlank()) append(" · $place")
                    if (!route.isNullOrBlank()) append(" · $route")
                    if (!inService.isNullOrBlank()) append(" · $inService")
                }
                out.add(GeoBlip(lat, lon, label, image))
            }
            return out
        }
    }
}
