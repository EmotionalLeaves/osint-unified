package com.osint.unified.ui.godeye

import android.content.Context
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Building footprints and heights for the 3D buildings, a zoom-14 vector
 * tile at a time.
 *
 * From OpenFreeMap: OpenStreetMap's buildings in the OpenMapTiles schema,
 * keyless, no account. Each tile is downloaded once and kept on the phone
 * with the imagery ([GlobeDisk]); the parsed footprints are held in memory
 * for the few tiles around the view.
 *
 * OpenFreeMap publishes a dated tile set and says which one is current in
 * a small JSON document; that address is looked up once a week and kept,
 * so a stale one is still used when the lookup fails.
 */
internal class BuildingStore(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = LruCache<Long, List<VectorTile.Building>>(MAX_TILES)
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val gate = Semaphore(2)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var template: String? = null

    fun peek(ref: Tiles.Ref): List<VectorTile.Building>? = memory.get(ref.key)

    /** Asks for the zoom-14 tiles in [refs] that are not already in hand. */
    fun request(refs: List<Tiles.Ref>) {
        refs.forEach { ref ->
            if (ref.z != ZOOM || memory.get(ref.key) != null) return@forEach
            if (!pending.add(ref.key)) return@forEach
            scope.launch {
                try {
                    gate.withPermit { load(ref) }?.let {
                        memory.put(ref.key, it)
                        _version.value = _version.value + 1
                    }
                } finally {
                    pending.remove(ref.key)
                }
            }
        }
    }

    private fun load(ref: Tiles.Ref): List<VectorTile.Building>? {
        val file = GlobeDisk.file(app, "buildings", ref.z, ref.x, ref.y, "pbf")
        GlobeDisk.read(file)?.let { kept ->
            runCatching { VectorTile.buildings(kept, ref.z, ref.x, ref.y) }.getOrNull()?.let { return it }
        }
        val url = (template() ?: return null)
            .replace("{z}", ref.z.toString())
            .replace("{x}", ref.x.toString())
            .replace("{y}", ref.y.toString())
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", Http.defaultUserAgent).build()
            Http.tileClient.newCall(request).execute().use { resp ->
                // A tile with nothing in it is a 204 or an empty body, and is
                // an answer — open water, open country. Anything else that
                // is not a success is not.
                if (resp.code == 204) return@use emptyList()
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                val parsed = VectorTile.buildings(bytes, ref.z, ref.x, ref.y)
                GlobeDisk.write(app, file, bytes)
                parsed
            }
        }.getOrNull()
    }

    /** The current tile address, from memory, the phone, or OpenFreeMap. */
    private fun template(): String? {
        template?.let { return it }
        val kept = File(GlobeDisk.root(app), "buildings/tilejson.txt")
        val fresh = kept.isFile && System.currentTimeMillis() - kept.lastModified() < TEMPLATE_MAX_AGE_MS
        if (fresh) {
            kept.readText().trim().takeIf { it.startsWith("https://") }?.let { template = it; return it }
        }
        val looked = runCatching {
            val request = Request.Builder().url(TILEJSON).header("User-Agent", Http.defaultUserAgent).build()
            Http.tileClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                TILES.find(resp.body?.string().orEmpty())?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("https://") && "{z}" in it }
            }
        }.getOrNull()
        if (looked != null) {
            runCatching { kept.parentFile?.mkdirs(); kept.writeText(looked) }
            template = looked
            return looked
        }
        // A week-old address still has tiles behind it; better than none.
        return runCatching { kept.readText().trim() }.getOrNull()
            ?.takeIf { it.startsWith("https://") }
            ?.also { template = it }
    }

    fun clear() {
        scope.cancel()
        memory.evictAll()
    }

    companion object {
        /** The zoom OpenFreeMap carries buildings at, and the only one asked for. */
        const val ZOOM = 14

        /** Credit for the building data: OpenStreetMap, served by OpenFreeMap. */
        const val ATTRIBUTION = "Buildings © OpenStreetMap contributors · OpenFreeMap"

        private const val TILEJSON = "https://tiles.openfreemap.org/planet"
        private val TILES = Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]{1,300})\"")
        private const val MAX_TILES = 12
        private const val TEMPLATE_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
