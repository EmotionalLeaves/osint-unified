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

    /**
     * What a source meant, as a value rather than a guess.
     *
     * Reading English out of a sentence to decide whether something was
     * found is wrong in both directions: "Domain registered; WHOIS privacy:
     * registrant unavailable" was filed as unreachable because of one word,
     * and "No database exposures, but 4 open ports" was filed as an absence
     * because "No database" contains "no data". Either way a real finding
     * vanishes or a false one appears, and rewording one line in an executor
     * silently changes a verdict with no test to catch it.
     *
     * A tool that knows what it meant now says so, and the answer is stored
     * on the row. The prose reading below remains only for the tools that
     * have not been migrated yet, and every migration shrinks its job.
     */
    enum class Kind { HIT, ABSENT, UNREACHABLE }

    /** Row statuses that carry a stated verdict rather than an inferred one. */
    const val STATUS_ABSENT = "absent"
    const val STATUS_UNREACHABLE = "unreachable"

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
        "no mentions", "no transactions", "no aircraft", "no breach"
    )

    /**
     * Openings that mean the source could not be consulted at all.
     *
     * These arrive as successes because the executor returns a sentence
     * rather than throwing, and they were being filed as absences — so a
     * dossier could state that the UN consolidated sanctions list had been
     * checked and the target was clear when the list had never loaded.
     * Treating them as failures is the whole point of keeping "said no" and
     * "could not be asked" apart.
     */
    private val UNREACHABLE = listOf(
        "could not be read", "is not bundled", "not bundled in this build",
        "blocked the request", "could not load any", "rate limited",
        "rate-limited", "could not be reached", "no source answered"
    )

    /**
     * Whether [phrase] appears in [head] as a phrase, not as the start of a
     * longer word.
     *
     * "No database exposures, but 4 open ports" is a finding, and it was
     * being filed as an absence because "no database" begins with "no data".
     */
    private fun hasPhrase(head: String, phrase: String): Boolean {
        var from = 0
        while (true) {
            val i = head.indexOf(phrase, from)
            if (i < 0) return false
            val after = i + phrase.length
            val before = i - 1
            val okBefore = before < 0 || !head[before].isLetterOrDigit()
            val okAfter = after >= head.length || !head[after].isLetterOrDigit()
            if (okBefore && okAfter) return true
            from = i + 1
        }
    }

    /** True when a "successful" result is really a report of failure. */
    fun saysUnreachable(output: String): Boolean {
        val head = output.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        if (UNREACHABLE.any { hasPhrase(head, it) }) return true
        // A sweep that reached a fraction of its list is closer to "could
        // not ask" than to "looked and found nothing".
        return output.contains("This is not a clean sheet", ignoreCase = true)
    }

    /** True when a successful result is really a report of absence. */
    fun saysNothing(output: String): Boolean {
        val head = output.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        if (head.isBlank()) return true
        if (UNREACHABLE.any { hasPhrase(head, it) }) return false
        // A sweep that says in its own words that it is not a clean sheet
        // is not reporting an absence; it is reporting partial coverage.
        if (output.contains("This is not a clean sheet", ignoreCase = true)) return false
        return NOTHING.any { hasPhrase(head, it) }
    }

    /** A result that carries something, as opposed to one that errored or
     *  looked and found nothing. */
    fun isHit(row: ResultRow): Boolean = kindOf(row) == Kind.HIT

    /**
     * The verdict for a row: what the tool stated, if it stated one, and
     * only otherwise what its prose suggests.
     */
    fun kindOf(row: ResultRow): Kind = when (row.status) {
        "success" -> when {
            saysUnreachable(row.rawOutput) -> Kind.UNREACHABLE
            saysNothing(row.rawOutput) -> Kind.ABSENT
            else -> Kind.HIT
        }
        STATUS_ABSENT -> Kind.ABSENT
        else -> Kind.UNREACHABLE
    }

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

    /** Rows still in flight, which belong in none of the three. */
    fun pending(results: List<ResultRow>): List<ResultRow> =
        results.filter { it.status == "running" }

    fun sort(results: List<ResultRow>): Sorted {
        val hits = ArrayList<ResultRow>()
        val empty = ArrayList<ResultRow>()
        val failed = ArrayList<ResultRow>()
        results.forEach { r ->
            // A row still running is neither a finding nor an absence, and
            // counting it as a miss is how the dossier reported "NOTHING
            // FOUND (38 errored)" while thirty-eight tools were still
            // working.
            if (r.status == "running") return@forEach
            when (kindOf(r)) {
                Kind.HIT -> hits.add(r)
                Kind.ABSENT -> empty.add(r)
                Kind.UNREACHABLE -> failed.add(r)
            }
        }
        return Sorted(hits, empty, failed)
    }
}
