package com.osint.unified.data

import com.osint.unified.network.Http
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Global seismic-station network (IRIS FDSN station metadata, keyless): active
 * station positions, as the STATIONS layer.
 */
/*
 * Without `endafter`, IRIS returns every station it has ever listed,
 * including ones decommissioned decades ago — so the map showed
 * instruments that are not there.
 */
class SeismicStationDataSource(
    private val cache: DiskCache? = null,
    private val client: OkHttpClient = Http.feedClient
) {

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        try {
            // Fourteen megabytes of essentially static infrastructure — a
            // seismic station does not move. Cached on disk for a week, and
            // a stale copy is used if IRIS is unreachable.
            val body = cache?.read(CACHE_KEY, WEEK_MS)
                ?: download()?.also { cache?.write(CACHE_KEY, it) }
                ?: cache?.readStale(CACHE_KEY)
                ?: throw IOException("IRIS answered in an unexpected shape")
            run {
                val out = ArrayList<GeoBlip>()
                body.lineSequence().forEach { line ->
                    if (out.size >= 8000) return@forEach
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    // Net|Sta|Lat|Lon|Elev|SiteName|Start|End
                    val c = line.split("|")
                    if (c.size < 4) return@forEach
                    val lat = c[2].trim().toDoubleOrNull() ?: return@forEach
                    val lon = c[3].trim().toDoubleOrNull() ?: return@forEach
                    val site = c.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() }
                    val label = "${c[0].trim()}.${c[1].trim()}" +
                        if (site != null) " · ${site.take(50)}" else ""
                    out.add(
                        GeoBlip(
                            lat, lon, label,
                            "https://ds.iris.edu/mda/${c[0].trim()}/${c[1].trim()}/"
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            // An empty list is a claim that there is nothing out
            // there, which the pills and the world brief repeat as
            // fact. A failure is not that claim.
            throw IOException("IRIS unavailable: ${e.message}", e)
        }
    }

    private fun download(): String? = runCatching {
        val request = Request.Builder()
            .url("https://service.iris.edu/fdsnws/station/1/query?format=text&level=station&nodata=404&endafter=2026-01-01")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .build()
        Http.bulkClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private companion object {
        const val CACHE_KEY = "iris-stations"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
