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
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Crowd-measured radiation from Safecast, around wherever you are
 * looking.
 *
 * Safecast grew out of Fukushima: volunteers with Geiger counters,
 * because the official numbers were not available fast enough or at
 * enough places. It is the only open, global, keyless radiation dataset
 * there is, and on a globe it answers a question no other layer can.
 *
 * It is an archive, and this layer took a long time to admit it. The
 * API ignores sort order, so the only way to ask for recent readings
 * was `captured_after` — which makes the query take longer than any
 * reasonable timeout. Probed again today: the plain call answers in
 * about a second, the dated one still does not answer at all. So the
 * radiation pill has been dark since the day it was added, and the one
 * thing that could have lit it up was a filter that cannot complete.
 *
 * The filter is gone. What comes back is what Safecast holds for that
 * place, oldest first, and every reading carries the date it was taken.
 * A measurement from 2014 near Fukushima is not stale data pretending
 * to be current — it is a real reading of a real place, and the label
 * says when. Dressing that up as live would be the lie; dropping it
 * because it is old leaves an empty layer over the one dataset that has
 * anything to say.
 *
 * Still queried around the viewport, because the dataset is tens of
 * millions of points and a global fetch is meaningless.
 */
// Back on the ordinary client: without the date filter the query
// answers in about a second, and the long timeout existed only to wait
// for a request that never completed.
class RadiationDataSource(private val client: OkHttpClient = Http.feedClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private companion object {
        /** An archive does not change; this is about panning, not
         *  freshness. The label's age is rebuilt on every fetch anyway. */
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }

    /**
     * One object, so the key and the readings can never belong to
     * different places. They were two volatiles written in sequence, which
     * two overlapping fetches could interleave into a cache hit serving
     * Fukushima's readings for Nevada.
     */
    private data class Cached(
        val key: String,
        val points: List<GeoBlip>,
        val fetchedAt: Long
    )

    @Volatile private var cached: Cached? = null

    suspend fun fetch(lat: Double, lon: Double, zoom: Double): List<GeoBlip> =
        withContext(Dispatchers.IO) {
            // Radius with the zoom, and a cache key rounded to it, so panning
            // within one area does not re-query.
            val radiusM = when {
                zoom >= 11 -> 20_000
                zoom >= 8 -> 60_000
                zoom >= 5 -> 200_000
                else -> 500_000
            }
            val grid = (radiusM / 111_000.0).coerceAtLeast(0.2)
            val key = "${Math.round(lat / grid)}:${Math.round(lon / grid)}:$radiusM"
            val held = cached
            // Expires, because every label carries a "2 days ago" built at
            // fetch time; a cache with no clock would keep showing an age
            // that stopped being true hours earlier.
            if (held != null && held.key == key &&
                System.currentTimeMillis() - held.fetchedAt < CACHE_TTL_MS
            ) {
                return@withContext held.points
            }

            val url = "https://api.safecast.org/measurements.json" +
                "?latitude=$lat&longitude=$lon&distance=$radiusM"
            val parsed = run {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OSINT-Unified/1.0 (research)")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    // Never the previous area's readings. Serving those
                    // plots Fukushima's numbers while the person is looking
                    // at Nevada and believes the layer has reported on it —
                    // the fabricated answer this app exists not to give.
                    if (!resp.isSuccessful) throw java.io.IOException("Safecast HTTP ${resp.code}")
                    if (body.isNullOrBlank()) throw java.io.IOException("Safecast returned nothing")
                    json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        val mLat = d("latitude") ?: return@mapNotNull null
                        val mLon = d("longitude") ?: return@mapNotNull null
                        val value = d("value") ?: return@mapNotNull null
                        val captured = Freshness.parse(s("captured_at"))
                        // The date is not decoration. These readings span
                        // more than a decade and the only thing that makes
                        // an old one honest is saying how old, on the
                        // marker, every time. A reading with no date at all
                        // is dropped: unlabelled, it reads as current.
                        if (captured == null) return@mapNotNull null
                        val label = buildString {
                            append("%.1f ${s("unit") ?: "cpm"}".format(value))
                            s("location_name")?.let { append(" · $it") }
                            append(" · measured ")
                            append(Freshness.ago(captured) ?: day.format(Date(captured)))
                        }
                        GeoBlip(mLat, mLon, label, "https://map.safecast.org/")
                    }
                }
            }
            cached = Cached(key, parsed, System.currentTimeMillis())
            parsed
        }
}
