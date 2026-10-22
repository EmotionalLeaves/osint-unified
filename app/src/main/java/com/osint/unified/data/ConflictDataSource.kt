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
import java.net.URLEncoder

/**
 * Ongoing armed conflicts geocoded via Wikidata SPARQL (keyless): items that
 * are an armed conflict with a coordinate and no end date, as the CONFLICT
 * layer.
 */
class ConflictDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pointRegex = Regex("""Point\(([-\d.]+)\s+([-\d.]+)\)""")

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            // Conflicts with no recorded end date. "Ongoing" in Wikidata
            // can still mean a conflict that began decades ago, so the start
            // date comes back too and is shown in the label rather than
            // letting every marker read as though it flared up this week.
            val query = """
                SELECT ?cLabel ?coord ?start WHERE {
                  ?c wdt:P31/wdt:P279* wd:Q350604 .
                  ?c wdt:P625 ?coord .
                  OPTIONAL { ?c wdt:P582 ?end . }
                  OPTIONAL { ?c wdt:P580 ?start . }
                  FILTER(!BOUND(?end))
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT 600
            """.trimIndent()
            val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OSINT-Unified/1.0 (https://github.com/EmotionalLeaves/osint-unified)")
                .header("Accept", "application/sparql-results+json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val bindings = json.parseToJsonElement(body)
                    .jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
                    ?: return@withContext emptyList()
                bindings.mapNotNull { b ->
                    val o = b.jsonObject
                    val coord = ((o["coord"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val m = pointRegex.find(coord) ?: return@mapNotNull null
                    val lon = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lat = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                    val label = ((o["cLabel"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: "conflict"
                    val started = ((o["start"] as? JsonObject)?.get("value") as? JsonPrimitive)
                        ?.contentOrNull?.take(4)?.toIntOrNull()
                    val full = if (started != null) "$label · since $started" else label
                    GeoBlip(lat, lon, full, "https://www.wikidata.org/w/index.php?search=" + URLEncoder.encode(label, "UTF-8"))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
