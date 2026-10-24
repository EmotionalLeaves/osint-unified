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
 * because the files are megabytes and the satellites only pass
 * overhead every few hours.
 *
 * Two instruments, not one. MODIS sees a kilometre across and has
 * flown since 1999; VIIRS sees 375 metres and finds roughly three
 * times as many fires, including small ones MODIS cannot resolve at
 * all — a burning building, a gas flare, the start of something. A map
 * of only the large fires is a map that says a fire began when it got
 * big.
 */
class FiresDataSource(
    private val cache: DiskCache? = null,
    private val client: OkHttpClient = Http.bulkClient
) : PartlyAnswered {

    @Volatile override var lastGaps: String? = null
        private set

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        // Parsed here rather than below, so a body that fetched and
        // then failed to parse counts as a missing instrument.
        val parsed = SOURCES.map { (key, url) ->
            runCatching { load(key, url)?.let { parse(it) } }
        }
        // An instrument counts as having answered only if its file
        // both arrived and parsed.
        val answered = parsed.mapNotNull { it.getOrNull() }
        // Losing one instrument is losing half the detections, and
        // nothing said so. MODIS and VIIRS see the same ground at
        // different resolutions, so a map drawn from one of them is a
        // real map with real gaps in it — which is exactly the state
        // `lastGaps` exists to name.
        lastGaps =
            if (answered.size >= SOURCES.size) null
            else "${SOURCES.size - answered.size} of ${SOURCES.size} " +
                "satellite instruments did not answer"
        if (answered.isEmpty()) throw IOException("NASA FIRMS unavailable")
        // Deliberately not deduplicated between instruments. The two
        // satellites see the same fire at different resolutions and
        // minutes apart, and the thinning that runs before anything is
        // drawn already keeps the count sane; merging them by distance
        // would quietly drop the small detections, which are the ones
        // worth having.
        val rows = answered.flatten()
        if (rows.isEmpty()) {
            throw IOException("FIRMS answered with no detections, which never happens")
        }
        rows
    }

    private fun load(key: String, url: String): String? {
        cache?.read(key, CACHE_TTL_MS)?.let { return it }
        val fetched = runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
        if (!fetched.isNullOrBlank() && fetched.startsWith("latitude,")) {
            cache?.write(key, fetched)
            return fetched
        }
        // A stale copy of where the world was burning three hours ago
        // beats an empty map that says it is not.
        return cache?.readStale(key)
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
        // An empty or header-less body is a body that fetched and
        // did not parse, and the caller counts what parsed rather
        // than what it asked for — so a malformed MODIS file with a
        // good VIIRS one beside it halved the map and reported no
        // gap at all.
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) throw IOException("FIRMS returned an empty file")
        val columns = lines.next().split(',').map { it.trim().lowercase() }
        fun col(name: String) = columns.indexOf(name)
        val latAt = col("latitude")
        val lonAt = col("longitude")
        val confidenceAt = col("confidence")
        val frpAt = col("frp")
        val dateAt = col("acq_date")
        val timeAt = col("acq_time")
        if (latAt < 0 || lonAt < 0) {
            throw IOException("FIRMS answered without latitude or longitude columns")
        }

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
        /** Both instruments, cached separately so one being slow
         *  does not cost the other's detections. */
        val SOURCES = listOf(
            "firms-modis-24h" to
                "https://firms.modaps.eosdis.nasa.gov/data/active_fire/" +
                "modis-c6.1/csv/MODIS_C6_1_Global_24h.csv",
            "firms-viirs-24h" to
                "https://firms.modaps.eosdis.nasa.gov/data/active_fire/" +
                "noaa-20-viirs-c2/csv/J1_VIIRS_C2_Global_24h.csv"
        )

        /** The satellites pass over every few hours; asking more often
         *  downloads two megabytes to learn nothing. */
        const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

        /** Below this MODIS is flagging sun glint and gas flares. */
        const val MIN_CONFIDENCE = 50

        /** A bad fire day worldwide is well under this, per instrument. */
        const val MAX_POINTS = 60_000
    }
}
