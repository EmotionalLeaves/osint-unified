package com.osint.unified.data

import android.content.Context
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

/**
 * Where the internet stopped working, and roughly when.
 *
 * IODA, at Georgia Tech, watches three independent signals for every
 * country and region on Earth — the routes other networks advertise,
 * whether addresses answer a ping, and the background noise every
 * live network leaks into a darknet — and raises an alert when they
 * drop together. A national shutdown, a cut cable, a power failure
 * and a censorship event all look like this, and which one it is is
 * exactly the question an investigator is there to answer.
 *
 * This is the layer that makes the globe worth looking at during
 * something. Nothing else here reports a country going dark.
 *
 * Alerts name a country or a region and carry no coordinates, so each
 * is placed on its country's centroid and flagged approximate. The
 * position is the country; the outage is not a point.
 */
class OutageDataSource(
    private val appContext: Context,
    private val client: OkHttpClient = Http.feedClient
) {

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val request = Request.Builder()
            .url("$SOURCE?from=${now - WINDOW_SECONDS}&until=$now&limit=500")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw IOException("IODA HTTP ${resp.code}")
            if (text.isNullOrBlank()) throw IOException("IODA returned nothing")
            text
        }
        OutageAlerts.parse(body, CountryCentroids.load(appContext))
    }

    private companion object {
        const val SOURCE = "https://api.ioda.inetintel.cc.gatech.edu/v2/outages/alerts"

        /** A day. An outage that started yesterday is still an outage,
         *  and a window shorter than this misses the start of one. */
        const val WINDOW_SECONDS = 24 * 3600
    }
}

/**
 * Reading IODA's alerts, with nothing Android in it.
 *
 * Separated from the fetch so the grouping — which is where the
 * judgement is, and where a mistake looks like twelve outages in
 * Malawi — can be tested without a device.
 */
internal object OutageAlerts {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Alerts into one marker per place.
     *
     * A country losing connectivity trips every detection method at
     * once and often several times over an hour, so the raw list has
     * a dozen rows for one event. Grouped, counted, and labelled with
     * the worst level seen — a map with twelve markers on Malawi is
     * reporting twelve outages, which is not what happened.
     */
    internal fun parse(
        body: String,
        countries: Map<String, CountryCentroids.Place>
    ): List<GeoBlip> {
        val alerts = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: runCatching {
                json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            }.getOrNull()
            ?: return emptyList()

        data class Seen(
            var place: CountryCentroids.Place,
            var names: MutableSet<String> = linkedSetOf(),
            var methods: MutableSet<String> = linkedSetOf(),
            var critical: Boolean = false,
            var count: Int = 0
        )

        val byCountry = LinkedHashMap<String, Seen>()
        alerts.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val entity = o["entity"] as? JsonObject ?: return@forEach
            fun s(obj: JsonObject?, key: String) =
                (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

            // The country is in the fully qualified id even when the
            // alert is about a region inside it: "geo.netacuity.AF.MW"
            // is Malawi. The entity's own code for a region is a
            // number, which names nowhere.
            val attrs = entity["attrs"] as? JsonObject
            val fqid = s(attrs, "fqid").orEmpty()
            val type = s(entity, "type").orEmpty()
            val code = when {
                type == "country" -> s(entity, "code")
                else -> fqid.split('.').lastOrNull { it.length == 2 && it.all(Char::isUpperCase) }
            } ?: fqid.split('.').getOrNull(3)
            val place = CountryCentroids.find(countries, code)
                ?: CountryCentroids.find(countries, s(entity, "name"))
                ?: return@forEach

            val held = byCountry.getOrPut(place.name) { Seen(place) }
            held.count++
            s(entity, "name")?.let { if (it != place.name) held.names.add(it) }
            s(o, "datasource")?.let { held.methods.add(friendly(it)) }
            if (s(o, "level")?.equals("critical", ignoreCase = true) == true) {
                held.critical = true
            }
        }

        return byCountry.values.map { seen ->
            val label = buildString {
                append(seen.place.name)
                append(" · connectivity ")
                append(if (seen.critical) "down" else "degraded")
                if (seen.names.isNotEmpty()) {
                    append(" · ")
                    append(seen.names.take(3).joinToString(", "))
                    if (seen.names.size > 3) append(" +${seen.names.size - 3}")
                }
                if (seen.methods.isNotEmpty()) {
                    append(" · seen by ").append(seen.methods.joinToString(", "))
                }
                append(" · ${seen.count} alert")
                if (seen.count != 1) append("s")
            }
            GeoBlip(
                lat = seen.place.lat,
                lon = seen.place.lon,
                label = label,
                url = "https://ioda.inetintel.cc.gatech.edu/",
                // The country, not the outage. Nothing may measure a
                // distance to this.
                approximate = true
            )
        }
    }

    /** IODA's three signals, in words rather than in its own slugs. */
    private fun friendly(datasource: String): String = when (datasource) {
        "bgp" -> "routing"
        "ping-slash24" -> "pings"
        "merit-nt", "ucsd-nt" -> "darknet"
        "gtr", "gtr-norm" -> "traffic"
        else -> datasource
    }
}
