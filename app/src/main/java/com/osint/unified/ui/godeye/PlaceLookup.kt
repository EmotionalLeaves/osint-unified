package com.osint.unified.ui.godeye

import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What is at a tapped spot, in words: OpenStreetMap's Nominatim reverse
 * geocoder, keyless.
 *
 * One request per tap, and remembered for the session by rounded
 * position, so tapping the same building twice asks once. Nominatim's
 * policy is one request a second with an identifying agent; a tap is
 * well inside that.
 */
internal object PlaceLookup {

    /** A name if the spot has one, the address, and what kind of place it is. */
    data class Place(val name: String?, val address: String?, val kind: String?) {
        val isEmpty: Boolean get() = name == null && address == null
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val seen = ConcurrentHashMap<String, Place>()

    /**
     * The place at [lat], [lon], at a detail suited to [tileZoom]: a
     * street address close in, a town or country further out.
     *
     * Throws when Nominatim cannot be asked. Open water comes back as an
     * empty [Place], which is an answer.
     */
    suspend fun reverse(lat: Double, lon: Double, tileZoom: Int): Place = withContext(Dispatchers.IO) {
        val detail = tileZoom.coerceIn(3, 18)
        val key = "%.5f,%.5f,%d".format(Locale.US, lat, lon, detail)
        seen[key]?.let { return@withContext it }
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
            "&lat=%.6f&lon=%.6f&zoom=%d&addressdetails=0".format(Locale.US, lat, lon, detail)
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .build()
        val body = Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Nominatim HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("Nominatim sent nothing")
        }
        val place = read(body)
        if (seen.size > MAX_REMEMBERED) seen.clear()
        seen[key] = place
        place
    }

    /** Nominatim's answer, read. `{"error": …}` is how it says "nothing here". */
    fun read(body: String): Place {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IOException("Nominatim's answer could not be read") }
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (o["error"] != null) return Place(null, null, null)
        val name = s("name")
        val address = s("display_name")?.let { full ->
            // The name leads the full address; said once is enough.
            if (name != null && full.startsWith("$name, ")) full.removePrefix("$name, ") else full
        }
        val kind = listOfNotNull(s("type"), s("category"))
            .firstOrNull { it != "yes" }
            ?.replace('_', ' ')
        return Place(name, address, kind)
    }

    private const val MAX_REMEMBERED = 200
}
