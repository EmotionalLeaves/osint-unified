package com.osint.unified.data

import android.content.Context
import com.osint.unified.domain.executor.MediaWiki
import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Armed conflicts with deaths recorded this year.
 *
 * This layer used to ask Wikidata for conflicts with no recorded end date
 * and call them current. "Nobody filled in an end" is not "still going
 * on": it put the Second World War on a live map, and after a twenty-year
 * window was added to stop that, it showed a 2017 skirmish beside this
 * year's wars and ordered six hundred results by nothing at all. Wikidata
 * has no "active" signal; narrowing the window was never going to make one.
 *
 * Wikipedia's list of ongoing armed conflicts does: each row is a conflict
 * with the deaths recorded this year and last, kept against ACLED and UCDP
 * by its editors. So the label says what is known about *now* — "8,348
 * killed in 2026" — and not when a conflict's lineage began, which for
 * half of them is the 1940s and would reproduce the old complaint exactly.
 *
 * Placed at a country's centroid, and marked approximate, because a
 * country is all the list gives. A distance to the middle of Sudan is not
 * a distance to the fighting, and the area report should not offer one.
 */
class ConflictDataSource(
    context: Context,
    private val client: OkHttpClient = Http.feedClient
) : PartlyAnswered {

    private val appContext = context.applicationContext

    @Volatile
    override var lastGaps: String? = null
        private set

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(URL)
                .header("User-Agent", "OSINT-Unified/1.0 (https://github.com/EmotionalLeaves/osint-unified)")
                .build()
            val raw = client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("Wikipedia HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("Wikipedia returned nothing")
                body
            }
            val parse = MediaWiki.body(raw, "Wikipedia")["parse"] as? JsonObject
                ?: throw IOException("Wikipedia answered without the page")
            // formatversion=2 gives the HTML as a string; the old format
            // nests it under "*". Either is the page.
            val html = (parse["text"] as? JsonPrimitive)?.contentOrNull
                ?: ((parse["text"] as? JsonObject)?.get("*") as? JsonPrimitive)?.contentOrNull
                ?: throw IOException("Wikipedia answered without the page's text")

            val parsed = OngoingConflicts.parse(html, currentYear())
            // Rows, or the page changed shape. An empty list here would be
            // the claim that no war anywhere killed anyone this year.
            if (parsed.rows.isEmpty()) {
                throw IOException(
                    "the list of ongoing conflicts came back with no rows this app " +
                        "could read — the page may have changed shape"
                )
            }

            // Natural Earth's centroids, the same ones the outage layer
            // uses: the largest landmass of each country, not the mean of
            // every polygon. Empty in a build that could not bundle them,
            // which is said rather than drawn as no conflicts at all.
            val table = CountryCentroids.load(appContext)
            if (table.isEmpty()) {
                throw IOException("this build has no country positions to place conflicts with")
            }
            fun place(country: String): CountryCentroids.Place? =
                OngoingConflicts.isoFor(country)?.let { CountryCentroids.find(table, it) }
                    ?: CountryCentroids.find(table, country)

            val perCountry = HashMap<String, Int>()
            var unplaced = 0
            val blips = parsed.rows
                .sortedByDescending { it.thisYear?.atLeast ?: it.lastYear?.atLeast ?: 0 }
                .mapNotNull { row ->
                    // The first country the list names that can be placed —
                    // the list leads with where the fighting is.
                    val home = row.countries.firstNotNullOfOrNull { c -> place(c)?.let { c to it } }
                    if (home == null) {
                        unplaced++
                        return@mapNotNull null
                    }
                    val centre = home.second.lat to home.second.lon
                    // Several conflicts share a country — India and
                    // Pakistan carry four between them — and markers at one
                    // point stack into one tap target. Spread around the
                    // centroid instead; the position is approximate anyway.
                    val k = perCountry.merge(home.first, 1, Int::plus)!! - 1
                    val (lat, lon) = if (k == 0) centre else {
                        val angle = k * 2.399963
                        val r = SPREAD_DEGREES * kotlin.math.sqrt(k.toDouble())
                        (centre.first + r * sin(angle)) to (centre.second + r * cos(angle))
                    }
                    GeoBlip(
                        lat = lat.coerceIn(-85.0, 85.0),
                        lon = lon,
                        label = label(row),
                        url = row.article,
                        approximate = true
                    )
                }

            lastGaps = buildList {
                if (parsed.skipped > 0) add("${parsed.skipped} rows were not in a shape this app reads")
                if (unplaced > 0) add("$unplaced conflicts named no country this app can place")
            }.takeIf { it.isNotEmpty() }?.joinToString("; ")
            blips
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastGaps = null
            throw IOException("the list of ongoing conflicts was unavailable: ${e.message}", e)
        }
    }

    private fun label(row: OngoingConflicts.Row): String = buildString {
        append(row.name)
        row.phase?.let { append(" (").append(it).append(')') }
        append(" · ")
        val deaths = row.thisYear ?: row.lastYear
        if (deaths == null) {
            append("deaths this year not stated")
        } else {
            if (!deaths.exact) append("at least ")
            append(NumberFormat.getIntegerInstance(Locale.US).format(deaths.atLeast))
            append(" killed in ").append(deaths.year)
        }
        if (row.countries.isNotEmpty()) {
            append(" · ").append(row.countries.take(3).joinToString(", "))
            if (row.countries.size > 3) append(" +").append(row.countries.size - 3)
        }
    }

    private companion object {
        const val URL = "https://en.wikipedia.org/w/api.php?action=parse" +
            "&page=List_of_ongoing_armed_conflicts&prop=text&format=json" +
            "&formatversion=2&redirects=1"

        /** How far apart conflicts in one country are drawn, in degrees. */
        const val SPREAD_DEGREES = 0.9

        /** Read per call, so a process alive across New Year is not a year behind. */
        fun currentYear(): Int =
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                .get(java.util.Calendar.YEAR)
    }
}
