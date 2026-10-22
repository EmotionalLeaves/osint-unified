package com.osint.unified.data

import android.content.Context
import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Ground stops and delay programmes in the US national airspace system.
 *
 * This is the layer that explains the others. When a hundred aircraft are
 * holding over one state, the reason is a ground stop at the airport they
 * are all trying to reach, and that fact lives in one small FAA feed and
 * nowhere on the map. It is also the fastest public signal that something
 * has happened at an airport, often ahead of any news.
 *
 * The FAA names airports by code and gives no coordinates, so each
 * programme is joined to the airport dataset already bundled in the APK.
 * A code that matches nothing is dropped rather than plotted at (0, 0).
 */
class AirportStatusDataSource(
    private val appContext: Context,
    private val airports: BundledPointsDataSource,
    private val client: OkHttpClient = Http.client
) {

    private val delayTypeRegex = Regex("<Delay_type>(.*?)</Delay_type>", RegexOption.DOT_MATCHES_ALL)
    // The FAA names the element differently in each section, and the
    // closure section holds <Airport> elements inside an
    // <Airport_Closure_List> wrapper — not <Airport_Closure> elements. A
    // regex that missed that would silently never show a closed airport,
    // which is the most serious thing this layer exists to report.
    private val programRegex = Regex(
        "<(Program|Ground_Delay|Delay|Airport_Closure|Airport)>(.*?)</\\1>",
        RegexOption.DOT_MATCHES_ALL
    )

    /** code -> (lat, lon, name), from the bundled airport points. */
    @Volatile private var index: Map<String, Triple<Double, Double, String>>? = null

    private suspend fun airportIndex(): Map<String, Triple<Double, Double, String>> {
        index?.let { return it }
        val built = HashMap<String, Triple<Double, Double, String>>()
        airports.fetch().forEach { blip ->
            // The bundled label is "CODE · Name", but falls back to the bare
            // name when the dataset has no code. Without requiring the
            // separator, a short name like "Hilo" was indexed as if it were
            // an airport code, so a real FAA programme for that code would
            // be plotted at an unrelated airfield.
            if (!blip.label.contains(" · ")) return@forEach
            val code = blip.label.substringBefore(" · ").trim().uppercase()
            if (code.length in 3..4) {
                built.putIfAbsent(code, Triple(blip.lat, blip.lon, blip.label))
            }
        }
        // Only cache an index that has something in it. Caching an empty one
        // after a failed asset read made every later poll drop every
        // programme, for the life of the process, in silence.
        if (built.isNotEmpty()) index = built
        return built
    }

    /** Throws rather than returning an empty list when the feed or the
     *  airport index could not be read — an empty list is a statement that
     *  nothing is delayed anywhere, which the world brief repeats. */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://nasstatus.faa.gov/api/airport-status-information")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("FAA HTTP ${resp.code}")
                resp.body?.string()
            }
            if (body.isNullOrBlank()) throw IOException("FAA returned an empty body")

            // No airports means no join is possible; saying "no ground
            // stops" would be a claim this cannot support.
            val coordsOrNone = airportIndex()
            if (coordsOrNone.isEmpty()) throw IOException("airport coordinates unavailable")

            val coords = coordsOrNone
            val out = LinkedHashMap<String, GeoBlip>()

            delayTypeRegex.findAll(body).forEach { typeMatch ->
                val block = typeMatch.groupValues[1]
                val kind = tag(block, "Name")?.let(::clean) ?: "delay"
                programRegex.findAll(block).forEach { pm ->
                    val p = pm.groupValues[2]
                    val code = tag(p, "ARPT")?.let(::clean)?.uppercase() ?: return@forEach
                    val where = coords[code] ?: return@forEach
                    val reason = tag(p, "Reason")?.let(::clean)
                    val avg = tag(p, "Avg")?.let(::clean)
                    val max = tag(p, "Max")?.let(::clean)
                    val min = tag(p, "Min")?.let(::clean)
                    val end = tag(p, "End_Time")?.let(::clean)

                    val label = buildString {
                        append("$code ")
                        append(shortKind(kind))
                        if (!reason.isNullOrBlank()) append(" · $reason")
                        when {
                            avg != null -> append(" · avg $avg")
                            min != null -> append(" · $min")
                        }
                        if (max != null) append(" · up to $max")
                        if (end != null) append(" · until $end")
                        append(" · ${where.third.substringAfter("· ").take(40)}")
                    }
                    // One airport can appear under several programmes; the
                    // most serious one is the one worth a marker, and the
                    // feed lists ground stops first.
                    out.putIfAbsent(code, GeoBlip(where.first, where.second, label, FAA_STATUS_URL))
                }
            }
            out.values.toList()
    }

    /** The FAA's section names, shortened to fit a marker label. */
    private fun shortKind(name: String): String = when {
        name.contains("Ground Stop", true) -> "GROUND STOP"
        name.contains("Ground Delay", true) -> "ground delay programme"
        name.contains("Closure", true) -> "CLOSED"
        name.contains("Arrival/Departure", true) -> "delays"
        else -> name.lowercase()
    }

    private fun tag(block: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun clean(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val FAA_STATUS_URL = "https://nasstatus.faa.gov/"
    }
}
