package com.osint.unified.ui.godeye

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.osint.unified.network.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Satellite tiles for the globe, fetched once and kept in memory.
 *
 * The imagery is Esri's World Imagery, which is keyless and goes to
 * zoom 19 — the same basemap the separate map view used, so the
 * globe gains exactly the detail the map had rather than something
 * that merely resembles it. Esri's terms require the credit to be
 * shown wherever the imagery is, which [ATTRIBUTION] is for and the
 * globe draws.
 *
 * On [Http.feedClient] deliberately: it carries the forty-eight
 * megabyte HTTP cache, so a tile looked at twice in a session —
 * which is most of them, since zooming in and out crosses the same
 * ground — is paid for once, and the data meter counts it against
 * the map feeds rather than against whatever dossier happens to be
 * running at the time.
 *
 * Nothing here blocks the draw. A tile that has not arrived is
 * simply not painted, and the sphere underneath shows through until
 * it does.
 */
internal class TileStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /**
     * Decoded tiles, by packed z/x/y.
     *
     * RGB_565 rather than ARGB_8888: this is photography with no
     * transparency in it, and half the bytes of a tile is half the
     * bytes of the largest thing this screen holds.
     */
    private val memory = object : LruCache<Long, Bitmap>(MAX_TILES) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }

    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val gate = Semaphore(MAX_IN_FLIGHT)

    private val _version = MutableStateFlow(0)

    /**
     * Bumped whenever a tile arrives.
     *
     * The draw reads tiles out of memory and never waits for one, so
     * something has to tell Compose that the picture changed. A
     * counter is enough: what changed is always "there is more
     * imagery than there was".
     */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** A tile if it is already here. Never fetches; safe inside a draw. */
    fun peek(ref: Tiles.Ref): Bitmap? = memory.get(ref.key)

    /**
     * Asks for everything in [refs] that is missing.
     *
     * Called from a side effect when the camera settles, not from
     * the draw: a draw runs on every frame of a pinch, and queueing
     * work from one would spend a phone's data on ground that was
     * under the thumb for a sixtieth of a second.
     */
    fun request(refs: List<Tiles.Ref>) {
        refs.forEach { ref ->
            if (memory.get(ref.key) != null) return@forEach
            if (!pending.add(ref.key)) return@forEach
            scope.launch {
                try {
                    gate.withPermit { fetch(ref) }
                } finally {
                    pending.remove(ref.key)
                }
            }
        }
    }

    private fun fetch(ref: Tiles.Ref) {
        val url = "$BASE${ref.z}/${ref.y}/${ref.x}"
        val bitmap = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.defaultUserAgent)
                .build()
            Http.feedClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bytes = resp.body?.bytes() ?: return@runCatching null
                // A zoom past the imagery's coverage answers with a
                // small "no data" tile rather than a 404, and an
                // empty body decodes to null — both are "not here",
                // which is a blank patch and not a crash.
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }
        }.getOrNull() ?: return
        memory.put(ref.key, bitmap)
        _version.value = _version.value + 1
    }

    /** Dropped when the screen goes away, because this is tens of megabytes. */
    fun clear() {
        memory.evictAll()
    }

    companion object {
        /**
         * Required wherever the imagery appears, and it was being
         * met by an overlay belonging to the view that this
         * replaced.
         */
        const val ATTRIBUTION =
            "Imagery © Esri, Maxar, Earthstar Geographics · Data © OpenStreetMap contributors"

        /** z/y/x with no extension, which is Esri's path order and not the usual one. */
        private const val BASE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"

        /**
         * A hundred and ninety-two tiles of RGB_565 is about
         * twenty-four megabytes — two screenfuls of a tall phone, so
         * panning back the way you came costs nothing, and bounded
         * near the whole-Earth mosaic this sits beside.
         */
        private const val MAX_TILES = 192

        /** Enough to fill a screen quickly, few enough to stay polite. */
        private const val MAX_IN_FLIGHT = 6
    }
}
