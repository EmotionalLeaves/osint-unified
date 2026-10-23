package com.osint.unified.ui.godeye

import android.app.ActivityManager
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
        // The detailed mosaic is four times the pixels and therefore
        // four times the heap — thirty-odd megabytes, which is fine on
        // a current phone and is not fine on a cheap one. So it is only
        // asked for where there is room, and a failure to decode it
        // falls through to the small one rather than to nothing.
        if (roomy(context)) day = decode(context, HI_DAY)
        if (day == null) day = decode(context, DAY)
        night = decode(context, NIGHT)
    }

    /**
     * Whether this device can afford the detailed mosaic.
     *
     * memoryClass is the heap an ordinary app is guaranteed, in
     * megabytes. The large mosaic needs 32 MB of it and the renderer
     * needs a few more for the frame it is building, so this asks for
     * comfortably more than the sum rather than exactly it.
     */
    private fun roomy(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.memoryClass >= 160
    }.getOrDefault(false)

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

    private const val DAY = "earth-day.jpg"

    /** The same mosaic at twice the linear resolution. Optional: a
     *  build that could not fetch it simply does not ship it. */
    private const val HI_DAY = "earth-day-hi.jpg"

    private const val NIGHT = "earth-night.jpg"

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
