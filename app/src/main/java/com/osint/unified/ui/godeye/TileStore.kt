package com.osint.unified.ui.godeye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.osint.unified.network.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Imagery for the globe, at every zoom.
 *
 * Two sources, one scheme. From the whole Earth down to continent
 * scale (slippy zoom 0–5) the tiles are NASA's Blue Marble, bundled in
 * the APK by CI from the public-domain GIBS service — they cost no
 * data, need no network and arrive at once. Below that they are Esri's
 * World Imagery, fetched live, keyless, to zoom 19. Esri's terms
 * require the credit wherever its imagery is shown, which
 * [ESRI_ATTRIBUTION] is for.
 *
 * This replaced a single 4096-pixel photograph of the planet that was
 * drawn at every zoom down to a country across, which was at its limit
 * with the whole Earth in view and two to four times magnified as soon
 * as anyone zoomed: "very blurry at all levels of zoom".
 *
 * Nothing here blocks the draw. A tile that has not arrived is not
 * painted, and its parent — already here — shows through.
 */
internal class TileStore(context: Context) {

    private val app = context.applicationContext
    private val assets = app.assets
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Decoded tiles, by packed z/x/y and layer.
     *
     * RGB_565: photography with no transparency, at half the bytes of
     * ARGB. 400 tiles (more on a larger screen) is about 50 MB of bitmap memory, which on Android 8
     * and later lives outside the Java heap — a full high-resolution
     * screen at three levels plus room to pan back the way you came.
     */
    private val memory = object : LruCache<Long, Bitmap>(capacity(context, detail(context))) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }

    private val pending = ConcurrentHashMap.newKeySet<Long>()

    /**
     * What the view wants now.
     *
     * Requests queue behind a small gate, and a pinch from continent to
     * street level used to queue every level it passed through — some
     * eight hundred fetches, all downloaded, the final view's tiles
     * waiting behind them. A queued fetch now checks it is still wanted
     * when its turn comes, and is dropped if the view has moved on.
     */
    @Volatile private var wanted: Set<Long> = emptySet()
    private val gate = Semaphore(MAX_IN_FLIGHT)

    private val _version = MutableStateFlow(0)

    /** Bumped whenever a tile arrives, so the canvas knows to repaint. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile var usedLive: Boolean = false
        private set

    fun peek(ref: Tiles.Ref, layer: Layer = Layer.DAY): Bitmap? = memory.get(key(ref, layer))

    /**
     * Asks for what is missing in [refs].
     *
     * Bundled tiles are read whenever asked, gesture or not: they cost
     * nothing. Live ones only when [network] is true — the caller passes
     * false while a thumb is moving the globe, so ground that was under
     * it for a sixtieth of a second is not paid for.
     */
    fun request(refs: List<Tiles.Ref>, layer: Layer = Layer.DAY, network: Boolean = true) {
        val live = refs.filter { it.z > Tiles.BUNDLED_MAX_ZOOM }
        if (network && live.isNotEmpty()) wanted = live.mapTo(HashSet()) { key(it, layer) }
        refs.forEach { ref ->
            val k = key(ref, layer)
            if (memory.get(k) != null) return@forEach
            val bundled = ref.z <= Tiles.BUNDLED_MAX_ZOOM
            if (!bundled && !network) return@forEach
            if (!pending.add(k)) return@forEach
            scope.launch {
                try {
                    val bitmap = if (bundled) {
                        bundled(ref, layer) ?: if (network) gate.withPermit { live(ref, layer) } else null
                    } else {
                        gate.withPermit {
                            // Superseded while it waited: the view is elsewhere.
                            if (k in wanted) live(ref, layer) else null
                        }
                    }
                    if (bitmap != null) {
                        // Drawn smaller than fetched whenever the quality is
                        // above the screen's own resolution; mipmaps keep the
                        // downscale smooth rather than shimmering as it pans.
                        bitmap.setHasMipMap(true)
                        memory.put(k, bitmap)
                        _version.value = _version.value + 1
                    }
                } finally {
                    pending.remove(k)
                }
            }
        }
    }

    private fun bundled(ref: Tiles.Ref, layer: Layer): Bitmap? = runCatching {
        assets.open("tiles/${layer.dir}/${ref.z}/${ref.x}_${ref.y}.jpg").use { input ->
            BitmapFactory.decodeStream(input, null, OPTIONS)
        }
    }.getOrNull()

    /**
     * From the phone if it has been fetched before; otherwise fetched,
     * and kept. See [GlobeDisk].
     */
    private fun live(ref: Tiles.Ref, layer: Layer): Bitmap? {
        val file = GlobeDisk.file(app, "imagery-${layer.dir}", ref.z, ref.x, ref.y, "jpg")
        GlobeDisk.read(file)?.let { kept ->
            BitmapFactory.decodeByteArray(kept, 0, kept.size, OPTIONS)?.let { return it }
            // Kept but unreadable: fetched again below and overwritten.
        }
        val url = layer.liveUrl(ref)
        val bitmap = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.defaultUserAgent)
                .build()
            Http.tileClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bytes = resp.body?.bytes() ?: return@runCatching null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, OPTIONS)
                    ?: return@runCatching null
                GlobeDisk.write(app, file, bytes)
                decoded
            }
        }.getOrNull()
        if (bitmap != null && ref.z > Tiles.BUNDLED_MAX_ZOOM && layer == Layer.DAY) usedLive = true
        return bitmap
    }

    /** Dropped when the screen goes away: tens of megabytes, and the queue with it. */
    fun clear() {
        wanted = emptySet()
        scope.cancel()
        memory.evictAll()
    }

    enum class Layer(val dir: String) {
        DAY("day") {
            override fun liveUrl(ref: Tiles.Ref): String =
                if (ref.z <= Tiles.BUNDLED_MAX_ZOOM) {
                    "$GIBS/BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/" +
                        "${ref.z}/${ref.y}/${ref.x}.jpeg"
                } else {
                    // z/y/x with no extension: Esri's path order. Two of
                    // Esri's hostnames serve the same tiles; alternating
                    // between them doubles how many arrive at once.
                    (if ((ref.x + ref.y) % 2 == 0) ESRI else ESRI_ALT) + "${ref.z}/${ref.y}/${ref.x}"
                }
        },
        NIGHT("night") {
            override fun liveUrl(ref: Tiles.Ref): String =
                "$GIBS/VIIRS_CityLights_2012/default/GoogleMapsCompatible_Level8/" +
                    "${ref.z.coerceAtMost(8)}/${ref.y}/${ref.x}.jpeg"
        };

        abstract fun liveUrl(ref: Tiles.Ref): String
    }

    // Bit 63: the packed key uses bits 58-62 for the zoom, and zoom 16
    // and above reach bit 62.
    private fun key(ref: Tiles.Ref, layer: Layer): Long =
        ref.key xor (layer.ordinal.toLong() shl 63)

    companion object {
        /** Required wherever Esri's imagery appears. */
        const val ESRI_ATTRIBUTION =
            "Imagery © Esri, Maxar, Earthstar Geographics · Data © OpenStreetMap contributors"

        /** NASA's imagery is public domain; the credit is courtesy. */
        const val NASA_ATTRIBUTION = "Imagery: NASA Blue Marble, VIIRS"

        private const val ESRI =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
        private const val ESRI_ALT =
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
        private const val GIBS = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best"

        private const val MAX_TILES = 400

        /**
         * Scaled with the screen, like the per-view cap in [Tiles]: a
         * 1440 x 3120 view may ask for 300 live tiles, and with the level
         * beneath and the bundled base that is more than 400 — the memory
         * would evict tiles still on screen and fetch them again.
         */
        private fun capacity(context: Context, detail: Float): Int {
            val metrics = context.resources.displayMetrics
            val scale = metrics.widthPixels.toDouble() * metrics.heightPixels * detail * detail /
                (1080.0 * 2340.0)
            return (MAX_TILES * scale).toInt().coerceIn(MAX_TILES, MAX_TILES_LARGE)
        }

        /**
         * How much finer than the screen the imagery is chosen: the
         * quality setting's short side over the screen's, never below
         * the setting's own floor. 1.33 for QHD on a 1080p phone; under
         * 1 for Data saver.
         */
        fun detail(context: Context): Float {
            val metrics = context.resources.displayMetrics
            val short = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            return com.osint.unified.data.AppSettings(context).globeTargetShortSide.toFloat() / short
        }

        private const val MAX_TILES_LARGE = 720
        // Matched to what Http lets through to the tile hosts: eight to
        // each of Esri's two names.
        private const val MAX_IN_FLIGHT = 14

        private val OPTIONS = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    }
}
