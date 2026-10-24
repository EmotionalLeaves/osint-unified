package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.Vessel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 * Maritime vessel positions, from as many open feeds as there are.
 *
 * There is no network publishing live AIS worldwide without an
 * account, so this is built out of national authorities publishing
 * their own waters and adding them up. The setting lists feed URLs,
 * one per line; each is fetched and merged, and a vessel reported by
 * two feeds is kept once.
 *
 * They do not agree on shape. Fintraffic sends a point per vessel with
 * `sog` and `cog`; the Norwegian Coastal Administration sends the last
 * few minutes of each vessel's track as a LineString with `ship_name`
 * and `speed`. So the reader takes the last coordinate of whatever
 * geometry it is given — the end of a track is where the ship is now —
 * and looks for each field under every name anyone uses for it.
 *
 * They also do not agree on size, and that matters more than it
 * sounds. The Norwegian feed is four and a half megabytes of JSON, and
 * this layer polls every thirty seconds — which on somebody's phone,
 * on mobile data, is most of a gigabyte an hour for a feed covering
 * one coastline. So two things govern how often a feed is actually
 * asked: how big its last answer was, and whether the view is
 * anywhere near the water it covers.
 *
 * Neither is configured. The size is measured, and the region is
 * learned from the vessels themselves, so a feed somebody pastes in
 * tomorrow is throttled correctly without anybody describing it.
 */
class AisDataSource(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.feedClient
) : PartlyAnswered {

    @Volatile override var lastGaps: String? = null
        private set

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What one feed last gave, how big it was, and where it covers. */
    private data class Held(
        val vessels: List<Vessel>,
        val bytes: Int,
        val at: Long,
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double
    ) {
        /**
         * How long before this feed may be asked again.
         *
         * A millisecond per twenty bytes, floored at half a minute and
         * capped at five. It works out at roughly ten megabytes an
         * hour per feed whatever its size, which is the point: a small
         * feed is asked as often as ships move, a five-megabyte one
         * every four minutes, and neither quietly spends somebody's
         * month on a coastline they are not looking at.
         */
        val interval: Long get() = (bytes / 20L).coerceIn(30_000L, 300_000L)

        /** True when the camera is somewhere this feed knows about. */
        fun covers(lat: Double, lon: Double, marginDegrees: Double): Boolean {
            if (vessels.isEmpty()) return true
            return lat >= south - marginDegrees && lat <= north + marginDegrees &&
                lon >= west - marginDegrees && lon <= east + marginDegrees
        }
    }

    private val held = HashMap<String, Held>()

    /**
     * Every vessel the open feeds know about.
     *
     * [focus] is where the globe is looking, when it is looking
     * anywhere in particular. A regional feed whose water is nowhere
     * near it is not asked again — its last answer is still returned,
     * so turning back is instant, but no bytes are spent on a sea
     * nobody is looking at.
     */
    suspend fun fetch(focus: Pair<Double, Double>? = null): List<Vessel> =
        withContext(Dispatchers.IO) {
            val urls = settings.aisUrl.split(',', '\n').map { it.trim() }
                .filter { it.isNotBlank() }
            // "Nothing is configured" is not "there are no ships".
            // An empty list here is read as the second, and the
            // default is never empty, so reaching this means the
            // person cleared the field.
            if (urls.isEmpty()) {
                throw IOException(
                    "no AIS feed is configured — add one in LIVE FEEDS, or " +
                        "restore the default"
                )
            }
            val now = System.currentTimeMillis()
            val merged = LinkedHashMap<String, Vessel>()
            // Whether any feed told us anything at all — freshly or
            // from what it last said. Without this, every feed being
            // down returned an empty list and reported success, and an
            // empty vessel layer is read as "no ships", not as "no
            // answer".
            var answered = 0
            for (url in urls) {
                if (merged.size >= 20000) break
                val last = synchronized(held) { held[url] }
                val due = last == null || now - last.at >= last.interval
                val near = last == null || focus == null ||
                    last.covers(focus.first, focus.second, OFF_SCREEN_MARGIN)
                val vessels = if (due && near) {
                    runCatching { fetchOne(url) }.getOrNull()?.also { fresh ->
                        synchronized(held) { held[url] = describe(fresh, now) }
                    } ?: last?.vessels
                } else {
                    last?.vessels
                }
                if (vessels != null) answered++
                vessels?.forEach { v ->
                    merged.putIfAbsent(v.mmsi.ifBlank { "${v.lat},${v.lon}" }, v)
                }
            }
            if (answered == 0) {
                throw IOException(
                    "no AIS feed answered and none had anything held from before"
                )
            }
            // One national authority of several is a real picture of
            // one sea and an empty one everywhere else, and the pill
            // could not say which it was holding.
            lastGaps =
                if (answered >= urls.size) null
                else "${urls.size - answered} of ${urls.size} AIS feeds did not answer"
            merged.values.toList()
        }

    /**
     * What a feed turned out to be: its size and the box it covers.
     *
     * Learned rather than configured, so a feed somebody pastes in
     * tomorrow is throttled and skipped correctly without anybody
     * having to describe it.
     */
    private fun describe(vessels: List<Vessel>, now: Long): Held {
        if (vessels.isEmpty()) return Held(vessels, 0, now, 90.0, -90.0, -180.0, 180.0)
        var north = -90.0
        var south = 90.0
        var west = 180.0
        var east = -180.0
        vessels.forEach {
            if (it.lat > north) north = it.lat
            if (it.lat < south) south = it.lat
            if (it.lon < west) west = it.lon
            if (it.lon > east) east = it.lon
        }
        return Held(vessels, lastBytes, now, north, south, west, east)
    }

    @Volatile private var lastBytes: Int = 0

    /**
     * Names for the Finnish feed, which does not carry them.
     *
     * Digitraffic splits AIS in two: positions on one endpoint and the
     * vessel register on another, joined by MMSI. Without the second
     * every ship in the Baltic is a nine-digit number, which is a
     * worse answer than the Norwegian feed gives for free.
     *
     * Refreshed rarely. A ship's name is not news.
     */
    private val names = HashMap<String, String>()
    @Volatile private var namesAt = 0L

    private fun namesFor(url: String): Map<String, String> {
        if (!url.contains("digitraffic.fi") || !url.contains("/locations")) return emptyMap()
        val now = System.currentTimeMillis()
        synchronized(names) {
            if (names.isNotEmpty() && now - namesAt < NAMES_TTL_MS) return HashMap(names)
        }
        val register = url.replace("/locations", "/vessels")
        val parsed = runCatching {
            val request = Request.Builder().url(register)
                .header("User-Agent", "OSINT-Unified/1.0")
                .header("Digitraffic-User", DIGITRAFFIC_USER)
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@runCatching emptyMap()
                json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val mmsi = (o["mmsi"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    mmsi to name
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        if (parsed.isNotEmpty()) {
            synchronized(names) {
                names.clear()
                names.putAll(parsed)
                namesAt = now
            }
        }
        return parsed
    }

    private fun fetchOne(url: String): List<Vessel> {
        val register = namesFor(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OSINT-Unified/1.0")
            // Digitraffic refuses a request that does not say who is
            // making it, and says so with a 406 that mentions gzip —
            // which sends you looking in the wrong place entirely.
            .header("Digitraffic-User", DIGITRAFFIC_USER)
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            // Thrown, not returned. The caller is
            // `runCatching { fetchOne(url) }.getOrNull()`, which
            // reads an empty list as "that feed answered and there
            // are no ships in its waters" — so a 429 from Fintraffic
            // emptied the Baltic and counted itself as having
            // answered.
            if (!resp.isSuccessful) throw IOException("AIS feed HTTP ${resp.code}: $url")
            if (body.isNullOrBlank()) throw IOException("AIS feed returned nothing: $url")
            // Measured after decompression, which is the honest number
            // for "how heavy is this feed" even though the wire sees
            // roughly a seventh of it: the ratio is the server's
            // business and the order of magnitude is what matters.
            lastBytes = body.length
            val root = json.parseToJsonElement(body)
            val features = (root as? JsonObject)?.get("features")?.jsonArray
            if (features != null) {
                features.mapNotNull { f ->
                    val fo = f.jsonObject
                    val geometry = fo["geometry"] as? JsonObject ?: return@mapNotNull null
                    val (lat, lon) = position(geometry) ?: return@mapNotNull null
                    val props = fo["properties"] as? JsonObject
                    fun p(vararg keys: String): JsonPrimitive? {
                        keys.forEach { k -> (props?.get(k) as? JsonPrimitive)?.let { return it } }
                        return null
                    }
                    val mmsi = p("mmsi")?.contentOrNull
                        ?: (fo["mmsi"] as? JsonPrimitive)?.contentOrNull ?: ""
                    Vessel(
                        mmsi = mmsi,
                        name = p("name", "ship_name", "shipname", "vesselName")
                            ?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                            ?: register[mmsi].orEmpty(),
                        lat = lat, lon = lon,
                        course = p("cog", "course", "courseOverGround")?.doubleOrNull ?: 0.0,
                        speed = p("sog", "speed", "speedOverGround")?.doubleOrNull ?: 0.0
                    )
                }.take(20000)
            } else {
                json.decodeFromString<List<AisVessel>>(body)
                    .filter { it.lat != null && it.lon != null }
                    .map {
                        Vessel(
                            mmsi = it.mmsi ?: "",
                            name = it.name?.trim().orEmpty(),
                            lat = it.lat!!, lon = it.lon!!,
                            course = it.course ?: 0.0, speed = it.speed ?: 0.0
                        )
                    }
                    .take(20000)
            }
        }
    }


    /**
     * Where the vessel is, from whatever geometry the feed sent.
     *
     * A Point is the position. A LineString or MultiPoint is the track
     * it has just travelled, and the last coordinate of that is where
     * it is now — taking the first would plot every ship where it was
     * several minutes ago, which on a map of moving things is a
     * mistake nobody would ever see.
     */
    internal fun position(geometry: JsonObject): Pair<Double, Double>? {
        val coordinates = geometry["coordinates"]?.jsonArray ?: return null
        val pair = when (geometry["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "Point" -> coordinates
            "LineString", "MultiPoint" ->
                coordinates.lastOrNull()?.let { it as? kotlinx.serialization.json.JsonArray }
            // An unfamiliar geometry is not guessed at. A polygon's
            // first vertex is a corner of something, not a ship.
            else -> if (coordinates.firstOrNull() is JsonPrimitive) coordinates else null
        } ?: return null
        val lon = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    private companion object {
        /** How far outside a feed's own water the camera may be before
         *  that feed stops being asked. Generous, because a feed's box
         *  is the box of the ships that happened to be reporting. */
        const val OFF_SCREEN_MARGIN = 12.0

        /** Digitraffic asks every caller to identify itself, and
         *  refuses the ones that do not. */
        const val DIGITRAFFIC_USER = "EmotionalLeaves/osint-unified"

        /** A ship's name is not news. */
        const val NAMES_TTL_MS = 6 * 3600_000L
    }

    @Serializable
    private data class AisVessel(
        val mmsi: String? = null,
        val name: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val course: Double? = null,
        val speed: Double? = null
    )
}
