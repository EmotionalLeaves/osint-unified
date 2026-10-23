package com.osint.unified.ui.godeye

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The outline of every landmass, as numbers.
 *
 * Bundled rather than fetched, and drawn rather than textured: a sphere
 * cannot be covered with flat map tiles without reprojecting every one
 * of them, and the reason to look at a globe is to see the whole Earth
 * at once, which is the one thing tiles are the wrong shape for.
 *
 * Natural Earth at 110 million to one — the coarsest they publish, and
 * still more detail than a globe a few hundred pixels across can show.
 * Stored as flat alternating longitude and latitude so that reading it
 * is a loop over numbers rather than an object allocated per point;
 * there are about ten thousand of them and they are read on every frame.
 */
object Coastlines {

    @Volatile private var cached: List<DoubleArray>? = null

    /** Every ring, biggest first. Empty when the file is not bundled. */
    fun rings(context: Context): List<DoubleArray> {
        cached?.let { return it }
        val loaded = synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
        return loaded
    }

    private fun load(context: Context): List<DoubleArray> = runCatching {
        val text = context.assets.open("coastlines.json").bufferedReader()
            .use { it.readText() }
        val rings = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(text).jsonObject["rings"]?.jsonArray ?: return emptyList()
        rings.mapNotNull { ring ->
            val flat = ring as? JsonArray ?: return@mapNotNull null
            if (flat.size < 8) return@mapNotNull null
            DoubleArray(flat.size) { i ->
                (flat[i] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            }
        }
    }.getOrDefault(emptyList())
}
