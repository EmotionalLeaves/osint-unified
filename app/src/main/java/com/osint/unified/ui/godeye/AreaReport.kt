package com.osint.unified.ui.godeye

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

/**
 * Everything the globe currently knows about one place.
 *
 * Tapping a marker answers "what is this?". The question an analyst actually
 * asks of a map is the other one — "what is going on *here*?" — and no single
 * layer answers it. A port call means something different with a military
 * flight overhead, a weather warning in force and a quake an hour old; the
 * layers are only intelligence together.
 *
 * So a long press on the globe sweeps every loaded layer for a radius and
 * returns what it finds, nearest first, grouped by layer. It reads the state
 * the app already holds, which means it needs no network and works wherever
 * you press, including over ocean.
 */
object AreaReport {

    private const val EARTH_RADIUS_KM = 6371.0088

    /** One thing found near the pressed point. */
    data class Item(
        val kind: String,
        val label: String,
        val lat: Double,
        val lon: Double,
        val distanceKm: Double,
        val url: String? = null,
        /** The position is a stand-in, so the distance is to that stand-in
         *  and not to the thing. Shown with a "≈" rather than as a fact. */
        val approximate: Boolean = false
    )

    /** The items of one layer, nearest first. */
    data class Group(val kind: String, val items: List<Item>, val total: Int)

    data class Report(
        val lat: Double,
        val lon: Double,
        val radiusKm: Double,
        val groups: List<Group>
    ) {
        val total: Int get() = groups.sumOf { it.total }
    }

    /**
     * Great-circle distance in kilometres.
     *
     * Haversine rather than the law of cosines: the points being compared are
     * often a few hundred metres apart — two aircraft in a holding pattern,
     * cameras along one road — and the cosine form loses its precision
     * exactly there.
     */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p = Math.PI / 180.0
        val dLat = (lat2 - lat1) * p
        val dLon = (lon2 - lon1) * p
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * p) * cos(lat2 * p) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /**
     * A sensible sweep radius for the zoom you are looking at.
     *
     * Pressing while zoomed out over a continent means something different
     * from pressing on one airfield, and a fixed radius would be either
     * uselessly narrow at the first or meaninglessly wide at the second.
     */
    fun radiusForZoom(zoom: Double): Double = when {
        zoom >= 13 -> 10.0
        zoom >= 11 -> 25.0
        zoom >= 9 -> 60.0
        zoom >= 7 -> 150.0
        zoom >= 5 -> 400.0
        zoom >= 3 -> 1000.0
        else -> 2000.0
    }

    /** Per layer, in the report. Enough to be a picture, not a data dump. */
    private const val PER_LAYER = 12

    fun build(state: GodEyeUiState, lat: Double, lon: Double, radiusKm: Double): Report {
        val groups = ArrayList<Group>()

        fun <T> sweep(
            kind: String,
            source: List<T>,
            latOf: (T) -> Double,
            lonOf: (T) -> Double,
            labelOf: (T) -> String,
            urlOf: (T) -> String? = { null },
            approximateOf: (T) -> Boolean = { false }
        ) {
            if (source.isEmpty()) return
            val near = ArrayList<Item>()
            source.forEach { t ->
                val tLat = latOf(t)
                val tLon = lonOf(t)
                if (tLat.isNaN() || tLon.isNaN()) return@forEach
                val d = distanceKm(lat, lon, tLat, tLon)
                if (d > radiusKm) return@forEach
                near.add(
                    Item(
                        kind, labelOf(t).ifBlank { kind }, tLat, tLon, d,
                        urlOf(t), approximateOf(t)
                    )
                )
            }
            if (near.isEmpty()) return
            near.sortBy { it.distanceKm }
            groups.add(Group(kind, near.take(PER_LAYER), near.size))
        }

        fun blips(kind: String, source: List<GeoBlip>) =
            sweep(
                kind, source, { it.lat }, { it.lon }, { it.label }, { it.url },
                { it.approximate }
            )

        // Ordered so the things that would change a decision come first.
        sweep("Conflict", state.conflicts, { it.lat }, { it.lon }, { it.label }, { it.url })
        blips("Tsunami bulletin", state.tsunamis)
        blips("Tropical cyclone", state.cyclones)
        blips("Disaster alert", state.gdacs)
        blips("Weather alert", state.weather)
        blips("World weather warning", state.worldWarnings)
        blips("Internet outage", state.outages)
        blips("Tide gauge", state.seaLevel)
        blips("Launch", state.launches)
        blips("Port", state.ports)
        blips("Controlled site", state.sites)
        sweep("Earthquake", state.quakes, { it.lat }, { it.lon }, { it.label }, { it.url })
        blips("Volcano", state.volcanoes)
        blips("Wildfire", state.fires)
        sweep("Natural event", state.naturalEvents, { it.lat }, { it.lon }, { "${it.category}: ${it.title}" })
        blips("Military aircraft", state.milAircraft)
        sweep("Aircraft", state.aircraft, { it.lat }, { it.lon },
            { it.callsign.ifBlank { it.icao } })
        sweep("Vessel", state.vessels, { it.lat }, { it.lon }, { it.name.ifBlank { it.mmsi } })
        blips("Airport status", state.airportStatus)
        blips("Restricted airspace", state.airspace)
        blips("Radiation", state.radiation)
        blips("News hotspot", state.news)
        blips("Airport", state.airports)
        blips("Power plant", state.powerPlants)
        blips("Cable landing", state.cables)
        blips("Traffic camera", state.cameras)
        blips("Airfield weather", state.metar)
        blips("Air quality", state.airQuality)
        blips("Ocean buoy", state.buoys)
        blips("Seismic station", state.stations)
        blips("Satellite", state.satellites)
        blips("Aurora forecast", state.aurora)
        sweep("Custom overlay", state.overlayPoints, { it.lat }, { it.lon }, { it.label })
        sweep("Finding", state.findings, { it.lat }, { it.lon }, { it.label })

        return Report(lat, lon, radiusKm, groups)
    }

    /**
     * A short headline, in the order [build] established.
     *
     * It used to re-sort by count, which threw that order away and handed
     * back "47 traffic camera · 31 airport" while the one ground stop or
     * tsunami bulletin in the report never reached the line anyone reads.
     * The sweep is already ordered so that what would change a decision
     * comes first; the headline's job is to keep it.
     */
    fun headline(report: Report): String {
        if (report.groups.isEmpty()) return "Nothing loaded within ${fmtKm(report.radiusKm)} of here."
        return report.groups
            .take(4)
            .joinToString(" · ") { "${it.total} ${it.kind.lowercase()}" }
    }

    /** A distance, or an admission that it is only roughly one. */
    fun fmtDistance(item: Item): String =
        if (item.approximate) "≈ " + fmtKm(item.distanceKm) else fmtKm(item.distanceKm)

    fun fmtKm(km: Double): String = when {
        km < 1 -> "${(km * 1000).toInt()} m"
        km < 10 -> "%.1f km".format(Locale.US, km)
        else -> "${km.toInt()} km"
    }
}
