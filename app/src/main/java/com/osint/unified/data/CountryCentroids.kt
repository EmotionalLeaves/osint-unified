package com.osint.unified.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Where each country is, for the sources that name one and give no
 * coordinates.
 *
 * Internet outages are reported per country; so are a dozen other
 * things worth knowing. Without somewhere to put them they are a list,
 * and a list is not what this app is for.
 *
 * The positions are area-weighted centroids of the largest landmass of
 * each country, computed in CI from Natural Earth — not the mean of
 * every polygon, because the average of France and French Guiana is a
 * point in the Atlantic.
 */
object CountryCentroids {

    data class Place(val lat: Double, val lon: Double, val name: String)

    @Volatile private var table: Map<String, Place>? = null

    fun load(context: Context): Map<String, Place> {
        table?.let { return it }
        val parsed = runCatching {
            val text = context.assets.open("country-centroids.json")
                .bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching emptyMap()
            root.entries.mapNotNull { (code, value) ->
                val row = value as? JsonArray ?: return@mapNotNull null
                val lat = (row.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                    ?: return@mapNotNull null
                val lon = (row.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                    ?: return@mapNotNull null
                val name = (row.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: code
                code.uppercase() to Place(lat, lon, name)
            }.toMap()
        }.getOrDefault(emptyMap())
        table = parsed
        return parsed
    }

    /**
     * A country by whatever the source called it.
     *
     * Sources name countries in at least three ways — an ISO alpha-2,
     * an alpha-3, or the English name — and it is not worth a separate
     * parser per feed to find out which. A code is tried as a code and
     * anything else as a name.
     */
    fun find(table: Map<String, Place>, raw: String?): Place? {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty()) return null
        table[key.uppercase()]?.let { return it }
        return table.values.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}
