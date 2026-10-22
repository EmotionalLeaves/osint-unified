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

    fun firstCoordinate(text: String): Pair<Double, Double>? = allCoordinates(text).firstOrNull()

    /** Every distinct, valid coordinate in the text, in order of appearance. */
    fun allCoordinates(text: String): List<Pair<Double, Double>> {
        val out = LinkedHashSet<Pair<Double, Double>>()
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@forEach
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    out.add(lat to lon)
                }
            }
        }
        return out.toList()
    }
}
