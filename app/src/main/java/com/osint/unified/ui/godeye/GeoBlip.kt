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
    val url: String? = null
)
