package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * FAA Special Use Airspace — restricted, prohibited, warning, alert and
 * military operations areas, from the FAA's own ArcGIS service (keyless).
 *
 * Polygons are reduced to their centroid so they sit on the globe as no-go
 * markers; the label carries the designator, class and vertical limits.
 */
class AirspaceDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base =
        "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/ArcGIS/rest/services/" +
            "Special_Use_Airspace/FeatureServer/0/query"

    @Volatile private var cache: List<GeoBlip> = emptyList()
    @Volatile private var fetchedAt = 0L

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val age = System.currentTimeMillis() - fetchedAt
        if (cache.isNotEmpty() && age < 12 * 60 * 60 * 1000L) return@withContext cache
        val out = ArrayList<GeoBlip>()
        // The service caps each response at 2000 features, so page through.
        var offset = 0
        while (offset < 8000) {
            val page = runCatching { page(offset) }.getOrDefault(emptyList())
            if (page.isEmpty()) break
            out.addAll(page)
            offset += 2000
        }
        if (out.isNotEmpty()) { cache = out; fetchedAt = System.currentTimeMillis() }
        out
    }

    private fun page(offset: Int): List<GeoBlip> {
        val url = "$base?where=1%3D1&outFields=NAME,TYPE_CODE,UPPER_VAL,UPPER_UOM,LOWER_VAL," +
            "LOWER_UOM,TIMESOFUSE&f=geojson&resultRecordCount=2000&resultOffset=$offset"
        val request = Request.Builder().url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray
                ?: return emptyList()
            val out = ArrayList<GeoBlip>(features.size)
            features.forEach { f ->
                val o = f as? JsonObject ?: return@forEach
                val p = o["properties"] as? JsonObject
                val c = centroid(o["geometry"] as? JsonObject) ?: return@forEach
                fun s(k: String) = (p?.get(k) as? JsonPrimitive)?.contentOrNull
                val name = s("NAME") ?: "SUA"
                val kind = when (s("TYPE_CODE")) {
                    "A" -> "ALERT"; "D" -> "DANGER"; "M" -> "MOA"
                    "P" -> "PROHIBITED"; "R" -> "RESTRICTED"; "W" -> "WARNING"
                    "N" -> "NSA"; else -> s("TYPE_CODE") ?: "SUA"
                }
                val floor = s("LOWER_VAL")?.let { "$it${s("LOWER_UOM") ?: ""}" }
                val ceil = s("UPPER_VAL")?.let { "$it${s("UPPER_UOM") ?: ""}" }
                val label = buildString {
                    append("$kind $name")
                    if (floor != null || ceil != null) append(" · ${floor ?: "SFC"}–${ceil ?: "UNL"}")
                    s("TIMESOFUSE")?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { append(" · ${it.take(60)}") }
                }
                out.add(GeoBlip(c.first, c.second, label))
            }
            return out
        }
    }

    /** Average of a polygon's outer ring — close enough to drop a marker on. */
    private fun centroid(geom: JsonObject?): Pair<Double, Double>? {
        val type = (geom?.get("type") as? JsonPrimitive)?.contentOrNull ?: return null
        val coords = geom["coordinates"] as? JsonArray ?: return null
        val ring: JsonArray = when (type) {
            "Polygon" -> coords.firstOrNull() as? JsonArray ?: return null
            "MultiPolygon" -> ((coords.firstOrNull() as? JsonArray)?.firstOrNull() as? JsonArray) ?: return null
            "Point" -> {
                val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
                val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
                return lat to lon
            }
            else -> return null
        }
        var sx = 0.0; var sy = 0.0; var n = 0
        ring.forEach { pt ->
            val a = pt as? JsonArray ?: return@forEach
            val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            sx += lon; sy += lat; n++
        }
        if (n == 0) return null
        val lat = sy / n; val lon = sx / n
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
