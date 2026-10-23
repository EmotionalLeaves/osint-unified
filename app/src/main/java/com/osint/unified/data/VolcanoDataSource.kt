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
import java.time.LocalDate

/**
 * Volcanoes that have erupted recently, from the Smithsonian.
 *
 * This asked EONET for open volcano events and got an empty list — EONET
 * opens an event only when a volcano makes the news, so the layer was
 * blank while several volcanoes were erupting. The Global Volcanism
 * Program is the authority instead: every eruption since 1960, with
 * position, start date and explosivity index, served as GeoJSON with no
 * key.
 *
 * What counts as "erupting now" is a judgement, and the honest answer is
 * that the Smithsonian records eruptions with start dates and often no
 * end date until long afterwards. So the layer shows eruptions that began
 * within the last two years, newest first, and every label carries its
 * start date — a volcano that began erupting in 2025 and has not stopped
 * is a true thing to show, as long as the map says when it started.
 */
class VolcanoDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SOURCE)
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Smithsonian GVP HTTP ${resp.code}")
            resp.body?.string()
        } ?: throw IOException("Smithsonian GVP returned nothing")
        parse(body)
    }

    internal fun parse(body: String): List<GeoBlip> {
        val features = runCatching {
            json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
        }.getOrNull() ?: throw IOException("Smithsonian GVP answered in an unexpected shape")

        val cutoff = LocalDate.now().minusYears(RECENT_YEARS)
        val out = ArrayList<GeoBlip>()
        val seen = HashSet<String>()

        features.forEach { element ->
            val feature = element as? JsonObject ?: return@forEach
            val geometry = feature["geometry"] as? JsonObject ?: return@forEach
            val coordinates = (geometry["coordinates"] as? kotlinx.serialization.json.JsonArray)
                ?: return@forEach
            val lon = (coordinates.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val lat = (coordinates.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val props = feature["properties"] as? JsonObject ?: return@forEach

            val name = (props["VolcanoName"] as? JsonPrimitive)?.contentOrNull ?: "Volcano"
            val started = parseDate((props["StartDate"] as? JsonPrimitive)?.contentOrNull)
                ?: return@forEach
            if (started.isBefore(cutoff)) return@forEach
            // One volcano, one marker, however many eruptive episodes the
            // catalogue records for it.
            if (!seen.add(name.lowercase())) return@forEach

            val vei = (props["ExplosivityIndexMax"] as? JsonPrimitive)?.intOrNull
            val label = buildString {
                append(name)
                if (vei != null) append(" · VEI ").append(vei)
                append(" · since ").append(started)
            }
            out.add(
                GeoBlip(
                    lat, lon, label,
                    "https://volcano.si.edu/volcano.cfm?vn=" +
                        ((props["VolcanoNumber"] as? JsonPrimitive)?.contentOrNull ?: "")
                )
            )
        }
        return out
    }

    /**
     * The catalogue writes dates several ways across its history.
     *
     * Refusing the ones that do not parse would silently drop whole
     * decades, and guessing at them would plot an eruption from 1963 as
     * current, so each shape is handled and anything unrecognised is left
     * out rather than assumed recent.
     */
    internal fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        runCatching { return LocalDate.parse(s.substring(0, 10)) }
        // 20260131, and the catalogue's habit of zero months and days.
        val digits = s.filter { it.isDigit() }
        if (digits.length >= 4) {
            val year = digits.substring(0, 4).toIntOrNull() ?: return null
            if (year !in 1900..2100) return null
            val month = digits.drop(4).take(2).toIntOrNull()?.coerceIn(1, 12) ?: 1
            val day = digits.drop(6).take(2).toIntOrNull()?.coerceIn(1, 28) ?: 1
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        return null
    }

    private companion object {
        /** Newest first, so the cap keeps the recent ones. */
        const val SOURCE =
            "https://webservices.volcano.si.edu/geoserver/GVP-VOTW/ows" +
                "?service=WFS&version=2.0.0&request=GetFeature" +
                "&typeName=GVP-VOTW:E3WebApp_Eruptions1960" +
                "&outputFormat=application/json&count=600&sortBy=StartDate+D"

        /** An eruption that began last year and has not been declared
         *  over is still an erupting volcano. Three years back is
         *  history. */
        const val RECENT_YEARS = 2L
    }
}
