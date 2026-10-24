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
    private val limit: Int = 60000,
    private val link: (JsonObject) -> String? = { null }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var cache: List<GeoBlip>? = null

    /**
     * Throws when the dataset is not there, rather than reporting an
     * empty world.
     *
     * These three assets are fetched in CI with `|| true` and
     * `verify-assets.py` deletes one that fails its floor, on the
     * stated grounds that the app "handles an absent dataset and says
     * so". It did not say so: any failure — asset missing, JSON
     * truncated, out of memory on a sixty-thousand-entry parse —
     * became an empty list, which the poll reads as a clean answer and
     * the pill prints as `PORTS·GLOBAL 0` with a loaded tick. An area
     * report then found no ports in a harbour.
     *
     * The bundled-list executors already draw this line — "the breach
     * catalogue is not bundled in this build" is a different sentence
     * from "not found" — and this is the same distinction one layer
     * down.
     *
     * Only a successful parse is cached, so a transient failure is
     * retried instead of being frozen in for the life of the process.
     */
    suspend fun fetch(): List<GeoBlip> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        val text = try {
            appContext.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw java.io.IOException(
                "$assetName is not bundled in this build", e
            )
        }
        val parsed = try {
            json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                GeoBlip(lat, lon, label(o), link(o))
            }.take(limit)
        } catch (e: Exception) {
            throw java.io.IOException(
                "$assetName could not be read: ${e.message ?: e::class.simpleName}", e
            )
        }
        // A dataset that parses to nothing is a broken dataset, not a
        // world with no airports in it.
        if (parsed.isEmpty()) {
            throw java.io.IOException("$assetName holds no usable points")
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

        /**
         * Every port in the world.
         *
         * A map that tracks ships and does not know where they can
         * tie up is half a maritime picture: a vessel slowing on
         * approach to somewhere means nothing until the somewhere has
         * a name. Static, so it works with the network off.
         */
        fun ports(context: Context) = BundledPointsDataSource(
            context,
            "ports.json",
            { o -> str(o, "n").ifBlank { "port" } },
            link = { o -> str(o, "w").takeIf { it.isNotBlank() }?.let { site ->
                if (site.startsWith("http")) site else "https://$site"
            } }
        )

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
