package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow

/**
 * Sorting a set of tool results into what was found and what was not.
 *
 * Nearly every source answers "no entity named X" with HTTP 200, so the
 * status column cannot tell a hit from a miss, and a dossier that trusts it
 * is mostly cards reporting absence. The screen and the exported report must
 * agree about which is which, so the judgement lives here rather than in
 * either of them.
 */
object Findings {

    /** Openings that mean the source looked and found nothing. */
    private val NOTHING = listOf(
        "no entry matching", "no registered legal entity", "no sanctioned entity",
        "no places matched", "no accounts found", "nothing matching", "no public data",
        "no account named", "not found in any", "no certificates found", "no cves found",
        "has nothing on", "no indexed pages", "no edits by", "no key published",
        "no record", "no results", "no data", "no matching", "not a known relay",
        "no outage alerts", "no uk crime data", "nothing reported near",
        "no military-tagged sites", "no mapped infrastructure", "no public camera",
        "no news coverage", "no geolocated news", "is not a recognised",
        "no subdomain", "nothing found", "no entity", "no bgp data",
        "no routing data", "no registrant matched", "no structured record",
        "no filings", "no photos", "no profile", "no repositories",
        "no mentions", "no transactions", "no aircraft", "no breach",
        "could not be read", "is not bundled"
    )

    /** True when a successful result is really a report of absence. */
    fun saysNothing(output: String): Boolean {
        val head = output.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        if (head.isBlank()) return true
        return NOTHING.any { head.contains(it) }
    }

    /** A result that carries something, as opposed to one that errored or
     *  looked and found nothing. */
    fun isHit(row: ResultRow): Boolean =
        row.status == "success" && !saysNothing(row.rawOutput)

    /**
     * Splits results three ways: what was found, what was looked for and
     * not found, and what could not be asked at all. The distinction
     * matters — "this source says no" and "this source was unreachable"
     * support opposite conclusions.
     */
    data class Sorted(
        val hits: List<ResultRow>,
        val empty: List<ResultRow>,
        val failed: List<ResultRow>
    )

    fun sort(results: List<ResultRow>): Sorted {
        val hits = ArrayList<ResultRow>()
        val empty = ArrayList<ResultRow>()
        val failed = ArrayList<ResultRow>()
        results.forEach { r ->
            when {
                r.status != "success" -> failed.add(r)
                saysNothing(r.rawOutput) -> empty.add(r)
                else -> hits.add(r)
            }
        }
        return Sorted(hits, empty, failed)
    }
}
