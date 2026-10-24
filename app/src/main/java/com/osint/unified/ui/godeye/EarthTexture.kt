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
    // The same two images, kept as Bitmaps instead, for the GPU path.
    //
    // Only one form is ever held: the shader needs a Bitmap to sample
    // and the CPU renderer needs an int array to index, and keeping
    // both would double the one thing in this app measured in tens of
    // megabytes.
    @Volatile private var dayMap: Bitmap? = null
    @Volatile private var nightMap: Bitmap? = null
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
        if (GlobeShader.supported) {
            if (roomy(context)) dayMap = bitmap(context, HI_DAY)
            if (dayMap == null) dayMap = bitmap(context, DAY)
            nightMap = bitmap(context, NIGHT)
            // A build that shipped no imagery leaves both null, and
            // the globe falls back to coastline outlines exactly as
            // it did before there was a shader.
            if (dayMap != null) return
        }
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

    fun dayMap(): Bitmap? = dayMap

    fun nightMap(): Bitmap? = nightMap

    /** True once there is something better than an outline to draw. */
    fun ready(): Boolean = day != null || dayMap != null

    /** Whether the CPU renderer has a texture to work from. */
    fun hasCpuTexture(): Boolean = day != null

    /**
     * Builds the CPU texture, if it is not there already.
     *
     * [load] returns as soon as the shader path has its Bitmaps, so
     * on Android 13 and up the IntArray the CPU renderer indexes is
     * never built. That is right while the shader works — and when
     * `GlobeShader.bind` fails, which it swallows, `gpuSphere` is
     * null while `ready()` is still true: the render pass then
     * relaunched on every camera move, allocated up to a 1400x1400
     * IntArray, and returned null from `image()` every time. Seven
     * and a half megabytes per camera move to produce nothing.
     *
     * Called only on that path, so the ordinary one pays nothing.
     */
    @Synchronized
    fun loadCpuFallback(context: Context) {
        if (day != null || cpuAttempted) return
        cpuAttempted = true
        // The Bitmaps go first.
        //
        // This file's own comment says only one form is ever held,
        // because the large mosaic is the one thing in this app
        // measured in tens of megabytes and keeping both doubles it.
        // Reaching here means the shader bind failed, so the Bitmap
        // form is dead weight — and this is exactly the hardware
        // where the headroom is worst, which is why the bind failed.
        dayMap?.recycle()
        dayMap = null
        nightMap?.recycle()
        nightMap = null
        if (roomy(context)) day = decode(context, HI_DAY)
        if (day == null) day = decode(context, DAY)
        if (night == null) night = decode(context, NIGHT)
    }

    @Volatile private var cpuAttempted = false

    /** True when the globe can be drawn by the GPU this frame. */
    fun shaded(): Boolean = GlobeShader.supported && dayMap != null

    /** The mosaic as a Bitmap, which is what a BitmapShader samples. */
    private fun bitmap(context: Context, name: String): Bitmap? =
        runCatching {
            context.assets.open(name).use { stream ->
                val options = BitmapFactory.Options().apply {
                    // Not HARDWARE: a hardware bitmap cannot be read
                    // back, and the fallback path may still need to.
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()

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
        return runCatching { surface(d).also { it.setPixels(out, 0, d, 0, 0, d, d) } }
            .getOrNull()?.asImageBitmap()
    }

    /**
     * Two Bitmaps, used in turn.
     *
     * The comment above [image] explains why the pixel array is
     * reused, and then the old code called `Bitmap.createBitmap`,
     * which copies — so a quarter-size drag frame allocated half a
     * megabyte sixty times a second and a full frame up to eight,
     * none of it ever recycled. The array's reuse saved nothing.
     *
     * Two rather than one because the frame just handed to Compose
     * may still be on screen while the next is being painted; writing
     * into it would show half of one globe and half of another.
     */
    private val surfaces = arrayOfNulls<Bitmap>(2)
    private var nextSurface = 0

    private fun surface(d: Int): Bitmap {
        val slot = nextSurface
        nextSurface = (nextSurface + 1) % surfaces.size
        val held = surfaces[slot]
        if (held != null && !held.isRecycled && held.width == d && held.height == d) {
            return held
        }
        held?.recycle()
        return Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888).also { surfaces[slot] = it }
    }
}
