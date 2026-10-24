package com.osint.unified.ui.godeye

/**
 * Every layer, once.
 *
 * There are thirty-four of them and each one used to be written out
 * by hand in at least four places: the `show*` flag, the
 * `visibleLayers` builder, the pill, the world brief's health list
 * and the area report's wording table. Adding a layer meant finding
 * all of them, and the inevitable happened repeatedly — in one day
 * of review, `AreaReport.LAYER_WORDS` was found to be missing
 * `spaceWeather` so the caveat printed the raw key at the person;
 * the brief's health list covered eleven keys of thirty-four, so
 * twenty-three layers could not be reported as down at all; and
 * three separate pills had to be given a `partial` argument one at
 * a time.
 *
 * So: the prose about a layer lives here, in one table, and the two
 * places that describe a layer in words read it rather than
 * repeating it. The switches are one set of these keys
 * (GodEyeViewScreen's `shownLayers`), and what to fetch, ALL LAYERS
 * and CLEAR are worked out from this table. What is still written out
 * by hand is the list each layer's data lives in, because those are
 * typed fields of a state class.
 *
 * The test beside this file asserts the table covers exactly the
 * keys the view model polls, so the next layer added cannot be
 * half-added.
 */
internal object Layers {

    /**
     * @param key the id used in `loaded`, `feedTrouble`, `feedPartial`
     *        and `lastSuccess`.
     * @param brief how the world brief names it when it has nothing
     *        to report: "no <brief> anywhere". Null when the layer is
     *        an inventory rather than an event feed, which can be
     *        down but never "quiet" — "no ports anywhere in the
     *        world" is not a sentence about today.
     * @param area how the area report names it in a caveat.
     * @param reachKm for a layer fetched in a circle around the view
     *        rather than globally: how far that circle reaches.
     *        Beyond it nothing was asked, which is not the same as
     *        nothing being there.
     */
    data class Layer(
        val key: String,
        val area: String,
        val brief: String? = null,
        val reachKm: Double? = null,
        /** Switched on and off with another layer rather than by its own pill. */
        val ridesWith: String? = null
    )

    val ALL: List<Layer> = listOf(
        // Event feeds: these can honestly be quiet.
        Layer("gdacs", "disaster alerts", brief = "disaster alerts"),
        Layer("conflicts", "ongoing conflicts", brief = "conflicts"),
        Layer("volcanoes", "volcanoes", brief = "volcanoes"),
        Layer("fires", "wildfires", brief = "wildfires"),
        Layer("outages", "internet outages", brief = "countries with connectivity down"),
        Layer(
            "worldWarnings", "severe-weather warnings",
            brief = "severe-weather warnings anywhere in the world"
        ),
        Layer("weather", "US weather alerts", brief = "US severe-weather alerts"),
        Layer("milAircraft", "military aircraft", brief = "military aircraft"),
        // Named by their real coverage. A regional source reporting
        // nothing is not the world reporting nothing, and "no
        // tropical cyclones" during a West Pacific typhoon is a lie
        // the NHC never told.
        Layer(
            "cyclones", "tropical cyclones",
            brief = "tropical cyclones anywhere in the world"
        ),
        Layer(
            "tsunamis", "tsunami bulletins",
            brief = "tsunami bulletins from any of the four centres"
        ),
        Layer("airportStatus", "US airport status", brief = "US airport delays"),
        Layer("quakes", "earthquakes", brief = "earthquakes anywhere in the world"),
        Layer("naturalEvents", "natural events", brief = "natural events"),
        Layer("news", "news hotspots", brief = "news hotspots"),
        Layer("launches", "launches", brief = "rocket launches in the window"),
        Layer("aurora", "aurora", brief = "aurora"),

        // Inventories and trackers: down, never quiet.
        Layer("aircraft", "aircraft"),
        Layer("vessels", "vessels"),
        Layer("satellites", "satellites"),
        Layer("seaLevel", "tide gauges"),
        Layer("ports", "ports"),
        Layer("buoys", "ocean buoys"),
        Layer("metar", "airfield weather"),
        Layer("airQuality", "air quality"),
        Layer("stations", "seismic stations"),
        Layer("airports", "airports"),
        Layer("powerPlants", "power plants"),
        Layer("cables", "cable landings"),
        Layer("overlayPoints", "your overlay"),
        Layer("spaceWeather", "space weather", ridesWith = "aurora"),

        // Fetched in a circle around the view. The reach is what the
        // area report needs to say "most of this radius was never
        // asked about".
        Layer("cameras", "traffic cameras", reachKm = 25.0),
        Layer("sites", "controlled sites", reachKm = 120.0),
        Layer("airspace", "restricted airspace", reachKm = 150.0),
        // Radiation is fetched for the viewport rather than a fixed
        // circle, so there is no radius to compare — the area report
        // says so in its own words.
        Layer("radiation", "radiation monitors")
    )

    private val BY_KEY: Map<String, Layer> = ALL.associateBy { it.key }

    /** Every layer that has a switch of its own: what ALL LAYERS turns on. */
    val SWITCHES: Set<String> = ALL.filter { it.ridesWith == null }.mapTo(LinkedHashSet()) { it.key }

    /**
     * What to fetch for the switches that are on — every layer when
     * [everything] is, and a layer that rides with another whenever
     * that one is.
     */
    fun fetched(shown: Set<String>, everything: Boolean = false): Set<String> =
        ALL.filter { everything || (it.ridesWith ?: it.key) in shown }
            .mapTo(LinkedHashSet()) { it.key }

    fun of(key: String): Layer? = BY_KEY[key]

    /** How the area report names a layer, or the raw key if it is new. */
    fun area(key: String): String = BY_KEY[key]?.area ?: key

    /** The layers fetched in a circle, and how far that circle reaches. */
    val REACH: Map<String, Double> =
        ALL.mapNotNull { l -> l.reachKm?.let { l.key to it } }.toMap()
}
