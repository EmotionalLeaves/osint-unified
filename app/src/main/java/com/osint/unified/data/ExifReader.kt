package com.osint.unified.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.osint.unified.domain.ContentCredentials
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

        /** The front of the file: a manifest and a text chunk both
         *  live near the start, and the rest is pixels. */
        const val CREDENTIAL_BYTES = 512 * 1024
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

                    // The thumbnail nobody re-rendered.
                    //
                    // A JPEG usually carries a small copy of itself, and
                    // most editors that crop or retouch the main image
                    // leave that copy alone — so the embedded thumbnail
                    // is frequently the picture before the edit. It is a
                    // classic forensic tell and this file already had an
                    // ExifInterface open over the bytes; it was one call
                    // away and nobody made it.
                    if (exif.hasThumbnail()) {
                        val t = exif.getThumbnailBytes()
                        sb.append("\nEmbedded thumbnail: yes")
                        t?.let { sb.append(" (${it.size} bytes)") }
                        val thumb = exif.thumbnailBitmap
                        val tw = thumb?.width ?: 0
                        val th = thumb?.height ?: 0
                        if (tw > 0 && th > 0) {
                            sb.append(", ${tw}x$th")
                            // A thumbnail whose shape disagrees with the
                            // image's is the interesting case.
                            if (w > 0 && h > 0) {
                                val full = w.toFloat() / h
                                val small = tw.toFloat() / th
                                if (kotlin.math.abs(full - small) > 0.02f) {
                                    sb.append("\n⚠ The thumbnail's proportions do not ")
                                    sb.append("match the image's. Editors that crop ")
                                    sb.append("often leave the thumbnail alone, so this ")
                                    sb.append("one may show the picture before the crop.")
                                }
                            }
                        }
                        sb.append("\n")
                    }

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
    /**
     * What the file says about who or what made it.
     *
     * Separate from the EXIF read, because it answers a different
     * question and the answer is worth its own card: EXIF says what
     * camera took a photograph and says nothing at all about a
     * picture that no camera took.
     *
     * Only the front of the file is read. A C2PA manifest sits in an
     * APP11 segment or a PNG chunk near the start, a generator's text
     * chunk likewise, and pulling twenty megabytes down a phone
     * connection to look at a header is the kind of cost this app
     * exists not to impose.
     */
    suspend fun credentialsFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)
                ?.use { readAtMost(it, CREDENTIAL_BYTES) }
        }.getOrNull()
            ?: return@withContext "Could not read that image."
        ContentCredentials.describe(ContentCredentials.read(bytes))
    }

    suspend fun credentialsFromUrl(url: String): String = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            return@withContext "Give the image's URL."
        }
        try {
            // Range, so the host sends the front of the file and not
            // all of it. A host that ignores the header costs the read
            // below its cap instead, which is the same bound.
            val request = Request.Builder().url(target)
                .header("Range", "bytes=0-${CREDENTIAL_BYTES - 1}")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("HTTP ${resp.code} fetching the image")
                }
                val body = resp.body ?: throw IOException("the server sent no body")
                val bytes = body.byteStream().use { readAtMost(it, CREDENTIAL_BYTES) }
                if (bytes.isEmpty()) throw IOException("the server sent an empty image")
                ContentCredentials.describe(ContentCredentials.read(bytes))
            }
        } catch (e: IOException) {
            "Could not read $target: ${e.message}"
        }
    }

    /**
     * The first [limit] bytes, read by hand.
     *
     * Not InputStream.readNBytes: that is API 33 and this app's floor
     * is 26, so it would compile, pass every check here, and throw
     * NoSuchMethodError on most of the phones that run this.
     */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(32 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

}
