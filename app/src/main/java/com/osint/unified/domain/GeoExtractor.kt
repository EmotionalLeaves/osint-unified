package com.osint.unified.domain

/**
 * Pulls a latitude/longitude out of a tool's free-text output so findings with
 * a location (IP geolocation, geocoded places, EXIF GPS, reverse-geocode) can
 * be plotted on the globe. Matches the formats the executors emit.
 */
object GeoExtractor {
    private val patterns = listOf(
        Regex("""(?:Coordinates|Coords|GPS)\s*[:=]?\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""mlat=(-?\d+(?:\.\d+)?)&mlon=(-?\d+(?:\.\d+)?)"""),
        Regex("""maps\?q=(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    )

    fun firstCoordinate(text: String): Pair<Double, Double>? {
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                return lat to lon
            }
        }
        return null
    }
}
