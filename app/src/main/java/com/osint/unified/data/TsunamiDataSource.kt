package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
 * Two Atom feeds, keyless: the National centre (Alaska, US west coast,
 * Atlantic, Caribbean) and the Pacific centre (Hawaii and the Pacific
 * basin). Both are a couple of kilobytes.
 */
class TsunamiDataSource(private val client: OkHttpClient = Http.client) {

    private val feeds = listOf(
        "https://www.tsunami.gov/events/xml/PAAQAtom.xml" to "NTWC",
        "https://www.tsunami.gov/events/xml/PHEBAtom.xml" to "PTWC"
    )

    private val entryRegex = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)

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
            feeds.map { (url, centre) -> async { runCatching { one(url, centre) } } }.awaitAll()
        }
        if (answers.all { it.isFailure }) {
            throw IOException(
                "no tsunami centre answered: " +
                    answers.mapNotNull { it.exceptionOrNull()?.message }.joinToString("; ")
            )
        }
        answers.mapNotNull { it.getOrNull() }.flatten()
    }

    private fun one(url: String, centre: String): List<GeoBlip> = run {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IOException("$centre HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("$centre returned an empty body")

            // Some bulletins carry no entries and put the whole message in
            // the feed header, which still has a title and a point. On that
            // path the only <updated> is the feed's own, which these centres
            // refresh independently of the bulletin — so it cannot be used
            // to decide whether the bulletin is current, and an undated
            // bulletin is dropped rather than shown as live.
            val entries = entryRegex.findAll(body).map { it.groupValues[1] }.toList()
            val wholeFeed = entries.isEmpty()
            val blocks = if (wholeFeed) listOf(body) else entries

            blocks.mapNotNull { block ->
                val (lat, lon) = coordsOf(block) ?: return@mapNotNull null
                val title = tag(block, "title")?.let(::clean) ?: "tsunami bulletin"
                val updated = Freshness.parse(tag(block, "updated")?.let(::clean))
                // These feeds keep the last bulletin up indefinitely, so an
                // old statement would otherwise read as a live warning.
                if (updated == null && wholeFeed) return@mapNotNull null
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

    private fun clean(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()
}
