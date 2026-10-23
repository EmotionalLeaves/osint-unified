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
import java.io.IOException
import java.util.Locale

/**
 * Every tropical cyclone in the water, worldwide.
 *
 * GDACS carries cyclones too, but as a humanitarian alert after the fact —
 * a storm appears when it is about to hit someone. A warning centre
 * publishes the storm itself: where its centre is right now, how hard it
 * is blowing, and which way it is going. On a globe that is a different
 * object, and it is the one a person means when they ask what storms are
 * out there.
 *
 * Two centres, because one is not the world. The National Hurricane
 * Center has the Atlantic and the east Pacific and nothing else — so
 * most of the planet's typhoons, which form in the west Pacific, were
 * simply absent, and the layer said "no cyclones" through a landfall on
 * Luzon. The Joint Typhoon Warning Center covers the rest and publishes
 * it as plain message text, which [JtwcAdvisory] reads.
 *
 * Both keyless. Either one failing leaves the other's storms on the map,
 * because half the world's cyclones is better than none; only a total
 * failure raises, since an empty list here is read out loud as "no named
 * storms anywhere".
 */
class CycloneDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** NHC's two-letter classifications, in the words people use. */
    private val classes = mapOf(
        "TD" to "tropical depression", "TS" to "tropical storm",
        "HU" to "hurricane", "MH" to "major hurricane",
        "SD" to "subtropical depression", "SS" to "subtropical storm",
        "STS" to "subtropical storm", "TY" to "typhoon",
        "STY" to "super typhoon", "PTC" to "potential tropical cyclone",
        "EX" to "post-tropical cyclone", "LO" to "remnant low"
    )

    /**
     * Every storm both centres can see, or a failure.
     *
     * An empty list has to mean "no named storms in the water", because
     * the globe and the world brief go on to say so out loud. Returning
     * it for a failed request as well would have the app announce an
     * all-clear during an outage, so a total failure is raised and the
     * caller leaves the layer unloaded instead.
     */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val atlantic = runCatching { nhc() }
        val elsewhere = JTWC_ADVISORIES.map { url -> runCatching { jtwc(url) } }
        // A failure is only a failure if nothing answered. One centre
        // being unreachable must not blank the other's storms, and must
        // not let the brief announce an all-clear either.
        if (atlantic.isFailure && elsewhere.all { it.isFailure }) {
            throw atlantic.exceptionOrNull()
                ?: elsewhere.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IOException("No cyclone centre could be reached")
        }
        atlantic.getOrDefault(emptyList()) +
            elsewhere.flatMap { it.getOrDefault(emptyList()) }
    }

    /** One JTWC advisory, as plain military message text. */
    private fun jtwc(url: String): List<GeoBlip> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IOException("JTWC HTTP ${resp.code}")
            if (body.isNullOrBlank()) throw IOException("JTWC returned an empty body")
            JtwcAdvisory.parse(body, url)
        }
    }

    private fun nhc(): List<GeoBlip> {
            val request = Request.Builder()
                .url("https://www.nhc.noaa.gov/CurrentStorms.json")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) throw IOException("NHC HTTP ${resp.code}")
                if (body.isNullOrBlank()) throw IOException("NHC returned an empty body")
                val storms = json.parseToJsonElement(body).jsonObject["activeStorms"] as? JsonArray
                    ?: throw IOException("NHC response had no activeStorms array")

                storms.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull

                    val lat = d("latitudeNumeric") ?: return@mapNotNull null
                    val lon = d("longitudeNumeric") ?: return@mapNotNull null
                    val name = s("name") ?: s("id") ?: "unnamed system"
                    val cls = s("classification")?.uppercase()
                    // Intensity is in knots; everyone outside aviation thinks
                    // in mph or km/h, so both are given.
                    val knots = s("intensity")?.toDoubleOrNull()

                    val label = buildString {
                        append(name)
                        classes[cls]?.let { append(" · $it") }
                            ?: cls?.let { append(" · $it") }
                        if (knots != null) {
                            append(" · %.0f kt (%.0f mph)".format(Locale.US, knots, knots * 1.15078))
                        }
                        s("pressure")?.let { append(" · $it mb") }
                        val dir = s("movementDir")
                        val speed = s("movementSpeed")
                        if (dir != null && speed != null) append(" · moving ${dir}° at $speed kt")
                        Freshness.ago(Freshness.parse(s("lastUpdate")))?.let { append(" · $it") }
                    }
                    val link = s("publicAdvisory")
                        ?: ((o["publicAdvisory"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
                        ?: "https://www.nhc.noaa.gov/"
                    GeoBlip(lat, lon, label, link)
                }
            }
    }

    private companion object {
        /** The two basins the hurricane centre does not cover. */
        val JTWC_ADVISORIES = listOf(
            "https://www.metoc.navy.mil/jtwc/products/abpwweb.txt",
            "https://www.metoc.navy.mil/jtwc/products/abioweb.txt"
        )
    }
}
