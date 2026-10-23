package com.osint.unified.ui.godeye

/**
 * Deciding which markers belong on the map, with nothing Android in it.
 *
 * This work used to live inside the flat map's AndroidView update block,
 * which Compose runs on every recomposition — so typing one character
 * into the search box walked every point in every layer, grouped them,
 * thinned them, destroyed every marker on the map and built them all
 * again, on the thread drawing the keyboard. Pulled out here it runs off
 * the main thread, only when the layers or the view actually change, and
 * can be tested without a map.
 */

/**
 * The visible area, as numbers.
 *
 * osmdroid's BoundingBox is a view-layer object and comparing two of
 * them does not mean what it should; this is a value, so an identical
 * window after a pan out and back compares equal and plans nothing.
 * Kept here, away from anything Android, so the planning this file does
 * can be tested without a map.
 */
internal data class MapWindow(
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double
) {
    /** Tolerates a map scrolled past the antimeridian, where the west
     *  edge is numerically greater than the east. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat > north || lat < south) return false
        return if (west <= east) lon in west..east else lon >= west || lon <= east
    }

}

/**
 * Which markers belong on the map, culled to the view and thinned to a
 * budget.
 *
 * Pure, so it runs off the main thread and can be tested without a map.
 */
internal fun planMarkers(layers: MapLayers, inView: MapWindow?): List<PendingMarker> {
    val pending = ArrayList<PendingMarker>(2048)
    layers.points().forEach { p ->
        if (p.lat.isNaN() || p.lon.isNaN()) return@forEach
        if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return@forEach
        if (inView != null && !inView.contains(p.lat, p.lon)) return@forEach
        pending.add(
            PendingMarker(p.kind, p.lat, p.lon, p.color, p.sizeDp, p.title, p.clickable, p.url)
        )
    }
    return thin(pending)
}

/**
 * Thinning has to be fair between layers.
 *
 * The satellite catalogue alone is twelve thousand objects; taking
 * markers in the order layers happen to be added would let it eat the
 * whole budget and push aircraft, vessels and everything else off the
 * map entirely.
 *
 * So each layer present gets an equal share, any layer using less than
 * its share hands the remainder back, and layers over their share are
 * sampled so what survives stays spread across the view instead of
 * clumping.
 */
private fun thin(pending: List<PendingMarker>): List<PendingMarker> {
    if (pending.size <= MARKER_BUDGET) return pending
    val byKind = pending.groupBy { it.kind }
    val quotas = HashMap<String, Int>()
    var remaining = MARKER_BUDGET
    var contenders = byKind.keys.toMutableSet()
    while (contenders.isNotEmpty() && remaining > 0) {
        val share = (remaining / contenders.size).coerceAtLeast(1)
        val satisfied = contenders.filter { (byKind[it]?.size ?: 0) <= share }
        if (satisfied.isEmpty()) {
            contenders.forEach { quotas[it] = share }
            remaining -= share * contenders.size
            break
        }
        satisfied.forEach { kind ->
            val n = byKind[kind]?.size ?: 0
            quotas[kind] = n
            remaining -= n
        }
        contenders = (contenders - satisfied.toSet()).toMutableSet()
    }
    return byKind.flatMap { (kind, markers) ->
        val quota = quotas[kind] ?: 0
        when {
            quota <= 0 -> emptyList()
            markers.size <= quota -> markers
            else -> {
                // Which markers survive has to depend on the markers,
                // not on where they happened to land in the list.
                // Striding over list position meant that a feed
                // returning its items in a different order — which
                // these do, every poll — kept a different subset each
                // time, so stationary things blinked in and out while
                // nothing was moving.
                //
                // Each marker falls in a bucket drawn from its own
                // identity: a callsign, an MMSI, a satellite name, or
                // its position for the things that have no name. Taking
                // the lowest buckets gives exactly the quota, scattered
                // across the view, and gives the same answer however
                // the feed ordered its reply. The name breaks ties for
                // the same reason — a sort that falls back on list
                // position is a sort that flickers.
                markers
                    .sortedWith(compareBy({ markerBucket(it) }, { it.identity }))
                    .take(quota)
            }
        }
    }
}

internal data class PendingMarker(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val color: Int,
    val sizeDp: Int,
    val title: String?,
    val clickable: Boolean,
    val url: String? = null
) {
    /**
     * What this marker *is*, as opposed to where it is.
     *
     * Named things are identified by their name, so the Marker drawn
     * for GHOST52 two minutes ago is moved rather than destroyed and
     * replaced when GHOST52 reports a new position. Unnamed readings
     * have only their place, rounded to about a hundred metres so a
     * value wobbling in the last decimal is still the same reading.
     */
    val identity: String
        get() = kind + "\u0000" + (
            title?.takeIf { it.isNotBlank() }
                ?: "${(lat * 1000).toInt()}:${(lon * 1000).toInt()}"
            )
}

/** How finely the thinning hash divides the markers of one layer. */
private const val MARKER_BUCKETS = 10_000

/**
 * A number between 0 and [MARKER_BUCKETS] that belongs to the marker
 * rather than to its place in a list.
 *
 * Named things hash on the name — a callsign, an MMSI, a satellite — so
 * the same object lands in the same bucket however the feed orders its
 * reply. Unnamed ones hash on position, rounded to about a hundred metres
 * so a reading that wobbles in the last decimal does not jump buckets.
 * The shift mixes the high bits down, because String.hashCode leaves
 * similar strings in neighbouring slots and a plain modulo would then
 * keep or drop whole runs of them together.
 */
internal fun markerBucket(pm: PendingMarker): Int {
    val key = pm.title?.takeIf { it.isNotBlank() }
        ?: "${(pm.lat * 1000).toInt()}:${(pm.lon * 1000).toInt()}"
    var h = (pm.kind.hashCode() * 31) xor key.hashCode()
    h = h xor (h ushr 16)
    h *= -0x7ee3623b
    h = h xor (h ushr 13)
    return ((h.toLong() and 0xFFFFFFFFL) % MARKER_BUCKETS).toInt()
}

/** The most markers drawn at once. Layers may hold far more than this;
 *  what is off screen costs nothing, and what is left is thinned evenly. */
internal const val MARKER_BUDGET = 2500
