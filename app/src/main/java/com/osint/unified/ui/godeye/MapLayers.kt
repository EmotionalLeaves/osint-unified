package com.osint.unified.ui.godeye

/**
 * Everything currently visible, in one place.
 *
 * The globe and the flat map draw the same world and must never
 * disagree about what is on it. They used to be given twenty-seven
 * arguments each, which is an invitation for one of them to fall out of
 * step the first time a layer is added. One object, one table turning it
 * into markers, two views reading it.
 */
data class MapLayers(
    val aircraft: List<Aircraft> = emptyList(),
    val vessels: List<Vessel> = emptyList(),
    val quakes: List<Quake> = emptyList(),
    val events: List<NaturalEvent> = emptyList(),
    val overlay: List<OverlayPoint> = emptyList(),
    val weather: List<GeoBlip> = emptyList(),
    val fires: List<GeoBlip> = emptyList(),
    val satellites: List<GeoBlip> = emptyList(),
    val news: List<GeoBlip> = emptyList(),
    val gdacs: List<GeoBlip> = emptyList(),
    val volcanoes: List<GeoBlip> = emptyList(),
    val worldWarnings: List<GeoBlip> = emptyList(),
    val seaLevel: List<GeoBlip> = emptyList(),
    val outages: List<GeoBlip> = emptyList(),
    val ports: List<GeoBlip> = emptyList(),
    val launches: List<GeoBlip> = emptyList(),
    val sites: List<GeoBlip> = emptyList(),
    val aurora: List<GeoBlip> = emptyList(),
    val buoys: List<GeoBlip> = emptyList(),
    val metar: List<GeoBlip> = emptyList(),
    val milAircraft: List<GeoBlip> = emptyList(),
    val conflicts: List<GeoBlip> = emptyList(),
    val airQuality: List<GeoBlip> = emptyList(),
    val stations: List<GeoBlip> = emptyList(),
    val airports: List<GeoBlip> = emptyList(),
    val powerPlants: List<GeoBlip> = emptyList(),
    val cameras: List<GeoBlip> = emptyList(),
    val airspace: List<GeoBlip> = emptyList(),
    val cables: List<GeoBlip> = emptyList(),
    val cyclones: List<GeoBlip> = emptyList(),
    val tsunamis: List<GeoBlip> = emptyList(),
    val airportStatus: List<GeoBlip> = emptyList(),
    val radiation: List<GeoBlip> = emptyList(),
    val findings: List<FindingPin> = emptyList()
) {

    /**
     * Every layer as plottable points, in drawing order.
     *
     * Background readings first, moving things above them, and the
     * investigation's own findings on top — a dossier's result should
     * never be hidden under a weather station.
     */
    fun points(): List<GlobePoint> {
        val out = ArrayList<GlobePoint>(2048)
        fun add(
            kind: String,
            lat: Double,
            lon: Double,
            color: Int,
            sizeDp: Int,
            title: String?,
            clickable: Boolean,
            url: String? = null
        ) {
            if (lat.isNaN() || lon.isNaN()) return
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            out.add(GlobePoint(kind, lat, lon, color, sizeDp, title, clickable, url))
        }

        quakes.forEach { add("Earthquake", it.lat, it.lon, 0xFFF0997B.toInt(), 8, it.label, true, it.url) }
        events.forEach { add("Natural event", it.lat, it.lon, 0xFFF2C94C.toInt(), 9, "${it.category}: ${it.title}", true) }
        news.forEach { add("News hotspot", it.lat, it.lon, 0xFFFF79C6.toInt(), 7, it.label, true, it.url) }
        aurora.forEach { add("Aurora forecast", it.lat, it.lon, 0xFF66FFB2.toInt(), 6, it.label, false, it.url) }
        stations.forEach { add("Seismic station", it.lat, it.lon, 0xFF9AB0C0.toInt(), 5, it.label, false, it.url) }
        airports.forEach { add("Airport", it.lat, it.lon, 0xFF8FD3FF.toInt(), 6, it.label, true, it.url) }
        powerPlants.forEach { add("Power plant", it.lat, it.lon, 0xFFFFC14D.toInt(), 6, it.label, true, it.url) }
        cameras.forEach { add("Traffic camera", it.lat, it.lon, 0xFFB388FF.toInt(), 6, it.label, true, it.url) }
        airspace.forEach { add("Restricted airspace", it.lat, it.lon, 0xFFFF5370.toInt(), 7, it.label, true, it.url) }
        cables.forEach { add("Cable landing", it.lat, it.lon, 0xFF7FFFD4.toInt(), 5, it.label, true, it.url) }
        sites.forEach {
            add("Controlled site", it.lat, it.lon, 0xFFD98BFF.toInt(), 6, it.label, true, it.url)
        }
        ports.forEach {
            add("Port", it.lat, it.lon, 0xFF7FD4FF.toInt(), 5, it.label, true, it.url)
        }
        launches.forEach {
            add("Launch", it.lat, it.lon, 0xFFFFF066.toInt(), 10, it.label, true, it.url)
        }
        seaLevel.forEach {
            add("Tide gauge", it.lat, it.lon, 0xFF4DD0E1.toInt(), 5, it.label, true, it.url)
        }
        outages.forEach {
            add("Internet outage", it.lat, it.lon, 0xFFFF6B6B.toInt(), 12, it.label, true, it.url)
        }
        worldWarnings.forEach {
            add("World weather warning", it.lat, it.lon, 0xFFFFD166.toInt(), 10, it.label, true, it.url)
        }
        airQuality.forEach { add("Air quality", it.lat, it.lon, 0xFFB4E66B.toInt(), 9, it.label, false, it.url) }
        buoys.forEach { add("Ocean buoy", it.lat, it.lon, 0xFF4DD0E1.toInt(), 6, it.label, true, it.url) }
        metar.forEach { add("Airfield weather", it.lat, it.lon, 0xFF9FE8FF.toInt(), 6, it.label, false, it.url) }
        conflicts.forEach { add("Conflict", it.lat, it.lon, 0xFFFF2E2E.toInt(), 11, it.label, true, it.url) }
        gdacs.forEach { add("Disaster alert", it.lat, it.lon, 0xFFFF8A3D.toInt(), 10, it.label, true, it.url) }
        volcanoes.forEach { add("Volcano", it.lat, it.lon, 0xFFFF4D4D.toInt(), 9, it.label, true, it.url) }
        weather.forEach { add("Weather alert", it.lat, it.lon, 0xFF7FB2FF.toInt(), 9, it.label, true, it.url) }
        fires.forEach { add("Wildfire", it.lat, it.lon, 0xFFFF5A4D.toInt(), 8, it.label, true, it.url) }
        overlay.forEach { add("Custom overlay", it.lat, it.lon, 0xFF5CE1D6.toInt(), 9, it.label, false) }
        radiation.forEach { add("Radiation", it.lat, it.lon, 0xFFD4FF4D.toInt(), 7, it.label, false, it.url) }
        airportStatus.forEach { add("Airport status", it.lat, it.lon, 0xFFFFA64D.toInt(), 10, it.label, true, it.url) }
        cyclones.forEach { add("Tropical cyclone", it.lat, it.lon, 0xFFFF7AC6.toInt(), 14, it.label, true, it.url) }
        tsunamis.forEach { add("Tsunami bulletin", it.lat, it.lon, 0xFF4DF0FF.toInt(), 13, it.label, true, it.url) }
        satellites.forEach { add("Satellite", it.lat, it.lon, 0xFFC0A8FF.toInt(), 11, it.label, true, it.url) }
        vessels.forEach { add("Vessel", it.lat, it.lon, 0xFF1D9E75.toInt(), 9, it.name.ifBlank { it.mmsi }, true) }
        aircraft.forEach { add("Aircraft", it.lat, it.lon, 0xFF4FC3F7.toInt(), 9, it.callsign.ifBlank { it.icao }, true) }
        milAircraft.forEach { add("Military aircraft", it.lat, it.lon, 0xFFFFD23F.toInt(), 10, it.label, true, it.url) }
        // Investigation findings sit on top, larger and brighter.
        findings.forEach { add("Finding", it.lat, it.lon, 0xFFE24FD6.toInt(), 14, it.label, true) }
        return out
    }
}
