package com.osint.unified.ui.godeye

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Names, so the globe can be read rather than only looked at.
 *
 * Blue Marble is a photograph: beautiful, and silent about which
 * continent you have just turned to. A few hundred cities, biggest
 * first and capitals before them, is the difference between a picture
 * of the Earth and a map of it.
 *
 * Bundled and sorted in CI, so reading it is a loop and choosing how
 * many to show is a slice.
 */
object Places {

    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val population: Int,
        val capital: Boolean
    )

    @Volatile private var cached: List<Place>? = null

    /** Capitals first, then by population. Empty when not bundled. */
    fun all(context: Context): List<Place> {
        cached?.let { return it }
        val loaded = synchronized(this) { cached ?: load(context).also { cached = it } }
        return loaded
    }

    /**
     * How many names belong on screen at this zoom.
     *
     * A globe with three hundred labels on it is a word cloud. The
     * count climbs with the zoom because the same number of labels
     * over a smaller area is less crowded, not more.
     */
    fun countFor(zoom: Float): Int = when {
        zoom < 1.2f -> 14
        zoom < 1.8f -> 26
        zoom < 2.6f -> 48
        zoom < 3.6f -> 90
        zoom < 5f -> 180
        else -> 320
    }

    private fun load(context: Context): List<Place> = runCatching {
        val text = context.assets.open("places.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonArray
            .mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun p(key: String) = o[key] as? JsonPrimitive
                val lat = p("lat")?.doubleOrNull ?: return@mapNotNull null
                val lon = p("lon")?.doubleOrNull ?: return@mapNotNull null
                val name = p("n")?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Place(
                    name = name,
                    lat = lat,
                    lon = lon,
                    population = p("p")?.intOrNull ?: 0,
                    capital = (p("c")?.intOrNull ?: 0) == 1
                )
            }
    }.getOrDefault(emptyList())
}
