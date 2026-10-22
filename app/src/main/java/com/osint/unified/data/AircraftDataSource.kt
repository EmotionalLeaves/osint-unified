package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Provides aircraft positions for the God's Eye View.
 *
 * If a tar1090 feed URL is configured in [AppSettings], it fetches and parses
 * the standard tar1090 `aircraft.json`. Otherwise it sweeps the keyless open
 * ADS-B aggregators.
 *
 * There is no demo fallback. This used to invent plausible aircraft around
 * major airports whenever every source failed, which on an intelligence map
 * is worse than an empty layer: a fabricated contact is indistinguishable
 * from a real one, and nothing on screen said which it was.
 */
class AircraftDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [focus] is the centre of the map and [wide] whether it is zoomed out
     * far enough to be looking at the world rather than a region.
     *
     * This matters more than it looks. Each hub response is around half a
     * megabyte, the layer refreshes every fifteen seconds, and sweeping
     * every hub against both providers would pull roughly eighty megabytes
     * a refresh — gigabytes an hour of someone's mobile data, and a quick
     * route to being rate limited. So the sweep follows the viewport:
     * a spread sample when zoomed out, the nearby hubs when zoomed in.
     */
    suspend fun fetch(
        focus: Pair<Double, Double>? = null,
        wide: Boolean = true
    ): List<Aircraft> = withContext(Dispatchers.IO) {
        val url = settings.tar1090Url.trim()
        // Source order: a custom tar1090 feed, then keyless OpenSky (global),
        // then keyless adsb.fi (hub sweep), and only demo data if all are down.
        if (url.isBlank()) return@withContext merged(focus, wide)
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext merged(focus, wide)
                val envelope = json.decodeFromString<Tar1090Envelope>(body)
                envelope.aircraft
                    .filter { it.lat != null && it.lon != null }
                    .map { ac ->
                        Aircraft(
                            icao = ac.hex.orEmpty(),
                            callsign = ac.flight?.trim().orEmpty(),
                            lat = ac.lat!!,
                            lon = ac.lon!!,
                            altitude = (ac.alt_baro as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                            track = ac.track ?: 0.0,
                            speed = ac.gs ?: 0.0
                        )
                    }
                    .ifEmpty { merged(focus, wide) }
            }
        } catch (e: Exception) {
            merged(focus, wide)
        }
    }

    /**
     * Both keyless providers, merged. OpenSky is global but rate-limits
     * anonymous callers hard; the ADS-B aggregators are per-region but
     * reliable. Using both covers far more of the sky than either, and the
     * demo positions only appear if every real source is down.
     */
    private suspend fun merged(focus: Pair<Double, Double>?, wide: Boolean): List<Aircraft> {
        val byHex = LinkedHashMap<String, Aircraft>()
        runCatching { adsbSweep(focus, wide) }.getOrNull()?.forEach { byHex[it.icao] = it }
        // OpenSky is one request for the whole planet, so it is always worth
        // asking even though it rate-limits anonymous callers.
        runCatching { openSky() }.getOrNull()?.forEach { byHex.putIfAbsent(it.icao, it) }
        return byHex.values.toList()
    }

    /**
     * Keyless live aircraft from the open ADS-B aggregators, swept across
     * the world's traffic regions and merged by ICAO hex.
     *
     * Two bugs lived here. The parser only looked for an `ac` array, which
     * is adsb.lol's shape — adsb.fi returns `aircraft`, so this source had
     * never once returned a plane, and the layer was really just OpenSky's
     * truncated 250. And eight hub points at 250 nm covered a rounding
     * error of the planet. Both fixed: both shapes parsed, both providers
     * queried, and enough hubs to cover where aircraft actually are.
     */
    private suspend fun adsbSweep(
        focus: Pair<Double, Double>?,
        wide: Boolean
    ): List<Aircraft>? = coroutineScope {
        val out = java.util.concurrent.ConcurrentHashMap<String, Aircraft>()
        val gate = Semaphore(6)
        hubsFor(focus, wide).mapIndexed { index, hub ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    // One provider per hub, alternating, so the two are both
                    // exercised without doubling the traffic.
                    val provider = PROVIDERS[index % PROVIDERS.size]
                    runCatching { sweepOne(provider, hub.first, hub.second, out) }
                }
            }
        }.forEach { it.await() }
        if (out.isEmpty()) null else out.values.toList()
    }

    /**
     * Zoomed out, a spread sample of the busiest regions — enough to show
     * the world has traffic without downloading all of it. Zoomed in, the
     * hubs nearest what is on screen, so detail appears where it is being
     * looked at.
     */
    private fun hubsFor(focus: Pair<Double, Double>?, wide: Boolean): List<Pair<Double, Double>> {
        if (focus == null || wide) return HUBS.filterIndexed { i, _ -> i % 6 == 0 }
        val (lat, lon) = focus
        return HUBS.sortedBy { (hLat, hLon) ->
            val dLat = hLat - lat
            val dLon = ((hLon - lon + 540) % 360) - 180
            dLat * dLat + dLon * dLon
        }.take(6)
    }

    private fun sweepOne(
        provider: String,
        lat: Double,
        lon: Double,
        into: MutableMap<String, Aircraft>
    ) {
        val request = Request.Builder()
            .url("$provider$lat/lon/$lon/dist/250")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return
            val root = json.parseToJsonElement(body).jsonObject
            // adsb.lol says "ac", adsb.fi says "aircraft".
            val list = (root["ac"] ?: root["aircraft"])?.jsonArray ?: return
            list.forEach { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val la = d("lat") ?: return@forEach
                val lo = d("lon") ?: return@forEach
                val hex = str("hex") ?: return@forEach
                // Registration, type and operator where the feed carries
                // them, so a tapped aircraft identifies itself.
                val callsign = str("flight")?.trim().orEmpty()
                val reg = str("r")?.trim().orEmpty()
                val type = str("t")?.trim().orEmpty()
                val operator = str("ownOp")?.trim().orEmpty()
                val label = buildString {
                    append(callsign.ifBlank { reg.ifBlank { hex.uppercase() } })
                    if (reg.isNotBlank() && reg != callsign) append(" · $reg")
                    if (type.isNotBlank()) append(" · $type")
                    if (operator.isNotBlank()) append(" · ${operator.take(40)}")
                }
                into[hex] = Aircraft(
                    icao = hex,
                    callsign = label,
                    lat = la,
                    lon = lo,
                    altitude = d("alt_baro") ?: 0.0,
                    track = d("track") ?: 0.0,
                    speed = d("gs") ?: 0.0
                )
            }
        }
    }

    private fun openSky(): List<Aircraft>? = try {
        val request = Request.Builder()
            .url("https://opensky-network.org/api/states/all")
            .header("User-Agent", "OSINT-Unified/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) null
            else {
                val states = json.parseToJsonElement(body).jsonObject["states"]?.jsonArray
                val out = ArrayList<Aircraft>()
                states?.forEach { s ->
                    if (out.size >= 20000) return@forEach
                    val a = s as? JsonArray ?: return@forEach
                    fun d(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
                    fun str(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull
                    val lon = d(5) ?: return@forEach
                    val lat = d(6) ?: return@forEach
                    out.add(
                        Aircraft(
                            icao = str(0).orEmpty(),
                            callsign = str(1)?.trim().orEmpty(),
                            lat = lat,
                            lon = lon,
                            altitude = d(7) ?: d(13) ?: 0.0,
                            track = d(10) ?: 0.0,
                            speed = d(9) ?: 0.0
                        )
                    )
                }
                out.ifEmpty { null }
            }
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        val PROVIDERS = listOf(
            "https://api.adsb.lol/v2/lat/",
            "https://opendata.adsb.fi/api/v2/lat/"
        )

        /** Traffic regions, each covered to a 250 nm radius. Weighted to
         *  where aircraft are rather than spread evenly over empty ocean. */
        val HUBS: List<Pair<Double, Double>> = listOf(
            // North America
            40.0 to -74.0, 42.0 to -88.0, 33.9 to -118.0, 37.6 to -122.4,
            32.9 to -97.0, 39.9 to -82.9, 25.8 to -80.3, 29.8 to -95.3,
            47.4 to -122.3, 39.7 to -105.0, 45.5 to -73.6, 43.7 to -79.6,
            49.2 to -123.2, 61.2 to -149.9, 19.4 to -99.1,
            // South America
            -23.5 to -46.6, -34.6 to -58.4, 4.7 to -74.1, -12.0 to -77.0,
            -33.4 to -70.7, 10.5 to -66.9,
            // Europe
            51.5 to -0.1, 48.9 to 2.4, 50.0 to 8.6, 52.5 to 13.4,
            52.3 to 4.8, 41.9 to 12.5, 40.4 to -3.7, 55.6 to 12.6,
            59.3 to 18.1, 47.4 to 8.5, 50.1 to 14.4, 52.2 to 21.0,
            55.8 to 37.6, 41.3 to 2.1, 53.4 to -2.2, 55.9 to -3.2,
            37.9 to 23.7, 50.4 to 30.5, 60.2 to 24.9,
            // Africa and the Middle East
            25.3 to 55.4, 24.5 to 39.6, 29.2 to 48.0, 33.9 to 35.5,
            30.1 to 31.4, 36.8 to 3.1, 6.6 to 3.3, -26.1 to 28.2,
            -1.3 to 36.9, 14.7 to -17.5, 9.0 to 38.8, 32.0 to 34.9,
            // Asia
            35.6 to 139.8, 37.5 to 127.0, 31.2 to 121.5, 39.9 to 116.4,
            22.3 to 114.2, 23.0 to 113.4, 25.1 to 121.2, 1.36 to 103.99,
            13.7 to 100.7, 3.1 to 101.7, -6.1 to 106.7, 14.5 to 121.0,
            28.6 to 77.1, 19.1 to 72.9, 13.0 to 80.2, 12.97 to 77.6,
            24.9 to 67.2, 23.8 to 90.4, 41.3 to 69.3, 43.2 to 76.9,
            // Oceania
            -33.9 to 151.2, -37.7 to 144.8, -27.4 to 153.1, -31.9 to 115.9,
            -36.9 to 174.8
        )
    }


    @Serializable
    private data class Tar1090Envelope(val aircraft: List<Tar1090Ac> = emptyList())

    @Serializable
    private data class Tar1090Ac(
        val hex: String? = null,
        val flight: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val alt_baro: JsonElement? = null,
        val track: Double? = null,
        val gs: Double? = null
    )
}
