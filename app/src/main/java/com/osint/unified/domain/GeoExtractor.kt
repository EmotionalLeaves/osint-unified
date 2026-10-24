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

    /**
     * Every distinct, valid coordinate in the text, in order of
     * appearance.
     *
     * Which is what it says and what it did not do. The loop was
     * pattern by pattern, so every "Coordinates: ..." in the text
     * came before every "maps?q=..." however they were actually
     * ordered — and firstCoordinate is what decides where a finding
     * gets plotted on the globe. A tool whose output opens with a
     * map link and mentions a second place further down plotted the
     * second one.
     */
    fun allCoordinates(text: String): List<Pair<Double, Double>> {
        val found = ArrayList<Pair<Int, Pair<Double, Double>>>()
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@forEach
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    found.add(m.range.first to (lat to lon))
                }
            }
        }
        val out = LinkedHashSet<Pair<Double, Double>>()
        found.sortedBy { it.first }.forEach { out.add(it.second) }
        return out.toList()
    }
}
