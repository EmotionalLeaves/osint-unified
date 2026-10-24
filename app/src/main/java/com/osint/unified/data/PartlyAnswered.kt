package com.osint.unified.data

/**
 * A feed built from several agencies, and which of them were missing
 * from the last answer.
 *
 * `feedTrouble` is a map of layer to one string, so a layer has one
 * boolean's worth of honesty: it answered or it did not. That is the
 * right shape for a feed with one source behind it and the wrong
 * shape for the six that merge several. Two of three seismic
 * agencies unreachable reads as a clean layer; one of twenty-four
 * METAR boxes answering reads as a clean layer; the Atlantic
 * answering while the whole west Pacific is dark reads as a clean
 * layer. The reader is then told a count and given no reason to
 * doubt it.
 *
 * The project has met this before — `migrateIfNeeded` returning
 * `String?` could not say "moved" and caused the same catastrophic
 * failure twice — and the lesson was that a signature which cannot
 * express the state will keep producing the bug. So a source that
 * merges agencies can say who was missing, and the poll puts it
 * where the pill and the brief can read it.
 *
 * Null means everybody answered. It is set on every successful
 * fetch, including back to null, so it never describes a fetch
 * before last.
 */
interface PartlyAnswered {
    val lastGaps: String?
}

/** Phrases a list of failures as "n of m did not answer: reasons". */
internal fun gapsFrom(
    total: Int,
    failures: List<Throwable?>,
    what: String
): String? {
    if (failures.isEmpty()) return null
    val reasons = failures.mapNotNull { it?.message?.take(60) }.distinct().take(3)
    return buildString {
        append("${failures.size} of $total $what did not answer")
        if (reasons.isNotEmpty()) append(": " + reasons.joinToString("; "))
    }
}
