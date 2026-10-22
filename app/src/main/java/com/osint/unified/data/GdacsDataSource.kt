package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GDACS — the EU's global disaster alert system: earthquakes, cyclones,
 * floods, droughts, volcanoes and wildfires with a humanitarian impact
 * estimate, colour-coded green/orange/red.
 *
 * The JSON endpoint this used to call answers "Eventtype is required" to
 * every request and returned nothing, so the layer sat at zero. GDACS's RSS
 * feed carries the same events with georss coordinates and needs no
 * parameters, so that is what is parsed here.
 */
class GdacsDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val typeNames = mapOf(
        "EQ" to "Quake", "TC" to "Cyclone", "FL" to "Flood",
        "DR" to "Drought", "VO" to "Volcano", "WF" to "Wildfire",
        "TS" to "Tsunami"
    )

    private val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.gdacs.org/xml/rss.xml")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("GDACS HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("GDACS returned nothing")
                itemRegex.findAll(body).mapNotNull { m ->
                    val item = m.groupValues[1]
                    val (lat, lon) = coordsOf(item) ?: return@mapNotNull null
                    val type = tag(item, "gdacs:eventtype")
                    val alert = tag(item, "gdacs:alertlevel")
                    val title = tag(item, "title")?.let { clean(it) } ?: "event"
                    // GDACS keeps alerts in the feed well past the event, so
                    // anything older than a week is dropped and the rest
                    // carries its age.
                    val published = Freshness.parse(tag(item, "pubDate")?.let { clean(it) })
                    if (!Freshness.within(published, 7 * 24)) return@mapNotNull null
                    val age = Freshness.ago(published)
                    val label = buildString {
                        typeNames[type]?.let { append("$it: ") }
                        append(title.take(120))
                        if (!alert.isNullOrBlank()) append(" · ${alert.uppercase()}")
                        if (age != null) append(" · $age")
                    }
                    GeoBlip(lat, lon, label, tag(item, "link")?.let { clean(it) })
                }.toList()
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("GDACS unavailable: ${e.message}", e)
        }
    }

    /** georss:point carries "lat lon"; some items use geo:lat / geo:long. */
    private fun coordsOf(item: String): Pair<Double, Double>? {
        tag(item, "georss:point")?.trim()?.split(Regex("\\s+"))?.let { parts ->
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        val lat = tag(item, "geo:lat")?.trim()?.toDoubleOrNull()
        val lon = tag(item, "geo:long")?.trim()?.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        return null
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<${Regex.escape(name)}[^>]*>(.*?)</${Regex.escape(name)}>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    private fun clean(s: String) = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()
}
