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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Active severe-weather alerts from the US National Weather Service (keyless).
 * Alerts carry a polygon; we plot each at the polygon's centroid, labelled by
 * event type (Tornado Warning, Severe Thunderstorm Warning, …).
 */
class WeatherDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Active US weather alerts.
     *
     * Only a minority of alerts carry a polygon; most reference forecast
     * zones instead and have null geometry, which is why this layer showed
     * about ten of several hundred. Polygons and multipolygons are reduced
     * to a centroid, and zone-only alerts are placed at the centroid of the
     * state named in their zone code — approximate on purpose, with the
     * exact affected area kept in the label, which is where the precision
     * actually belongs.
     */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.weather.gov/alerts/active?status=actual&message_type=alert")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("NWS alerts HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("NWS alerts returned nothing")
                val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
                    ?: throw IOException("NWS alerts answered in an unexpected shape")
                val perState = HashMap<String, Int>()
                features.mapNotNull { f ->
                    val o = f.jsonObject
                    val props = o["properties"] as? JsonObject
                    val event = (props?.get("event") as? JsonPrimitive)?.contentOrNull ?: "Alert"
                    val area = (props?.get("areaDesc") as? JsonPrimitive)?.contentOrNull
                    val severity = (props?.get("severity") as? JsonPrimitive)?.contentOrNull
                    val sent = Freshness.parse((props?.get("sent") as? JsonPrimitive)?.contentOrNull)
                    val link = (props?.get("id") as? JsonPrimitive)?.contentOrNull

                    val point = centroidOf(o["geometry"] as? JsonObject)
                        ?: zonePoint(props, perState)
                        ?: return@mapNotNull null

                    val label = buildString {
                        append(event)
                        if (!severity.isNullOrBlank() && severity != "Unknown") append(" · $severity")
                        if (!area.isNullOrBlank()) append(" · ${area.take(90)}")
                        Freshness.ago(sent)?.let { append(" · $it") }
                    }
                    GeoBlip(point.first, point.second, label, link)
                }.take(3000)
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("NWS alerts unavailable: ${e.message}", e)
        }
    }

    /** Average of a polygon's outer ring, or of the first ring of a
     *  multipolygon. Good enough to drop a marker on. */
    private fun centroidOf(geom: JsonObject?): Pair<Double, Double>? {
        val type = (geom?.get("type") as? JsonPrimitive)?.contentOrNull ?: return null
        val coords = geom["coordinates"]?.jsonArray ?: return null
        val ring = when (type) {
            "Polygon" -> coords.getOrNull(0)?.jsonArray
            "MultiPolygon" -> coords.getOrNull(0)?.jsonArray?.getOrNull(0)?.jsonArray
            "Point" -> {
                val lon = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
                val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
                return lat to lon
            }
            else -> null
        } ?: return null
        var sumLat = 0.0
        var sumLon = 0.0
        var n = 0
        ring.forEach { p ->
            val a = p as? JsonArray ?: return@forEach
            val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            sumLat += lat; sumLon += lon; n++
        }
        return if (n == 0) null else (sumLat / n) to (sumLon / n)
    }

    /**
     * A zone-only alert gets its state's centroid, nudged along a small
     * spiral so a dozen alerts in one state stay individually tappable
     * instead of stacking into a single dot.
     */
    private fun zonePoint(props: JsonObject?, perState: MutableMap<String, Int>): Pair<Double, Double>? {
        val zones = (props?.get("affectedZones") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val code = zones.firstNotNullOfOrNull { z ->
            Regex("""/zones/\w+/([A-Z]{2})""").find(z)?.groupValues?.get(1)
        } ?: (props?.get("geocode") as? JsonObject)?.let { g ->
            ((g["UGC"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.take(2)
        } ?: return null

        val base = STATE_CENTROIDS[code] ?: return null
        val index = perState.merge(code, 1) { a, b -> a + b } ?: 1
        if (index == 1) return base
        val angle = (index - 1) * 2.399963   // golden angle, keeps points apart
        val radius = 0.18 * kotlin.math.sqrt((index - 1).toDouble())
        return (base.first + radius * kotlin.math.sin(angle)) to
            (base.second + radius * kotlin.math.cos(angle))
    }

    private companion object {
        private const val STATE_TABLE = """
            AL|32.8|-86.8, AK|64.0|-152.0, AZ|34.3|-111.7, AR|34.9|-92.4,
            CA|37.2|-119.5, CO|39.0|-105.5, CT|41.6|-72.7, DE|39.0|-75.5,
            DC|38.9|-77.0, FL|28.6|-82.4, GA|32.6|-83.4, HI|20.3|-156.4,
            ID|44.4|-114.6, IL|40.0|-89.2, IN|39.9|-86.3, IA|42.1|-93.5,
            KS|38.5|-98.4, KY|37.5|-85.3, LA|31.1|-92.0, ME|45.4|-69.2,
            MD|39.0|-76.8, MA|42.3|-71.8, MI|44.3|-85.4, MN|46.3|-94.3,
            MS|32.7|-89.7, MO|38.4|-92.5, MT|47.0|-109.6, NE|41.5|-99.8,
            NV|39.3|-116.6, NH|43.7|-71.6, NJ|40.2|-74.7, NM|34.4|-106.1,
            NY|42.9|-75.5, NC|35.5|-79.4, ND|47.4|-100.5, OH|40.3|-82.8,
            OK|35.6|-97.5, OR|43.9|-120.6, PA|40.9|-77.8, RI|41.7|-71.6,
            SC|33.9|-80.9, SD|44.4|-100.2, TN|35.8|-86.4, TX|31.5|-99.3,
            UT|39.3|-111.7, VT|44.1|-72.7, VA|37.5|-78.9, WA|47.4|-120.5,
            WV|38.6|-80.6, WI|44.6|-89.7, WY|43.0|-107.6, PR|18.2|-66.5,
            VI|18.1|-64.8, GU|13.4|144.8, AS|-14.3|-170.7, MP|15.2|145.7
        """

        val STATE_CENTROIDS: Map<String, Pair<Double, Double>> by lazy {
            STATE_TABLE.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { row ->
                    val p = row.split('|')
                    val code = p.getOrNull(0)?.trim() ?: return@mapNotNull null
                    val lat = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = p.getOrNull(2)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    code to (lat to lon)
                }
                .toMap()
        }
    }
}
