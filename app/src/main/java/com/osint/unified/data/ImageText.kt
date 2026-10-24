package com.osint.unified.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The words in a photograph, read on the device.
 *
 * This is the keyless half of image geolocation. GeoSpy, Picarta and
 * GeoInfer all want an account, and the standing rule here is no
 * keys and no accounts — but most of how a person actually
 * geolocates a picture does not need a model that has seen the
 * whole planet. It needs the text in the frame: a shop name, a
 * street sign, a phone number on a van, a licence plate, a bus
 * route, a language, an alphabet. Each of those is a string, and
 * this app is full of sources that take strings.
 *
 * ML Kit's recognizer ships inside the APK, runs with the radio off
 * and sends nothing anywhere, which matters more here than usual:
 * the picture may be the one thing in a case that must not leave
 * the phone.
 *
 * What it does not do is guess a location. It reads, sorts what it
 * read into the kinds of thing the rest of the app can look up, and
 * hands those over. The guess stays with the person.
 */
class ImageText(
    private val appContext: Context,
    private val client: OkHttpClient = Http.client
) {

    /** One line of text found in the picture, and where it was. */
    data class Line(val text: String, val fromTop: Float)

    /** What a read turned up. */
    data class Read(
        val lines: List<Line>,
        val widthPx: Int,
        val heightPx: Int
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Reads a picture already on the phone. Nothing touches the
     * network.
     *
     * Sampled down to the same ceiling [fromUrl] uses, and for the
     * same reason its comment gives: a forty-megapixel photograph
     * is four hundred and eighty megabytes as a bitmap, which is
     * the process on most phones. `InputImage.fromFilePath` decodes
     * at full resolution, and a gallery picture is exactly where a
     * forty-megapixel file comes from.
     */
    suspend fun fromUri(uri: Uri): Read = withContext(Dispatchers.IO) {
        // The fallback is a full-resolution decode, so it must not
        // be what an out-of-memory falls through to.
        //
        // `runCatching` catches Throwable, OutOfMemoryError
        // included, and `fromFilePath` then decodes the same
        // picture at its full size — reaching for more memory than
        // the attempt that just ran out of it, in the handler for
        // running out of it.
        val bitmap = try {
            sampled(uri)
        } catch (oom: OutOfMemoryError) {
            throw java.io.IOException("that image is too large to read on this phone", oom)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext recognise(InputImage.fromFilePath(appContext, uri))
        recognise(InputImage.fromBitmap(bitmap, 0))
    }

    /** The picture, decoded no larger than [MAX_PIXELS]. */
    private fun sampled(uri: Uri): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // toLong on one side.
        //
        // The product is Int, and an image declaring 65535 by 65535
        // overflows it to a negative number — so the loop never
        // runs, inSampleSize stays 1, and the decode asks for
        // seventeen gigabytes: exactly the out-of-memory this
        // function exists to prevent, triggered by the header of a
        // file somebody else chose. The dimensions are read before
        // any pixels are, so a lying header costs nothing to write.
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS) {
            sample *= 2
        }
        return appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }

    /**
     * Reads a picture at a URL.
     *
     * Fetching it is an active step — the host sees the request —
     * but when the observable IS the image, that file is the thing
     * the person handed over and reading it is the whole question.
     */
    suspend fun fromUrl(url: String): Read = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching the image")
            val body = resp.body ?: throw IOException("the server sent no body")
            val file = File.createTempFile("ocr", ".img", appContext.cacheDir)
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
                                throw IOException(
                                    "refusing to read more than " +
                                        "${MAX_IMAGE_BYTES / (1024 * 1024)} MB from this URL"
                                )
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                if (written == 0L) throw IOException("the server sent an empty image")
                // Decoded with a ceiling on the pixel count. A
                // forty-megapixel photograph is four hundred and
                // eighty megabytes as a bitmap, which is the process
                // on most phones, and the recognizer gains nothing
                // from the extra detail.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                // The same Int overflow as the sibling above: a
                // header claiming 65535 by 65535 makes the product
                // negative, the loop never runs, and the decode asks
                // for seventeen gigabytes.
                while (
                    bounds.outWidth.toLong() * bounds.outHeight /
                        (sample.toLong() * sample) > MAX_PIXELS
                ) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: throw IOException("that file is not an image this device can decode")
                recognise(InputImage.fromBitmap(bitmap, 0))
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun recognise(image: InputImage): Read =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val height = image.height.takeIf { it > 0 } ?: 1
                    val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val text = line.text.trim()
                        if (text.isBlank()) null
                        else Line(
                            text,
                            (line.boundingBox?.centerY()?.toFloat() ?: 0f) / height
                        )
                    }.sortedBy { it.fromTop }
                    runCatching { recognizer.close() }
                    cont.resume(Read(lines, image.width, image.height))
                }
                .addOnFailureListener { e ->
                    runCatching { recognizer.close() }
                    cont.resumeWithException(e)
                }
            cont.invokeOnCancellation { runCatching { recognizer.close() } }
        }

    private companion object {
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024

        /** About twelve megapixels, which is more than the recognizer
         *  can use and far less than a phone can hold. */
        const val MAX_PIXELS = 12_000_000
    }
}
