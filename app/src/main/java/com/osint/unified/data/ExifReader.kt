package com.osint.unified.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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

    /**
     * The same reading, for a photograph already on the phone.
     *
     * Reading EXIF used to require a URL, so examining a picture meant
     * uploading it somewhere first — leaving the app and handing the
     * photograph to a third party to find out whether it had GPS in it,
     * which is a strange thing for a tool like this to ask of anyone.
     * Nothing here touches the network.
     */
    suspend fun fromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
                digest.update(buffer, 0, n)
            }
        } ?: throw java.io.FileNotFoundException("that image could not be opened")

        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
            ?: throw java.io.FileNotFoundException("that image could not be read")

        buildString {
            append(describe(exif))
            append("\nBytes: ")
            append(if (size > 0) size else bytes)
            append("\nSHA-256: ")
            append(digest.digest().joinToString("") { "%02x".format(it) })
            append("\n\nRead on this phone. The picture was not uploaded anywhere.")
        }
    }

    /** The tags worth reading, in the order they are worth reading them. */
    private fun describe(exif: ExifInterface): String {
        val sb = StringBuilder()
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            sb.append("GPS: ${latLong[0]}, ${latLong[1]}\n")
            sb.append("Coordinates: ${latLong[0]},${latLong[1]}\n")
        } else {
            sb.append("GPS: (none)\n")
        }
        mapOf(
            "Make" to ExifInterface.TAG_MAKE,
            "Model" to ExifInterface.TAG_MODEL,
            "Date" to ExifInterface.TAG_DATETIME,
            "DateOriginal" to ExifInterface.TAG_DATETIME_ORIGINAL,
            "Software" to ExifInterface.TAG_SOFTWARE,
            "Lens" to ExifInterface.TAG_LENS_MODEL,
            "Artist" to ExifInterface.TAG_ARTIST,
            "Copyright" to ExifInterface.TAG_COPYRIGHT,
            "Orientation" to ExifInterface.TAG_ORIENTATION
        ).forEach { (label, tag) ->
            exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { sb.append("$label: $it\n") }
        }
        val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        if (w > 0 && h > 0) sb.append("Dimensions: ${w}x$h\n")
        return sb.toString().ifBlank { "No EXIF metadata found." }
    }

    suspend fun fromUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                // Thrown, not returned. A string beginning "HTTP 404" was
                // stored as a successful result, counted as a finding and
                // shown with a green dot — the app reporting that it had
                // read the metadata of a page that does not exist.
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching the image")
                val body = resp.body ?: throw IOException("the server sent no body")
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
                    if (written == 0L) throw IOException("the server sent an empty image")

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
            // Rethrown so the caller files this as a failure. Returning
            // the words "Error: ..." made every failure a success with an
            // apology in it.
            throw e
        }
    }
}
