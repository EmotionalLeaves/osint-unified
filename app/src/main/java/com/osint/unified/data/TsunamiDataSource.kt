package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Live tsunami bulletins from the two US warning centres.
 *
 * A tsunami is not an earthquake, and the quake layer cannot stand in for
 * it: most large undersea quakes produce no wave, and the ones that do are
 * announced by a centre that has looked at the sea level rather than the
 * seismogram. The distinction — information statement, advisory, watch,
 * warning — is the whole content of the message, so it is carried into the
 * label rather than flattened to "tsunami".
 *
 * Four sources, all keyless, all a few kilobytes.
 *
 * The two US centres — the National centre (Alaska, the US west coast,
 * the Atlantic and the Caribbean) and the Pacific centre (Hawaii and
 * the Pacific basin) — publish Atom.
 *
 * The other two exist because those centres are not the world. Japan's
 * Meteorological Agency is a tsunami warning authority in its own
 * right and issues for the whole Pacific rim, not only for Japan: the
 * bulletin sitting in its feed as this was written is a magnitude 8
 * off Central America. Indonesia's BMKG covers the single most
 * tsunami-prone coastline on Earth, and nothing else here reached it
 * at all — its quake feed is read for the one field that matters, the
 * tsunami-potential verdict, and the quakes with no such potential are
 * left to the earthquake layer where they belong.
 *
 * Four authorities is also four opinions. They are not merged: a wave
 * that two centres have both issued for appears twice, once in each
 * centre's own words, because they disagree about size and arrival
 * often enough that averaging them would be inventing a bulletin
 * nobody issued.
 */
class TsunamiDataSource(private val client: OkHttpClient = Http.feedClient) :
    PartlyAnswered {

    @Volatile override var lastGaps: String? = null
        private set


    private val feeds = listOf(
        "https://www.tsunami.gov/events/xml/PAAQAtom.xml" to "NTWC",
        "https://www.tsunami.gov/events/xml/PHEBAtom.xml" to "PTWC"
    )

    // Attributes allowed on the tag. A feed that one day emits
    // `<entry xml:base="...">` would otherwise match nothing, drop
    // silently into the whole-document path below, and collapse every
    // bulletin in the file into one marker — with no error anywhere.
    private val entryRegex = Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)

    // These feeds declare both namespaces and use whichever the bulletin
    // template felt like: georss:point carries "lat lon" in one element,
    // geo:lat / geo:long carry them separately. A parser that knows only
    // one of them silently finds no tsunamis, which is the worst possible
    // way for this layer to fail.
    private val pointRegex = Regex("<georss:point>\\s*(-?[\\d.]+)[\\s,]+(-?[\\d.]+)\\s*</georss:point>")
    private val latRegex = Regex("<geo:lat>\\s*(-?[\\d.]+)\\s*</geo:lat>")
    private val lonRegex = Regex("<geo:long>\\s*(-?[\\d.]+)\\s*</geo:long>")

    /** The bulletin's own severity word, out of the xhtml summary. */
    private val categoryRegex = Regex(
        "Category:\\s*</strong>\\s*([A-Za-z ]{3,30})",
        RegexOption.IGNORE_CASE
    )

    /**
     * Throws when neither centre could be read.
     *
     * An empty list means "no live bulletin anywhere", which the world brief
     * states as fact. One centre failing is survivable — the other still
     * answers — but both failing must not read as an all-clear.
     */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val answers = coroutineScope {
            val atom = feeds.map { (url, centre) ->
                async { runCatching { one(url, centre) } }
            }
            val others = listOf(
                async { runCatching { jma() } },
                async { runCatching { bmkg() } }
            )
            (atom + others).awaitAll()
        }
        val found = answers.mapNotNull { it.getOrNull() }.flatten()
        lastGaps = gapsFrom(
            answers.size,
            answers.filter { it.isFailure }.map { it.exceptionOrNull() },
            "tsunami centres"
        )
        // An empty list here is not "no answer", it is the assertion
        // that there is no tsunami bulletin anywhere on Earth — the
        // world brief prints exactly that sentence from it. So it may
        // only be made when every agency answered.
        //
        // "All four failed" was the old test and it stopped being
        // enough the moment there were four: both US feeds live on one
        // host and share one cooldown, so a single 429 from
        // tsunami.gov silences the Pacific and the Caribbean together
        // — and JMA and BMKG answering normally, which on a quiet day
        // means an empty list, was enough to turn that into a
        // confident all-clear for waters nothing had been read from.
        //
        // Partial data is still shown: a marker from an agency that
        // did answer is real whatever the others did. It is only the
        // absence that needs everyone.
        if (found.isEmpty() && answers.any { it.isFailure }) {
            throw IOException(
                "not every tsunami centre answered, so silence is not an all-clear: " +
                    answers.mapNotNull { it.exceptionOrNull()?.message }.joinToString("; ")
            )
        }
        found
    }

    private fun one(url: String, centre: String): List<GeoBlip> =
        atom(get(url, centre), centre)

    /** Split from the fetch so the parsing can be tested on its own —
     *  it is where this source has gone wrong before. */
    internal fun atom(body: String, centre: String): List<GeoBlip> {
            // Some bulletins carry no entries and put the whole message in
            // the feed header, which still has a title and a point. The
            // intent on that path has always been to drop the bulletin
            // rather than show an undated one as live — the only
            // <updated> in the document is the feed's own, which these
            // centres bump on every refresh and which says nothing
            // about when the bulletin was issued.
            //
            // The guard did not work. `tag` finds the first <updated>
            // anywhere in what it is given, and on this path that is
            // the whole document, so the feed-level stamp was always
            // found, `updated` was never null, the drop never happened
            // and the 48-hour test always passed. A bulletin from
            // months ago was drawn as current and labelled "4m ago" —
            // the feed's refresh time, presented as the wave's.
            //
            // So this path is read only for its coordinates and then
            // discarded. Losing a real bulletin that a centre chose to
            // publish header-only is the lesser harm: this layer exists
            // to say a wave may be coming, and one that says so about
            // last year is worse than one that says nothing.
            val entries = entryRegex.findAll(body).map { it.groupValues[1] }.toList()
            val wholeFeed = entries.isEmpty()
            val blocks = if (wholeFeed) emptyList() else entries

        return blocks.mapNotNull { block ->
                val (lat, lon) = coordsOf(block) ?: return@mapNotNull null
                val title = tag(block, "title")?.let(::clean) ?: "tsunami bulletin"
                val updated = Freshness.parse(tag(block, "updated")?.let(::clean))
                // These feeds keep the last bulletin up indefinitely, so an
                // old statement would otherwise read as a live warning.
                if (!Freshness.within(updated, 48)) return@mapNotNull null
                // "Information", "Advisory", "Watch", "Warning" — the
                // difference between these is the entire message.
                val category = categoryRegex.find(block)?.groupValues?.get(1)?.trim()
                val label = buildString {
                    append("$centre ")
                    append(category?.uppercase() ?: "BULLETIN")
                    append(": ")
                    append(title.take(130))
                    Freshness.ago(updated)?.let { append(" · $it") }
                }
                GeoBlip(lat, lon, label, tag(block, "link")?.let(::clean) ?: "https://www.tsunami.gov/")
        }
    }

    /**
     * Japan's own bulletins.
     *
     * The list is a flat JSON array, newest first, and JMA leaves old
     * bulletins in it — the oldest in the file can be a month back — so
     * the age filter is doing real work rather than guarding against a
     * stale cache.
     *
     * The severity is in the title and nowhere else. The per-region
     * `kind` entries further down each bulletin are what that one
     * stretch of coast is told to expect, which is not the same thing
     * and is a hundred entries long. The title words form a ladder:
     * major warning, warning, advisory, forecast — and "forecast" is
     * JMA's way of saying it has looked and there is nothing to worry
     * about, so it is carried through rather than dropped, labelled as
     * what it is.
     */
    internal fun jma(body: String = get(JMA_URL, "JMA")): List<GeoBlip> {
        val root = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: throw IOException("JMA sent something that is not a bulletin list")
        return root.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val (lat, lon) = iso6709(str("cod")) ?: return@mapNotNull null
            val at = Freshness.parse(str("rdt"))
            if (!Freshness.within(at, JMA_HOURS, keepUndated = false)) return@mapNotNull null
            val title = str("ttl").orEmpty()
            val label = buildString {
                append("JMA ")
                append(severityOf(title))
                append(": ")
                // The region is JMA's own name for it, in Japanese.
                // Leaving it is better than dropping it: it is the only
                // thing in the bulletin that says where, and a guessed
                // translation of a place name is worse than none.
                append(str("anm")?.takeIf { it.isNotBlank() } ?: "epicentre")
                str("mag")?.takeIf { it.isNotBlank() }?.let { append(" · M$it") }
                Freshness.ago(at)?.let { append(" · $it") }
            }
            GeoBlip(lat, lon, label, "https://www.jma.go.jp/bosai/map.html#contents=tsunami")
        }
    }

    /** The ladder of JMA titles, worst first so the longer word wins. */
    internal fun severityOf(title: String): String = when {
        title.contains("大津波警報") -> "MAJOR TSUNAMI WARNING"
        title.contains("津波警報") -> "TSUNAMI WARNING"
        title.contains("津波注意報") -> "TSUNAMI ADVISORY"
        title.contains("津波予報") -> "FORECAST (no significant wave expected)"
        else -> "BULLETIN"
    }

    /**
     * Indonesia, filtered to the one thing this layer is about.
     *
     * BMKG's feed is a list of recent earthquakes, and each carries the
     * agency's verdict on whether it can produce a wave. The ones that
     * cannot are earthquakes and are already on the earthquake layer;
     * putting them here too would turn a layer that means "a wave may
     * be coming" into a second, worse quake layer.
     */
    internal fun bmkg(body: String = get(BMKG_URL, "BMKG")): List<GeoBlip> {
        val quakes = runCatching {
            json.parseToJsonElement(body).jsonObject["Infogempa"]
                ?.jsonObject?.get("gempa")?.jsonArray
        }.getOrNull() ?: throw IOException("BMKG sent something unexpected")
        return quakes.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val verdict = str("Potensi").orEmpty()
            // "Tidak berpotensi tsunami" — no tsunami potential. Anything
            // else is the agency saying it is possible, and an empty
            // verdict is not an all-clear, so it is kept.
            if (verdict.trim().startsWith("Tidak", ignoreCase = true)) return@mapNotNull null
            val parts = str("Coordinates")?.split(",") ?: return@mapNotNull null
            if (parts.size < 2) return@mapNotNull null
            val lat = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val at = Freshness.parse(str("DateTime"))
            if (!Freshness.within(at, BMKG_HOURS, keepUndated = false)) return@mapNotNull null
            val label = buildString {
                append("BMKG TSUNAMI POTENTIAL: ")
                append(str("Wilayah")?.takeIf { it.isNotBlank() } ?: "Indonesia")
                str("Magnitude")?.takeIf { it.isNotBlank() }?.let { append(" · M$it") }
                if (verdict.isNotBlank()) append(" · $verdict")
                Freshness.ago(at)?.let { append(" · $it") }
            }
            GeoBlip(lat, lon, label, "https://www.bmkg.go.id/gempabumi-tsunami")
        }
    }

    /**
     * ISO 6709, as JMA writes it: "+07.5-080.8/", sometimes with a
     * depth on the end — "+23.8+122.9-10000/", ten kilometres down.
     *
     * Every field is signed, which is what makes it parseable at all:
     * the signs are the separators. Taking the first two and ignoring
     * the rest is correct for every form in the feed.
     */
    internal fun iso6709(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val numbers = signedRegex.findAll(raw).map { it.value }.take(2).toList()
        if (numbers.size < 2) return null
        val lat = numbers[0].toDoubleOrNull() ?: return null
        val lon = numbers[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private val signedRegex = Regex("[+-]\\d+(?:\\.\\d+)?")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun get(url: String, who: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IOException("$who HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("$who returned an empty body")
            body
        }
    }

    /** Either coordinate encoding the bulletin happens to use. */
    internal fun coordsOf(block: String): Pair<Double, Double>? {
        pointRegex.find(block)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        val lat = latRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val lon = lonRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun tag(block: String, name: String): String? {
        Regex("<$name[^>]*href=\"([^\"]+)\"").find(block)?.let { return it.groupValues[1] }
        return Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val JMA_URL = "https://www.jma.go.jp/bosai/tsunami/data/list.json"
        const val BMKG_URL = "https://data.bmkg.go.id/DataMKG/TEWS/gempaterkini.json"

        /** JMA leaves bulletins up for weeks. A tsunami bulletin three
         *  days old is history, not a warning. */
        const val JMA_HOURS = 72

        const val BMKG_HOURS = 72
    }

    private fun clean(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()
}
