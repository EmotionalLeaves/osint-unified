package com.osint.unified.ui.godeye

/**
 * A generic positioned marker for the globe's live feed layers.
 *
 * [url] is optional context the object inspector can open — a camera's live
 * JPEG, a tracking page, a source article.
 */
data class GeoBlip(
    val lat: Double,
    val lon: Double,
    val label: String,
    val url: String? = null,
    /**
     * True when this position is a stand-in rather than where the thing is.
     *
     * Most NWS alerts name zones and carry no geometry, and a restricted
     * airspace is an area rather than a point. Both were being drawn as
     * ordinary markers, so standing inside a hundred-kilometre MOA the area
     * report would say it was forty kilometres away. A distance to an
     * invented point is not a distance, and the map should not offer one.
     */
    val approximate: Boolean = false
)
