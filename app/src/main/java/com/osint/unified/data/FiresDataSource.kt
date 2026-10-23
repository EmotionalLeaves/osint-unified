package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Every fire burning on Earth in the last twenty-four hours.
 *
 * This used to ask EONET for open wildfire events, which returned an
 * empty list — EONET curates named, newsworthy fire *events* and often
 * has none open at all, so the layer was reliably blank and the map
 * claimed the world was not on fire.
 *
 * NASA FIRMS is the actual answer: the satellites' own thermal anomaly
 * detections, every one of them, worldwide, published as a plain CSV
 * with no key. A quiet day is thirty thousand detections. It is cached,
 * because the file is two megabytes and the satellites only pass
 * overhead every few hours.
 */
class FiresDataSource(
    private val cache: DiskCache? = null,
    private val client: OkHttpClient = Http.bulkClient
) {

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        val body = load() ?: throw IOException("NASA FIRMS unavailable")
        val rows = parse(body)
        if (rows.isEmpty()) throw IOException("FIRMS answered with no detections, which never happens")
        rows
    }

    private fun load(): String? {
        cache?.read(CACHE_KEY, CACHE_TTL_MS)?.let { return it }
        val fetched = runCatching {
            val request = Request.Builder().url(SOURCE).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
        if (!fetched.isNullOrBlank() && fetched.startsWith("latitude,")) {
            cache?.write(CACHE_KEY, fetched)
            return fetched
        }
        // A stale copy of where the world was burning three hours ago
        // beats an empty map that says it is not.
        return cache?.readStale(CACHE_KEY)
    }

    /**
     * FIRMS CSV into points.
     *
     * Low-confidence detections are dropped: MODIS flags sun glint and
     * hot industrial sites as fires, and a map of gas flares labelled
     * "wildfire" is worse than one without them. Fire radiative power
     * goes in the label, because a 2 MW smoulder and a 900 MW firestorm
     * are not the same event.
     */
    internal fun parse(csv: String): List<GeoBlip> {
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val header = lines.next().split(',').map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name)
        val latAt = col("latitude")
        val lonAt = col("longitude")
        val confidenceAt = col("confidence")
        val frpAt = col("frp")
        val dateAt = col("acq_date")
        val timeAt = col("acq_time")
        if (latAt < 0 || lonAt < 0) return emptyList()

        val out = ArrayList<GeoBlip>(4096)
        while (lines.hasNext() && out.size < MAX_POINTS) {
            val line = lines.next()
            if (line.isBlank()) continue
            val f = line.split(',')
            if (f.size <= maxOf(latAt, lonAt)) continue
            val lat = f[latAt].toDoubleOrNull() ?: continue
            val lon = f[lonAt].toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue


            val confidence = f.getOrNull(confidenceAt)?.trim().orEmpty()
            if (!confident(confidence)) continue

            val frp = f.getOrNull(frpAt)?.toDoubleOrNull()
            val when0 = f.getOrNull(dateAt)?.trim().orEmpty()
            val at = f.getOrNull(timeAt)?.trim().orEmpty().padStart(4, '0')
            val label = buildString {
                append("Fire")
                if (frp != null) append(" · %.0f MW".format(Locale.US, frp))
                if (when0.isNotBlank()) {
                    append(" · ").append(when0)
                    if (at.length == 4) append(" ").append(at.substring(0, 2))
                        .append(':').append(at.substring(2))
                    append("Z")
                }
            }
            out.add(GeoBlip(lat, lon, label))
        }
        return out
    }

    /**
     * MODIS reports confidence as 0–100, VIIRS as low/nominal/high.
     * Both appear in these files depending on which product is read.
     */
    private fun confident(raw: String): Boolean {
        val asNumber = raw.toIntOrNull()
        if (asNumber != null) return asNumber >= MIN_CONFIDENCE
        return !raw.equals("low", ignoreCase = true)
    }

    private companion object {
        /** MODIS rather than VIIRS: a sixth of the bytes for the same
         *  worldwide picture, which matters on a phone. */
        const val SOURCE =
            "https://firms.modaps.eosdis.nasa.gov/data/active_fire/" +
                "modis-c6.1/csv/MODIS_C6_1_Global_24h.csv"
        const val CACHE_KEY = "firms-global-24h"

        /** The satellites pass over every few hours; asking more often
         *  downloads two megabytes to learn nothing. */
        const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

        /** Below this MODIS is flagging sun glint and gas flares. */
        const val MIN_CONFIDENCE = 50

        /** A bad fire day worldwide is well under this. */
        const val MAX_POINTS = 60_000
    }
}
