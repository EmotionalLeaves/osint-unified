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
            val query = """
                SELECT ?cLabel ?coord WHERE {
                  ?c wdt:P31 wd:Q350604 .
                  ?c wdt:P625 ?coord .
                  OPTIONAL { ?c wdt:P582 ?end . }
                  FILTER(!BOUND(?end))
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT 250
            """.trimIndent()
            val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OSINT-Unified/1.0 (research; contact via app)")
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
                    GeoBlip(lat, lon, label)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
