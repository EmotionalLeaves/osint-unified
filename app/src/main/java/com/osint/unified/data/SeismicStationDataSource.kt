package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Global seismic-station network (IRIS FDSN station metadata, keyless): active
 * station positions, as the STATIONS layer.
 */
class SeismicStationDataSource(private val client: OkHttpClient = Http.client) {

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://service.iris.edu/fdsnws/station/1/query?format=text&level=station&nodata=404")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()
                val out = ArrayList<GeoBlip>()
                body.lineSequence().forEach { line ->
                    if (out.size >= 8000) return@forEach
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    // Net|Sta|Lat|Lon|Elev|SiteName|Start|End
                    val c = line.split("|")
                    if (c.size < 4) return@forEach
                    val lat = c[2].trim().toDoubleOrNull() ?: return@forEach
                    val lon = c[3].trim().toDoubleOrNull() ?: return@forEach
                    out.add(GeoBlip(lat, lon, "${c[0].trim()}.${c[1].trim()}"))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
