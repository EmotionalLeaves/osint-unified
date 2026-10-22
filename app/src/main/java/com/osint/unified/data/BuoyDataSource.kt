package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * NOAA NDBC ocean buoy network (keyless): latest observations for every active
 * buoy worldwide. We plot each buoy's position from the fixed-column text feed.
 */
class BuoyDataSource(private val client: OkHttpClient = Http.client) {

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val out = ArrayList<GeoBlip>()
                body.lineSequence().forEach { line ->
                    if (out.size >= 700) return@forEach
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 3) return@forEach
                    val lat = cols[1].toDoubleOrNull() ?: return@forEach
                    val lon = cols[2].toDoubleOrNull() ?: return@forEach
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
                    out.add(GeoBlip(lat, lon, "Buoy ${cols[0]}"))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
