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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Every official severe-weather warning on Earth.
 *
 * The weather layer was the United States and nothing else, not because
 * anyone decided that but because api.weather.gov was what could be
 * reached without a key. A world map that reports storms in Kansas and
 * says nothing about a typhoon making landfall in Luzon is not a world
 * map.
 *
 * The WMO's Severe Weather Information Centre is the actual answer: the
 * warnings its member states issue themselves, in their own authority,
 * aggregated in one keyless file. It is where the WMO's own map gets its
 * data.
 *
 * What it does not carry is geometry. Each warning names its area in
 * words — "Ryazan Region", "Wise; Denton; Collin" — and the coordinates
 * in the file belong to the issuing meteorological service, not to the
 * weather. So this plots one marker per member rather than scattering
 * invented positions across a country: the position is real and is the
 * service's, the warnings are counted and named on it, and the marker is
 * flagged approximate so nothing in the app measures a distance to it.
 * The United States keeps its own layer, where the polygons are real.
 */
class WorldWarningsDataSource(
    private val store: DiskCache? = null,
    private val client: OkHttpClient = Http.bulkClient
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val members = load(MEMBERS_URL, MEMBERS_KEY, MEMBERS_TTL_MS)
            ?: throw IOException("WMO member list unavailable")
        val warnings = load(WARNINGS_URL, WARNINGS_KEY, WARNINGS_TTL_MS)
            ?: throw IOException("WMO severe weather unavailable")
        parse(warnings, members, System.currentTimeMillis())
    }

    private fun load(url: String, key: String, ttl: Long): String? {
        store?.read(key, ttl)?.let { return it }
        val fetched = runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
        if (!fetched.isNullOrBlank() && fetched.trimStart().firstOrNull() in setOf('{', '[')) {
            store?.write(key, fetched)
            return fetched
        }
        // Yesterday's warnings are wrong in detail and right about
        // where the weather is; a blank layer is wrong about
        // everything. But only up to a point: a fetch that fails and
        // falls back here is recorded by the caller as a success, with
        // the pill green and the time stamped as just now, so the
        // older the copy the larger the lie. Past the limit the
        // failure is allowed to be a failure and the layer says it is
        // not reporting.
        val age = store?.ageMillis(key) ?: return null
        if (age > STALE_LIMIT_MS) return null
        return store.readStale(key)
    }

    /**
     * Who issues warnings, and from where.
     *
     * The file is grouped by regional association, and each member
     * carries the coordinates of its meteorological service.
     */
    internal fun members(body: String): Map<String, Member> {
        val out = HashMap<String, Member>()
        val root = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return out
        root.forEach { group ->
            val list = (group as? JsonObject)?.get("members")?.jsonArray ?: return@forEach
            list.forEach { entry ->
                val o = entry as? JsonObject ?: return@forEach
                val mid = (o["mid"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                val lon = (o["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
                out[mid] = Member(
                    name = name,
                    lat = lat,
                    lon = lon,
                    service = (o["dept"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                )
            }
        }
        return out
    }

    data class Member(
        val name: String,
        val lat: Double,
        val lon: Double,
        val service: String = ""
    )

    /**
     * Warnings into one marker per issuing member.
     *
     * Expired warnings are dropped rather than shown greyed: the file
     * keeps them for a while after they lapse, and a map that still
     * says "Blizzard Warning" over Alaska two days later is lying in
     * the most ordinary way a live map can.
     */
    internal fun parse(
        warningsBody: String,
        membersBody: String,
        nowMs: Long
    ): List<GeoBlip> {
        // A body that fetched and then did not parse is not "no
        // severe-weather warning anywhere in the world", which is
        // what an empty list from here is read out loud as.
        // VolcanoDataSource gets this right for the same situation
        // and says "answered in an unexpected shape".
        val members = members(membersBody)
        if (members.isEmpty()) {
            throw IOException("the WMO member list answered in an unexpected shape")
        }
        val items = runCatching {
            json.parseToJsonElement(warningsBody).jsonObject["items"]?.jsonArray
        }.getOrNull() ?: throw IOException("WMO SWIC answered in an unexpected shape")

        val byMember = LinkedHashMap<String, MutableList<Warning>>()
        items.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val mid = (o["mid"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (mid !in members) return@forEach
            val expires = instantOf((o["expires"] as? JsonPrimitive)?.contentOrNull)
            if (expires != null && expires < nowMs) return@forEach
            byMember.getOrPut(mid) { mutableListOf() }.add(
                Warning(
                    event = (o["event"] as? JsonPrimitive)?.contentOrNull
                        ?.trim().orEmpty().ifBlank { "Warning" },
                    area = (o["areaDesc"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                    severity = (o["s"] as? JsonPrimitive)?.intOrNull ?: UNKNOWN_SEVERITY
                )
            )
        }

        return byMember.map { (mid, list) ->
            val member = members.getValue(mid)
            // Named worst first: a country with one Extreme warning and
            // forty Minor ones should not read as forty Minor ones.
            val worst = list.minOf { it.severity }
            val events = list
                .groupBy { it.event }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<Warning>>> {
                        it.value.minOf { w -> w.severity }
                    }.thenByDescending { it.value.size }
                )
            val headline = events.take(3).joinToString(", ") { (event, group) ->
                if (group.size > 1) "$event ×${group.size}" else event
            }
            val label = buildString {
                append(member.name)
                append(" · ")
                append(list.size)
                append(if (list.size == 1) " warning" else " warnings")
                append(" · ")
                append(severityName(worst))
                if (headline.isNotBlank()) append(" · ").append(headline)
                if (events.size > 3) append(" +${events.size - 3} more kinds")
                // One area named, because "Severe · Ryazan Region" tells
                // you something and "Severe" does not.
                list.firstOrNull { it.area.isNotBlank() }?.let {
                    append(" · ").append(it.area.take(60))
                }
            }
            GeoBlip(
                lat = member.lat,
                lon = member.lon,
                label = label,
                url = "https://severeweather.wmo.int/",
                // The position is the meteorological service's, not the
                // weather's. Flagged so no distance is ever measured to it.
                approximate = true
            )
        }.sortedBy { it.label }
    }

    internal data class Warning(val event: String, val area: String, val severity: Int)

    /** "2026-10-25 12:00:00", which the file gives with no zone, as UTC. */
    internal fun instantOf(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(text, STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    /**
     * The CAP severity scale, which the file sends as its index.
     *
     * An unrecognised value is reported as unknown rather than guessed
     * at: a warning labelled "Minor" because a number was out of range
     * is worse than one labelled nothing.
     */
    internal fun severityName(index: Int): String = when (index) {
        0 -> "Extreme"
        1 -> "Severe"
        2 -> "Moderate"
        3 -> "Minor"
        else -> "severity not stated"
    }

    private companion object {
        /** The oldest cached copy still worth showing as though it
         *  were current. Individual warnings carry their own expiry
         *  and are dropped once past it, so this is a bound on the
         *  silence rather than on the warnings themselves. */
        const val STALE_LIMIT_MS = 6 * 3600_000L

        const val WARNINGS_URL = "https://severeweather.wmo.int/json/wmo_all.json"
        const val MEMBERS_URL = "https://severeweather.wmo.int/json/wmo_member.json"
        const val WARNINGS_KEY = "wmo-severe-weather"
        const val MEMBERS_KEY = "wmo-members"

        /** Members and their coordinates change about never. */
        const val MEMBERS_TTL_MS = 7 * 24 * 3600_000L

        /** The file is rebuilt every few minutes and is a megabyte. */
        const val WARNINGS_TTL_MS = 10 * 60_000L

        const val UNKNOWN_SEVERITY = 9

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
