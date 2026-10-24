package com.osint.unified.domain

/**
 * One reading of a written coordinate, for everything that takes one.
 *
 * There were seven. `DeepApis.parseCoord` learned to tolerate a date
 * written after the point — which is what the chronolocation tools
 * needed — and the other six did not, so the literal example in the
 * feature's own help text made `cctvNear`, `osmRecon`,
 * `militarySites`, `overpass`, `revgeo` and `wikiNearby` throw. Six
 * sources reported unreachable because the app had malformed the
 * question, reverse geocoding among them: the one tool whose job is
 * to say where the point is.
 *
 * A guard that exists in one of seven places is not a guard. One
 * definition, used by all of them.
 */
object Coordinates {

    data class Point(val lat: Double, val lon: Double)

    /**
     * The latitude and longitude at the front of [input], ignoring
     * anything written after them.
     *
     * Range-checked, because "500, 900" is not a place on Earth and
     * every caller downstream treats what it gets as one.
     */
    fun parse(input: String): Point? {
        val parts = input.trim().split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Point(lat, lon)
    }

    /**
     * Whether two written coordinates name the same place.
     *
     * Compared as numbers at four decimals — about eleven metres,
     * finer than anything these sources claim and coarser than the
     * difference between two spellings of one point. The tools write
     * the same point at four decimals and at five, so a string
     * comparison says two different things about one place.
     *
     * Tolerant of a trailing date for the same reason [parse] is.
     */
    fun same(a: String, b: String): Boolean {
        val pa = parse(a) ?: return false
        val pb = parse(b) ?: return false
        return Math.round(pa.lat * 10_000) == Math.round(pb.lat * 10_000) &&
            Math.round(pa.lon * 10_000) == Math.round(pb.lon * 10_000)
    }

    /** The point as the key two renderings of it share. */
    fun key(value: String): String? {
        val p = parse(value) ?: return null
        return "%.4f,%.4f".format(java.util.Locale.US, p.lat, p.lon)
    }
}
