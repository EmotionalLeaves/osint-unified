package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Armed conflicts under way now, geocoded via Wikidata SPARQL (keyless).
 *
 * "No recorded end date" is not the same as "still going on". Wikidata has
 * thousands of battles and sieges whose end nobody ever filled in, so a
 * filter on `!BOUND(?end)` alone put the Second World War on a live map —
 * which is worse than showing nothing, because every other marker beside it
 * is real and current.
 *
 * So a start date is now required, and it has to be recent. The query asks
 * for it, and the result is checked again here: a source that changes its
 * mind should not be able to put 1939 back on the globe.
 */
class ConflictDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pointRegex = Regex("""Point\(([-\d.]+)\s+([-\d.]+)\)""")

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val earliest = currentYear() - MAX_AGE_YEARS
            val query = """
                SELECT ?cLabel ?coord ?start WHERE {
                  ?c wdt:P31/wdt:P279* wd:Q350604 .
                  ?c wdt:P625 ?coord .
                  ?c wdt:P580 ?start .
                  OPTIONAL { ?c wdt:P582 ?end . }
                  FILTER(!BOUND(?end))
                  FILTER(YEAR(?start) >= $earliest)
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                }
                ORDER BY DESC(?start)
                LIMIT 600
            """.trimIndent()
            val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OSINT-Unified/1.0 (https://github.com/EmotionalLeaves/osint-unified)")
                .header("Accept", "application/sparql-results+json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("Wikidata HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("Wikidata returned nothing")
                val bindings = json.parseToJsonElement(body)
                    .jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
                    ?: throw IOException("Wikidata answered in an unexpected shape")
                bindings.mapNotNull { b ->
                    val o = b.jsonObject
                    val coord = ((o["coord"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val m = pointRegex.find(coord) ?: return@mapNotNull null
                    val lon = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lat = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                    val label = ((o["cLabel"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: "conflict"
                    val started = ((o["start"] as? JsonObject)?.get("value") as? JsonPrimitive)
                        ?.contentOrNull?.take(4)?.toIntOrNull() ?: return@mapNotNull null
                    // Enforced here as well as in the query: an undated or
                    // long-finished conflict must never reach the globe.
                    if (started < currentYear() - MAX_AGE_YEARS) return@mapNotNull null
                    // "since 2017" is a claim this source cannot
                    // support. All Wikidata says is when it started
                    // and that nobody has filled in an end — which
                    // is not the same as it still going on, and is
                    // how this layer once put the Second World War
                    // on a live map. Reported this time as a
                    // conflict from 2017 beside current ones. The
                    // label says exactly what is known.
                    val full = "$label · began $started, no recorded end"
                    GeoBlip(lat, lon, full, "https://www.wikidata.org/w/index.php?search=" + URLEncoder.encode(label, "UTF-8"))
                }
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("Wikidata unavailable: ${e.message}", e)
        }
    }

    private companion object {
        /**
         * How far back a conflict may have started and still be
         * worth drawing.
         *
         * Twenty is long enough for the wars that really are
         * running — Yemen from 2014, Ukraine from 2022 — and that
         * is the whole of its justification. It is not a claim that
         * everything inside it is current, because this source
         * cannot support that claim about anything: the query asks
         * for conflicts with no recorded end date, and Wikidata is
         * full of battles whose end nobody ever filled in. The
         * ORDER BY above and the wording below are what keep that
         * honest.
         */
        const val MAX_AGE_YEARS = 20

        /**
         * Read per call, not once per process.
         *
         * As a companion `val` this was fixed at whatever year the
         * app first started in. A phone left running across New
         * Year — or a process that outlives one, which on Android
         * is normal — would have gone on using the old year, and
         * the window would have silently slipped to twenty-one.
         */
        fun currentYear(): Int =
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                .get(java.util.Calendar.YEAR)
    }
}
