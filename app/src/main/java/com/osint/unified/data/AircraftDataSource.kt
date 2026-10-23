package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import java.io.IOException

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
    private val client: OkHttpClient = Http.feedClient
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
        // then keyless adsb.fi (hub sweep). Nothing at all if none answer.
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
        } catch (e: CancellationException) {
            // Not a failure of the feed. Caught and then retried, this
            // started a fresh whole-planet request on a coroutine that
            // had already been told to stop — which is how leaving the
            // screen mid-fetch cost a megabyte and a half.
            throw e
        } catch (e: Exception) {
            merged(focus, wide)
        }
    }

    /**
     * Both keyless providers, merged. OpenSky is global but rate-limits
     * anonymous callers hard; the ADS-B aggregators are per-region but
     * reliable. Using both covers far more of the sky than either.
     *
     * Throws when neither answered, rather than returning nothing.
     *
     * An empty list from this function is not "we could not look", it
     * is "we looked and the sky is empty" — the poll loop takes any
     * value as an answer, marks the layer loaded, clears its trouble
     * flag, and the pill then reads a confident zero. OpenSky spends
     * an anonymous caller's allowance inside half an hour and both
     * aggregators can be down at once, so this is a state the app
     * reaches on an ordinary afternoon, and every other feed here
     * throws rather than assert an absence it has not verified.
     */
    private suspend fun merged(focus: Pair<Double, Double>?, wide: Boolean): List<Aircraft> {
        val byHex = LinkedHashMap<String, Aircraft>()
        var answered = false
        // OpenSky covers the whole planet in one request, which is both
        // why it is worth asking first and why it has to be asked
        // carefully. Zoomed in it answers for a box round the viewport;
        // zoomed out it answers for the Earth, at most every few minutes.
        val sky = runCatching { openSky(focus, wide) }.getOrNull()
        if (sky != null) answered = true
        sky?.forEach { byHex[it.icao] = it }

        // The aggregators are asked for one circle, not for a sweep.
        //
        // This used to fan out across a dozen or more fixed hub points
        // every fifteen seconds. Each answer is around half a megabyte,
        // so the aircraft layer by itself was pulling something close to
        // a gigabyte an hour of someone's mobile data — for a globe on
        // which most of those hubs were not even on the lit side.
        //
        // Zoomed in, one circle centred on what is actually on screen is
        // both cheaper and better aimed than the nearest six hubs.
        // Zoomed out, there is nothing a scatter of circles can tell you
        // that the whole-planet answer has not already, so the sweep
        // only runs at all if that answer did not arrive.
        val local = if (wide) {
            // Only when OpenSky did not answer at all. An answer that
            // happens to contain nothing is still an answer, and
            // spending six more requests to disagree with it is how
            // the sweep got expensive in the first place.
            if (sky == null) runCatching { adsbSample() }.getOrNull() else null
        } else {
            runCatching { adsbNear(focus) }.getOrNull()
        }
        if (local != null) answered = true
        local?.forEach { byHex.putIfAbsent(it.icao, it) }
        if (!answered) {
            throw IOException("no aircraft source answered")
        }
        return byHex.values.toList()
    }

    /**
     * One circle, centred on the viewport.
     *
     * The radius is smaller than the old sweep's because it no longer
     * has to paper over the gaps between fixed points: at the zoom
     * where this path runs, 150 nautical miles already overfills the
     * screen.
     */
    private fun adsbNear(focus: Pair<Double, Double>?): List<Aircraft>? {
        val (lat, lon) = focus ?: return null
        val out = LinkedHashMap<String, Aircraft>()
        var answered = false
        for (provider in PROVIDERS) {
            if (runCatching { sweepOne(provider, lat, lon, out, NEAR_NM) }.isSuccess) {
                answered = true
            }
            // The two aggregators carry much the same traffic, so the
            // second is a fallback for the first having failed rather
            // than a second helping of the same data.
            if (out.isNotEmpty()) break
        }
        return if (answered) out.values.toList() else null
    }

    /**
     * A handful of hub circles, as a fallback for a whole-planet answer
     * that did not come.
     *
     * OpenSky rate-limits anonymous callers and does go down, and a
     * world globe with no aircraft on it at all is worse than a world
     * globe with the busiest half-dozen regions on it. [SAMPLE_HUBS] is
     * small on purpose: this is a stopgap, not a sweep, and the sweep it
     * replaced was the most expensive thing in the app.
     */
    private suspend fun adsbSample(): List<Aircraft>? = coroutineScope {
        val out = java.util.concurrent.ConcurrentHashMap<String, Aircraft>()
        val gate = Semaphore(3)
        val step = (HUBS.size / SAMPLE_HUBS).coerceAtLeast(1)
        HUBS.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE_HUBS)
            .mapIndexed { index, hub ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        // One provider per hub, alternating, so the two
                        // are both exercised without doubling the traffic.
                        val provider = PROVIDERS[index % PROVIDERS.size]
                        runCatching { sweepOne(provider, hub.first, hub.second, out, SAMPLE_NM) }
                    }
                }
            }.map { it.await() }
            .let { results ->
                if (results.any { it.isSuccess }) out.values.toList() else null
            }
    }

    private fun sweepOne(
        provider: String,
        lat: Double,
        lon: Double,
        into: MutableMap<String, Aircraft>,
        radiusNm: Int
    ) {
        val request = Request.Builder()
            .url("$provider$lat/lon/$lon/dist/$radiusNm")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            // Thrown rather than returned. The caller counts a quiet
            // return as "this provider answered and the sky was clear",
            // which is what lets the layer say there are no aircraft —
            // and a 429 is not that.
            if (!resp.isSuccessful) throw IOException("$provider HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("$provider returned nothing")
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

    /**
     * OpenSky, asked for as little as will do.
     *
     * The whole-planet response is one and three quarter megabytes, and
     * the layer refreshes every fifteen seconds. Asked for unthinkingly
     * that is four hundred megabytes an hour of someone's mobile data,
     * and it burns an anonymous caller's daily allowance inside half an
     * hour — after which the layer is empty for the rest of the day.
     *
     * So it is asked two different ways. Zoomed in there is a bounding
     * box around what is on screen, which is a percent or two of the
     * planet and costs a quarter as much against the allowance. Zoomed
     * out there is no honest way to narrow it, so the whole planet is
     * fetched — but at most once every few minutes, with the last answer
     * standing in between. Aircraft move perceptibly in five minutes;
     * they do not move so far that a globe showing the whole Earth is
     * wrong about where the traffic is.
     */
    private fun openSky(focus: Pair<Double, Double>?, wide: Boolean): List<Aircraft>? {
        val box = if (!wide && focus != null) boxAround(focus) else null
        if (box != null) return openSkyRequest(box)
        // One whole-planet fetch at a time.
        //
        // Reading the cache, finding it stale and then fetching is
        // three steps, and @Volatile makes each of them visible
        // without making the three of them one. Two callers that miss
        // together both pull the 1.75 MB the cache exists to avoid —
        // and they do meet: this source is a process singleton, the
        // map polls it on a timer, and opening the diagnostics screen
        // fires every probe at once, one of which is this with no
        // viewport at all. The second caller waits and then takes the
        // answer the first one just put there.
        synchronized(worldLock) {
            val held = world
            if (held != null && System.currentTimeMillis() - held.first < WORLD_TTL_MS) {
                return held.second
            }
            val fetched = openSkyRequest(null)
            if (fetched != null) world = System.currentTimeMillis() to fetched
            return fetched
        }
    }

    private val worldLock = Any()

    /**
     * A window around the viewport centre, generous on purpose.
     *
     * [wide] is the only thing this layer is told about zoom, so the box
     * cannot be the viewport exactly. Erring large means aircraft are
     * already loaded when the map is panned a little rather than popping
     * in afterwards, and a box this size is still a fiftieth of the
     * planet.
     */
    private fun boxAround(focus: Pair<Double, Double>): String {
        val (lat, lon) = focus
        val laMin = (lat - BOX_LAT).coerceAtLeast(-90.0)
        val laMax = (lat + BOX_LAT).coerceAtMost(90.0)
        // No wrapping at the date line: OpenSky rejects a box whose west
        // edge is east of its east edge, and a request that spans it is
        // better served by clamping than by two requests.
        val loMin = (lon - BOX_LON).coerceAtLeast(-180.0)
        val loMax = (lon + BOX_LON).coerceAtMost(180.0)
        return "?lamin=$laMin&lomin=$loMin&lamax=$laMax&lomax=$loMax"
    }

    @Volatile private var world: Pair<Long, List<Aircraft>>? = null

    private fun openSkyRequest(box: String?): List<Aircraft>? = try {
        val request = Request.Builder()
            .url("https://opensky-network.org/api/states/all${box.orEmpty()}")
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
                // An empty list is an answer. Collapsing it to null
                // here made "the box is over the Pacific at 3am" and
                // "we were rate limited" the same thing to every
                // caller above — including the cache, which then
                // never held a valid quiet answer and re-asked for
                // the whole planet every time.
                out
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** How long a whole-planet answer stands in for the next one. */
        const val WORLD_TTL_MS = 5 * 60_000L

        /** Half-height and half-width of the zoomed-in request, degrees. */
        const val BOX_LAT = 12.0
        const val BOX_LON = 18.0

        /** Radius of the single zoomed-in circle, nautical miles. At the
         *  zoom where that path runs this already overfills the screen. */
        const val NEAR_NM = 150

        /** And of a fallback hub circle, which is covering a continent
         *  rather than a viewport. */
        const val SAMPLE_NM = 250

        /** How many hub circles the fallback is allowed. */
        const val SAMPLE_HUBS = 6

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
