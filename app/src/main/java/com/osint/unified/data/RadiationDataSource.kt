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
 * Crowd-measured background radiation from Safecast, around wherever you
 * are looking.
 *
 * Safecast grew out of Fukushima: volunteers with Geiger counters, because
 * the official numbers were not available fast enough or at enough places.
 * It is the only open, global, keyless radiation dataset there is, and on a
 * globe it answers a question no other layer can.
 *
 * Two things make it honest rather than decorative. It is queried around
 * the viewport, because the dataset is tens of millions of points and a
 * global fetch is meaningless. And it is filtered by capture date — the API
 * ignores sort order and will happily return a reading from 2013 first, so
 * without `captured_after` this layer would plot thirteen-year-old
 * measurements as current.
 */
class RadiationDataSource(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** How far back a reading may have been taken and still be plotted. */
    private val windowDays = 120

    @Volatile private var lastKey: String = ""
    @Volatile private var cached: List<GeoBlip> = emptyList()

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
            if (key == lastKey && cached.isNotEmpty()) return@withContext cached

            val after = day.format(Date(System.currentTimeMillis() - windowDays * 86_400_000L))
            val url = "https://api.safecast.org/measurements.json" +
                "?latitude=$lat&longitude=$lon&distance=$radiusM" +
                "&captured_after=$after"
            val parsed = try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OSINT-Unified/1.0 (research)")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext cached
                    json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        val mLat = d("latitude") ?: return@mapNotNull null
                        val mLon = d("longitude") ?: return@mapNotNull null
                        val value = d("value") ?: return@mapNotNull null
                        val captured = Freshness.parse(s("captured_at"))
                        // The window is enforced here as well as in the
                        // query: the parameter is the API's promise, this is
                        // the app's.
                        if (!Freshness.within(captured, windowDays * 24)) return@mapNotNull null
                        val label = buildString {
                            append("%.1f ${s("unit") ?: "cpm"}".format(value))
                            s("location_name")?.let { append(" · $it") }
                            Freshness.ago(captured)?.let { append(" · $it") }
                        }
                        GeoBlip(mLat, mLon, label, "https://map.safecast.org/")
                    }
                }
            } catch (e: Exception) {
                return@withContext cached
            }
            lastKey = key
            cached = parsed
            parsed
        }
}
