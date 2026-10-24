package com.osint.unified.ui.godeye

import kotlin.math.cos
import kotlin.math.sin

/**
 * Deciding which markers the globe draws, with nothing Android in it.
 *
 * There used to be two of everything here — a `planMarkers` for the
 * flat map and a `planGlobe` for the sphere, a `MapWindow` that culled
 * to a rectangle and a great-circle cull for the globe, two budgets,
 * two buckets. The map is gone, so its half is gone with it; what
 * stays is the sphere's, and the fairness logic the two always shared.
 *
 * Pure, so it runs off the main thread, only when the layers or the
 * camera actually change, and can be tested without a screen.
 */

/**
 * What the globe is drawing, and what the armed layers hold.
 *
 * The second number is the whole point. Zoom in far enough and the
 * right answer is a nearly empty view — everything the layers hold is
 * over the horizon — but the pills go on reporting the layer totals,
 * so the app looks broken at exactly the moment it is being most
 * careful. Reported on a tester's phone as "zoom in and all the layers
 * stop working", which is what that looks like from the outside.
 */
internal data class GlobePlan(
    val points: List<GlobePoint>,
    /** Everything the armed layers hold, before the camera is applied. */
    val total: Int
)

/** [thinGlobe], with the total it thinned from. */
internal fun planGlobe(
    points: List<GlobePoint>,
    budget: Int = GLOBE_BUDGET,
    centre: Pair<Double, Double>? = null,
    keepWithinDegrees: Double = 180.0
): GlobePlan = GlobePlan(
    thinGlobe(points, budget, centre, keepWithinDegrees),
    points.size
)

/**
 * The markers worth projecting, culled to what the camera can see and
 * thinned to a budget.
 *
 * The sphere projects every marker it is given on every frame, so the
 * list it is handed has to be a list worth projecting. Culling to the
 * near hemisphere happens at draw time and is one comparison per
 * point; this is the part that cannot be done per frame.
 */
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
 * push aircraft, vessels and everything else off the globe entirely.
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
    // A layer left over when the budget ran out still gets one.
    //
    // The loop stops at `remaining > 0`. When the share has been
    // floored to 1 — more layers left than budget left — the layers of
    // size one are all "satisfied" in a single pass, and if they use
    // up exactly what remains, the loop ends with the large layers
    // still contending and no quota at all. They were then dropped to
    // zero: the biggest layer on the globe silently vanished, which is
    // the opposite of what fairness here exists to do. Their share in
    // that pass was one, so one is what they get — a marker over
    // budget is a cost; a layer that is switched on and shows nothing
    // is a lie.
    contenders.forEach { quotas.putIfAbsent(it, 1) }
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

/**
 * A label with its age taken off the end.
 *
 * Most feeds here finish a label with how old the reading is — "· 40m
 * ago", "· in 3h" — and a point's identity is its label. So at the
 * minute boundary every one of those markers became, as far as this
 * code could tell, a different marker: the Marker that had been
 * carefully reused was dropped and rebuilt, and the thinning hash
 * moved, so in any layer over its quota the surviving subset changed
 * and stationary markers blinked in and out once a minute.
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

/** A bucket for a point on the globe, belonging to the point rather
 *  than to its place in a list. */
internal fun globeBucket(point: GlobePoint): Int = bucketOf(point.kind, point.identity)

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
private fun bucketOf(kind: String, identity: String): Int {
    var h = (kind.hashCode() * 31) xor identity.hashCode()
    h = h xor (h ushr 16)
    h *= -0x7ee3623b
    h = h xor (h ushr 13)
    return ((h.toLong() and 0xFFFFFFFFL) % MARKER_BUCKETS).toInt()
}

/**
 * The sphere projects everything it is handed on every frame, so this
 * is what can be projected in a frame and not what can be seen.
 *
 * Each point costs a projection, a day-or-night test and a draw; two
 * and a half thousand markers on a disc a thousand pixels across is
 * already more than anyone can tell apart, and the ones over the
 * horizon cost nothing because the draw culls them.
 */
internal const val GLOBE_BUDGET = 2500
