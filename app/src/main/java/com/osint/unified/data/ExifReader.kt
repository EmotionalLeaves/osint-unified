package com.osint.unified.data

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Downloads an image and extracts its EXIF metadata (GPS, camera, timestamps). */
class ExifReader(
    private val appContext: Context,
    private val client: OkHttpClient = Http.client
) {
    suspend fun fromUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val bytes = resp.body?.bytes() ?: return@withContext "Empty response"
                val file = File(appContext.cacheDir, "exif_tmp.img")
                file.writeBytes(bytes)

                val exif = ExifInterface(file.absolutePath)
                val sb = StringBuilder()

                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    sb.append("GPS: ${latLong[0]}, ${latLong[1]}\n")
                    sb.append("Map: https://www.google.com/maps?q=${latLong[0]},${latLong[1]}\n")
                } else {
                    sb.append("GPS: (none)\n")
                }

                mapOf(
                    "Make" to ExifInterface.TAG_MAKE,
                    "Model" to ExifInterface.TAG_MODEL,
                    "Date" to ExifInterface.TAG_DATETIME,
                    "Software" to ExifInterface.TAG_SOFTWARE,
                    "Lens" to ExifInterface.TAG_LENS_MODEL
                ).forEach { (label, tag) ->
                    exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { sb.append("$label: $it\n") }
                }

                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) sb.append("Dimensions: ${w}x$h\n")

                sb.toString().ifBlank { "No EXIF metadata found." }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
