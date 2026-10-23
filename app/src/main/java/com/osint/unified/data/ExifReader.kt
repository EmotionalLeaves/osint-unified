package com.osint.unified.data

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads an image and extracts its EXIF metadata (GPS, camera, times).
 *
 * Three things this has to get right. The fetch happens from the
 * investigator's own address and the target's server logs it, so it is an
 * active source and declares itself one. The response is streamed with a
 * cap, because `bytes()` on an endless response is an out-of-memory crash
 * waiting for a hostile URL. And each read gets its own temporary file,
 * deleted afterwards: a single shared name was raced by two callers and
 * left the image sitting in the cache.
 */
class ExifReader(
    private val appContext: Context,
    private val client: OkHttpClient = Http.client
) {
    private companion object {
        /** Larger than any photograph worth reading EXIF from, and small
         *  enough that an endless response cannot exhaust memory. */
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    }

    suspend fun fromUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val body = resp.body ?: return@withContext "Empty response"
                val file = File.createTempFile("exif", ".img", appContext.cacheDir)
                try {
                    var written = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > MAX_IMAGE_BYTES) {
                                    return@withContext "Refusing to read more than " +
                                        "${MAX_IMAGE_BYTES / (1024 * 1024)} MB from this URL."
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    if (written == 0L) return@withContext "Empty response"

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
                        exif.getAttribute(tag)?.takeIf { it.isNotBlank() }
                            ?.let { sb.append("$label: $it\n") }
                    }

                    val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (w > 0 && h > 0) sb.append("Dimensions: ${w}x$h\n")

                    sb.toString().ifBlank { "No EXIF metadata found." }
                } finally {
                    runCatching { file.delete() }
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
