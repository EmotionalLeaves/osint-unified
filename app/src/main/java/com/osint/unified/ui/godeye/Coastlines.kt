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

    private val cached = HashMap<String, List<DoubleArray>>()

    /** Every ring, biggest first. Empty when the file is not bundled. */
    fun rings(context: Context): List<DoubleArray> = of(context, "coastlines.json")

    /**
     * National borders, in the same packing.
     *
     * Photography has no borders. Blue Marble shows the Earth as it
     * is, which tells you nothing about which country you are looking
     * at — and on a map whose purpose is knowing where something is,
     * that is the one line that has to be drawn.
     */
    fun borders(context: Context): List<DoubleArray> = of(context, "borders.json")

    @Synchronized
    private fun of(context: Context, asset: String): List<DoubleArray> =
        cached.getOrPut(asset) { load(context, asset) }

    private fun load(context: Context, asset: String): List<DoubleArray> = runCatching {
        val text = context.assets.open(asset).bufferedReader()
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
