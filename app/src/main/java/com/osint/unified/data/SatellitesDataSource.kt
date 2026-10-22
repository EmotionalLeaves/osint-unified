package com.osint.unified.data

import com.osint.unified.domain.OrbitPropagator
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
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Every tracked satellite, positioned live.
 *
 * The orbital maths lives in [OrbitPropagator], which is tested against an
 * observed ISS position rather than trusted.
 *
 * The previous source (wheretheiss.at) turned out to track exactly one
 * object — the ISS — so the layer was empty no matter how many NORAD IDs it
 * was asked for. Celestrak instead publishes the mean orbital elements for
 * the whole active catalogue, keyless, so positions are computed here.
 *
 * The propagator is Keplerian with the J2 secular drift of the node and
 * perigee, not full SGP4: good to roughly tens of kilometres for low orbits
 * within a day of the element epoch. That is far below one pixel at the
 * zoom levels a globe is viewed at, and the label says the position is
 * computed rather than observed.
 */
class SatellitesDataSource(
    private val cache: DiskCache? = null,
    private val client: OkHttpClient = Http.feedClient
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Mean elements for one object, as published by Celestrak. */
    private data class Elements(
        val name: String,
        val noradId: Int,
        val epochMillis: Long,
        val meanMotion: Double,      // revolutions per day
        val eccentricity: Double,
        val inclination: Double,     // degrees
        val raan: Double,            // degrees
        val argPerigee: Double,      // degrees
        val meanAnomaly: Double      // degrees
    )

    @Volatile private var catalogue: List<Elements> = emptyList()
    @Volatile private var fetchedAt = 0L

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (catalogue.isEmpty() || now - fetchedAt > CATALOGUE_TTL_MS) {
            val loaded = runCatching { loadCatalogue() }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                catalogue = loaded
                fetchedAt = now
            }
        }
        val snapshot = catalogue
        if (snapshot.isEmpty()) return@withContext emptyList()
        snapshot.mapNotNull { propagate(it, now) }
    }

    private fun loadCatalogue(): List<Elements> {
        // Celestrak asks callers to cache and rate-limits those who do not;
        // a health check caught it answering 403 after a few fetches. So the
        // catalogue is kept on disk for a day, and if the service refuses,
        // the last good copy is used rather than emptying the layer.
        cache?.read(CACHE_KEY, CATALOGUE_TTL_MS)?.let { cached ->
            parseCatalogue(cached).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val body = fetchCatalogue() ?: cache?.readStale(CACHE_KEY) ?: return emptyList()
        val parsed = parseCatalogue(body)
        if (parsed.isNotEmpty()) cache?.write(CACHE_KEY, body)
        return parsed
    }

    /** Tries the whole catalogue, then progressively smaller groups, so a
     *  refusal on the big one still leaves the notable objects plotted. */
    private fun fetchCatalogue(): String? {
        GROUPS.forEach { group ->
            val body = runCatching {
                val request = Request.Builder()
                    .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=json")
                    .header("User-Agent", "OSINT-Unified/1.0 (research)")
                    .build()
                Http.bulkClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull()
            if (!body.isNullOrBlank() && body.trimStart().startsWith("[")) return body
        }
        return null
    }

    private fun parseCatalogue(body: String): List<Elements> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        // The epoch carries fractional seconds and they were being thrown
        // away. A satellite in low orbit covers 7.7 km every second, so
        // discarding up to one of them moved the whole prediction by a town.
        fun fractionMillis(epoch: String): Long {
            val dot = epoch.indexOf('.')
            if (dot < 0) return 0L
            val digits = epoch.substring(dot + 1).takeWhile { it.isDigit() }
            if (digits.isEmpty()) return 0L
            return (("0." + digits).toDoubleOrNull() ?: 0.0).times(1000.0).toLong()
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val epoch = s("EPOCH") ?: return@mapNotNull null
            val epochMs = runCatching {
                fmt.parse(epoch.substringBefore('.'))?.time?.plus(fractionMillis(epoch))
            }.getOrNull() ?: return@mapNotNull null
            Elements(
                name = s("OBJECT_NAME") ?: "satellite",
                noradId = (o["NORAD_CAT_ID"] as? JsonPrimitive)?.intOrNull ?: 0,
                epochMillis = epochMs,
                meanMotion = d("MEAN_MOTION") ?: return@mapNotNull null,
                eccentricity = d("ECCENTRICITY") ?: 0.0,
                inclination = d("INCLINATION") ?: 0.0,
                raan = d("RA_OF_ASC_NODE") ?: 0.0,
                argPerigee = d("ARG_OF_PERICENTER") ?: 0.0,
                meanAnomaly = d("MEAN_ANOMALY") ?: 0.0
            )
        }
    }

    /** Sub-satellite point and altitude at [nowMillis]. */
    private fun propagate(e: Elements, nowMillis: Long): GeoBlip? {
        val age = abs(nowMillis - e.epochMillis)
        // How fast a stale element set goes wrong depends on the orbit.
        // Something circling fourteen times a day is dragged by the
        // atmosphere and its along-track error grows by hundreds of
        // kilometres a day; a geostationary object barely moves relative to
        // its own prediction. One fortnight-long cut-off for both plotted
        // low-orbit objects that had not been where the map said for a week.
        val limit = if (e.meanMotion >= LEO_REVS_PER_DAY) LEO_MAX_AGE_MS else HIGH_MAX_AGE_MS
        if (age > limit) return null
        val p = OrbitPropagator.propagate(
            OrbitPropagator.Elements(
                meanMotion = e.meanMotion,
                eccentricity = e.eccentricity,
                inclination = e.inclination,
                raan = e.raan,
                argPerigee = e.argPerigee,
                meanAnomaly = e.meanAnomaly,
                epochMillis = e.epochMillis
            ),
            nowMillis
        ) ?: return null

        val altitude = p.altitudeKm.toInt()
        // Decayed objects and junk element sets produce impossible orbits.
        if (altitude < 80 || altitude > 100_000) return null
        // Past a day old, the age is part of the reading: the marker is
        // where the elements say the object should be, not where anything
        // saw it.
        val staleness = if (age > 86_400_000L) " · elements ${age / 86_400_000L}d old" else ""
        return GeoBlip(
            p.lat, p.lon,
            "${e.name} · ${altitude}km$staleness",
            "https://celestrak.org/satcat/table-satcat.php?CATNR=${e.noradId}"
        )
    }

    private companion object {
        const val CATALOGUE_TTL_MS = 24 * 60 * 60 * 1000L
        const val CACHE_KEY = "celestrak-gp"

        /** Above this many revolutions a day an object is low enough for
         *  the atmosphere to drag it off a drag-free prediction. */
        const val LEO_REVS_PER_DAY = 10.0

        /** Four days of a low orbit is already tens of kilometres of
         *  along-track error; a fortnight is a different continent. */
        const val LEO_MAX_AGE_MS = 4L * 86_400_000L

        /** Higher orbits drift slowly enough to stay useful for longer. */
        const val HIGH_MAX_AGE_MS = 21L * 86_400_000L

        /** Largest first; each is a fallback for the one before it. */
        val GROUPS = listOf("active", "visual", "stations")
    }
}
