package com.osint.unified.data

import android.content.Context
import com.osint.unified.ui.godeye.GeoBlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Reads a bundled point dataset from assets (built in CI from the raw public
 * CSVs) and turns it into globe markers. Used for world airport and power-plant
 * infrastructure — datasets far too large to fetch live each time.
 */
class BundledPointsDataSource(
    private val appContext: Context,
    private val assetName: String,
    private val label: (JsonObject) -> String,
    private val limit: Int = 60000
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var cache: List<GeoBlip>? = null

    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        val parsed = try {
            val text = appContext.assets.open(assetName).bufferedReader().use { it.readText() }
            json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                GeoBlip(lat, lon, label(o))
            }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
        cache = parsed
        parsed
    }

    companion object {
        private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun airports(context: Context) = BundledPointsDataSource(context, "airports.json", { o ->
            val code = str(o, "c")
            val name = str(o, "n")
            if (code.isNotBlank()) "$code · $name" else name.ifBlank { "airport" }
        })

        fun powerPlants(context: Context) = BundledPointsDataSource(context, "powerplants.json", { o ->
            val mw = str(o, "mw")
            val fuel = str(o, "f")
            buildString {
                append(str(o, "n").ifBlank { "power plant" })
                if (fuel.isNotBlank()) append(" · $fuel")
                if (mw.isNotBlank()) append(" · ${mw}MW")
            }
        })
    }
}
