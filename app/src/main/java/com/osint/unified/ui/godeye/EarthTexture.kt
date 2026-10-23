package com.osint.unified.ui.godeye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The Blue Marble, loaded once and kept.
 *
 * Two bundled equirectangular images: NASA's whole-Earth mosaic for the
 * day side and the city-lights composite for the night. Both are public
 * domain, both are downscaled in CI to a size worth holding in memory,
 * and both are optional — if the build could not fetch them the globe
 * falls back to the coastline outlines it drew before, which is why
 * every accessor here returns null rather than throwing.
 *
 * Held as plain int arrays rather than Bitmaps because the renderer
 * reads single texels a million times a frame, and Bitmap.getPixel is
 * a method call into native code each time.
 */
object EarthTexture {

    @Volatile private var day: SphereShading.Texture? = null
    @Volatile private var night: SphereShading.Texture? = null
    @Volatile private var attempted = false

    /** Decoded lazily and off the main thread; safe to call repeatedly. */
    @Synchronized
    fun load(context: Context) {
        if (attempted) return
        attempted = true
        day = decode(context, "earth-day.jpg")
        night = decode(context, "earth-night.jpg")
    }

    fun day(): SphereShading.Texture? = day

    fun night(): SphereShading.Texture? = night

    /** True once there is something better than an outline to draw. */
    fun ready(): Boolean = day != null

    private fun decode(context: Context, name: String): SphereShading.Texture? =
        runCatching {
            context.assets.open(name).use { stream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    ?: return@use null
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                // The Bitmap was a decoding detail. Keeping it alongside
                // the array it was copied into doubles the cost of the
                // one thing in this app that is measured in megabytes.
                bitmap.recycle()
                SphereShading.Texture(pixels, w, h)
            }
        }.getOrNull()

    /**
     * One rendered globe, as something Compose can draw.
     *
     * [out] is reused between frames: at a thousand pixels across this
     * is a four-megabyte allocation, and allocating one per frame while
     * a thumb is moving is how a smooth thing becomes a stuttering one.
     */
    fun image(
        out: IntArray,
        d: Int,
        lat0: Double,
        lon0: Double,
        sunLat: Double,
        sunLon: Double,
        bilinear: Boolean
    ): ImageBitmap? {
        val base = day ?: return null
        SphereShading.render(out, d, base, night, lat0, lon0, sunLat, sunLon, bilinear)
        return runCatching {
            Bitmap.createBitmap(out, d, d, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
}
