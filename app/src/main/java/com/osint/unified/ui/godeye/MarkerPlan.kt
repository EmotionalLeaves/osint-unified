package com.osint.unified.ui.godeye

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
        // Equal edges mean the whole world, not an empty sliver of it.
        // osmdroid reports a box spanning exactly 360 degrees that way
        // when the map is zoomed right out, and the non-wrapping
        // branch below would then cull every marker on Earth.
        if (west == east) return true
        return if (west < east) lon in west..east else lon >= west || lon <= east
    }

    companion object {
        /**
         * A window, or null when the numbers are not a view of
         * anything.
         *
         * A MapView has no meaningful bounding box until it has been
         * laid out: before that it answers from a zero-sized viewport
         * and reports a north edge equal to its south edge, or NaN.
         * Culled against that, every marker on Earth is off screen —
         * and because the window was only refreshed by a scroll or
         * zoom event, a map that was opened and then merely looked at
         * stayed empty for ever, with every layer switched on and
         * nothing to show for it.
         *
         * Null is the honest answer there, and the planner reads it
         * as "do not cull": a few too many markers for one frame is a
         * cost, none at all is a broken screen.
         */
        fun of(north: Double, south: Double, west: Double, east: Double): MapWindow? {
            if (north.isNaN() || south.isNaN() || west.isNaN() || east.isNaN()) return null
            if (north <= south) return null
            // west == east is osmdroid's way of saying "the whole
            // world", which is a real view. Anything else narrower
            // than a thousandth of a degree is a view of nothing.
            if (west != east && kotlin.math.abs(east - west) < 0.001) return null
            return MapWindow(north, south, west, east)
        }
    }
}

/**
 * Which markers belong on the map, culled to the view and thinned to a
 * budget.
 *
 * Pure, so it runs off the main thread and can be tested without a map.
 */
internal fun planMarkers(layers: MapLayers, inView: MapWindow?): List<PendingMarker> =
    planMarkers(layers.points(), inView)

/**
 * What the map will draw, and how much it is leaving out.
 *
 * The second number is the whole point. Zoom a map in far enough and
 * the correct answer is an empty screen — everything the layers hold
 * is somewhere else — but the pills above it go on reporting the
 * layer totals, so the app looks broken at exactly the moment it is
 * being most careful. Reported on a tester's phone as "zoom in and
 * all the layers stop working", which is what that looks like from
 * the outside.
 */
internal data class MapPlan(
    val markers: List<PendingMarker>,
    /** Everything the armed layers hold, before the view is applied. */
    val total: Int
)

internal fun planFor(layers: MapLayers, inView: MapWindow?): MapPlan {
    val all = layers.points()
    return MapPlan(planMarkers(all, inView), all.size)
}

internal fun planMarkers(
    points: List<GlobePoint>,
    inView: MapWindow?
): List<PendingMarker> {
    val pending = ArrayList<PendingMarker>(2048)
    points.forEach { p ->
        if (p.lat.isNaN() || p.lon.isNaN()) return@forEach
        if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return@forEach
        if (inView != null && !inView.contains(p.lat, p.lon)) return@forEach
        pending.add(
            PendingMarker(p.kind, p.lat, p.lon, p.color, p.sizeDp, p.title, p.clickable, p.url)
        )
    }
    return thin(pending)
}

/** The flat map's share of [thinBy]. */
private fun thin(pending: List<PendingMarker>): List<PendingMarker> =
    thinBy(pending, MARKER_BUDGET, { it.kind }, { markerBucket(it) }, { it.identity })

/**
 * The same fairness, for the globe.
 *
 * The sphere projects every marker it is given on every frame, so the
 * list it is handed has to be a list worth projecting. Culling to the
 * near hemisphere happens at draw time and is one comparison per
 * point; this is the part that cannot be done per frame.
 *
 * A looser budget than the flat map's, because this runs once when the
 * layers change rather than on every pan, and because the globe can
 * usefully show more at once — it is the view for seeing everything.
 */
/**
 * What the globe is drawing, and what the armed layers hold.
 *
 * The flat map has carried this pair since the second tester
 * reported "zoom in and all the layers stop working"; the globe,
 * which is the view that opens, had no number to report, so the
 * sentence explaining an empty screen could only ever appear on the
 * branch where the problem was milder. The sphere culls to the
 * visible cap plus a margin and then thins to [GLOBE_BUDGET], so a
 * hundred thousand wildfires become at most two and a half thousand
 * markers under a pill still reading a hundred thousand.
 */
internal data class GlobePlan(
    val points: List<GlobePoint>,
    /** Everything the armed layers hold, before the camera is applied. */
    val total: Int
)

internal fun planGlobe(
    points: List<GlobePoint>,
    budget: Int = GLOBE_BUDGET,
    centre: Pair<Double, Double>? = null,
    keepWithinDegrees: Double = 180.0
): GlobePlan = GlobePlan(
    thinGlobe(points, budget, centre, keepWithinDegrees),
    points.size
)

internal fun thinGlobe(
    points: List<GlobePoint>,
    budget: Int = GLOBE_BUDGET,
    /** Where the globe is pointed, or null to thin the whole Earth. */
    centre: Pair<Double, Double>? = null,
    /** How far from that point is worth keeping, as a great-circle
     *  angle. Half the visible span plus a margin, so the camera can
     *  move a little before this has to be computed again. */
    keepWithinDegrees: Double = 180.0
): List<GlobePoint> {
    val near = if (centre == null || keepWithinDegrees >= 180.0) points
    else points.filter { withinDegrees(centre.first, centre.second, it.lat, it.lon, keepWithinDegrees) }
    return thinBy(near, budget, { it.kind }, { globeBucket(it) }, { it.identity })
}

/** Great-circle separation, as an angle, without the distance. */
internal fun withinDegrees(
    lat0: Double,
    lon0: Double,
    lat: Double,
    lon: Double,
    limit: Double
): Boolean {
    val a = Math.toRadians(lat0)
    val b = Math.toRadians(lat)
    val d = Math.toRadians(lon - lon0)
    val cosine = sin(a) * sin(b) + cos(a) * cos(b) * cos(d)
    return cosine >= cos(Math.toRadians(limit.coerceIn(0.0, 180.0)))
}

/**
 * Thinning that is fair between layers.
 *
 * The satellite catalogue alone is twelve thousand objects and the
 * fire detections are a hundred thousand; taking markers in the order
 * layers happen to be added would let either eat the whole budget and
 * push aircraft, vessels and everything else off the map entirely.
 *
 * So each layer present gets an equal share, any layer using less than
 * its share hands the remainder back, and layers over their share are
 * sampled by a hash of their own identity — which gives exactly the
 * quota, scattered across the view, and the same answer however the
 * feed ordered its reply.
 */
private fun <T> thinBy(
    items: List<T>,
    budget: Int,
    kindOf: (T) -> String,
    bucketOf: (T) -> Int,
    identityOf: (T) -> String
): List<T> {
    if (items.size <= budget) return items
    val byKind = items.groupBy(kindOf)
    val quotas = HashMap<String, Int>()
    var remaining = budget
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
                // not on where they happened to land in the list. A
                // feed returning its items in a different order — which
                // these do, every poll — would otherwise keep a
                // different subset each time, so stationary things
                // blinked in and out while nothing was moving. The name
                // breaks ties for the same reason: a sort that falls
                // back on list position is a sort that flickers.
                markers
                    .sortedWith(compareBy({ bucketOf(it) }, { identityOf(it) }))
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
            title?.let(::withoutAge)?.takeIf { it.isNotBlank() }
                ?: "${floor(lat * 1000).toInt()}:${floor(lon * 1000).toInt()}"
            )
}

/**
 * A label with its age taken off the end.
 *
 * Most feeds here finish a label with how old the reading is — "· 40m
 * ago", "· in 3h" — and the identity was the whole label. So at the
 * minute boundary every one of those markers became, as far as this
 * code could tell, a different marker: the map destroyed and rebuilt
 * the Marker it had been careful to reuse, and the thinning hash
 * moved, so in any layer over its quota the surviving subset changed
 * and stationary markers blinked in and out once a minute. Both are
 * the exact behaviour the comments here promise to avoid.
 *
 * What a thing is does not change when it gets a minute older.
 */
internal fun withoutAge(label: String): String =
    label.replace(AGE_SUFFIX, "").trim()

private val AGE_SUFFIX = Regex(
    """\s*·\s*(?:in\s+\d+[smhd]|\d+[smhd]\s+ago|undated)\s*$"""
)

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
internal fun markerBucket(pm: PendingMarker): Int = bucketOf(pm.kind, pm.identity)

/** The same, for a point on the globe. */
internal fun globeBucket(point: GlobePoint): Int = bucketOf(point.kind, point.identity)

private fun bucketOf(kind: String, identity: String): Int {
    var h = (kind.hashCode() * 31) xor identity.hashCode()
    h = h xor (h ushr 16)
    h *= -0x7ee3623b
    h = h xor (h ushr 13)
    return ((h.toLong() and 0xFFFFFFFFL) % MARKER_BUCKETS).toInt()
}

/** The most markers drawn at once. Layers may hold far more than this;
 *  what is off screen costs nothing, and what is left is thinned evenly. */
internal const val MARKER_BUDGET = 2500

/**
 * The sphere projects everything it is handed on every frame, so this
 * is what can be projected in a frame and not what can be seen.
 *
 * Was eight thousand, which was never a frame's worth: each point cost
 * a projection, a day-or-night test and two draw calls, so a full
 * globe was sixteen thousand canvas operations before a coastline had
 * been drawn. The drawing is batched by colour now, but the
 * projection and the night test are still per point and still on the
 * main thread — and two and a half thousand markers on a disc a
 * thousand pixels across is already more than anyone can tell apart.
 */
internal const val GLOBE_BUDGET = 2500
