package com.osint.unified.ui.godeye

/**
 * One earthquake.
 *
 * Magnitude alone is not intelligence. "M6.1" in a world brief, with no
 * place, no time and no depth, tells you something happened somewhere —
 * and a shallow 6.1 under a city is a different event from a deep one
 * under open ocean.
 */
data class Quake(
    val lat: Double,
    val lon: Double,
    val mag: Double,
    val place: String = "",
    val atMillis: Long? = null,
    val depthKm: Double? = null,
    val url: String? = null
) {
    /** What the map and the brief show. */
    val label: String
        get() = buildString {
            append("M%.1f".format(java.util.Locale.US, mag))
            if (place.isNotBlank()) append(" · $place")
            depthKm?.let { append(" · %.0f km deep".format(java.util.Locale.US, it)) }
            com.osint.unified.data.Freshness.ago(atMillis)?.let { append(" · $it") }
        }
}
