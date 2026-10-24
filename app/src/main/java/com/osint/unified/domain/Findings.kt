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
        "rate-limited", "could not be reached", "no source answered",
        // Found by reading what the executors actually print rather
        // than what this list assumed they print. "rate limiting" is
        // GDELT's own wording on a 429 and is a different string from
        // "rate limited"; "unreachable" is how certificate
        // transparency reports both of its sources failing; the two
        // TLS ones are a handshake that did not happen, which is not
        // a certificate that is absent.
        "unreachable", "rate limiting", "no tls handshake",
        "no certificate presented", "could not be completed",
        "did not answer", "nothing reachable", "inventory reachable",
        // Five phrasings of "could not be read" were here and the
        // sixth, which three tools actually print, was not — so
        // "Could not read https://x/y.jpg: timeout" and "Wikimedia
        // could not be asked about jsmith" were classified as
        // FINDINGS. A list of exact phrases loses this way every
        // time; the shape below is the answer, and these are the
        // stragglers it does not cover.
        "could not read", "could not be asked", "could not be opened",
        "answered http", "refused the", "was not read",
        "answered in an unexpected shape", "not reaching the internet"
    )

    /**
     * Openings that mean the tool was never asked a valid question.
     *
     * Nothing was established, so these are neither a finding nor an
     * absence — and they were being counted as both, at random. "Not
     * a coordinate: SW1A 1AA" was a FINDING because no noun in the
     * shape list follows; "Not a Bitcoin address: 3xyz" was an
     * ABSENT because "address" is in it. "Give an email address." was
     * a finding. A tool deliberately skipped because the subject is
     * an organisation was a finding.
     *
     * `Executors` already treats one of these — "Give at least
     * three" — as UNREACHABLE, which is the right answer and the
     * precedent: the tool could not be asked.
     */
    private val NOT_ASKED = listOf(
        "give a", "give an", "give the", "give at least", "give me",
        "not a coordinate", "not a bitcoin address",
        "not a recognised btc", "domain too short"
    )

    /**
     * The same idea, where the clause is not at the start.
     *
     * "IP reputation needs an IPv4 address." opens with the tool's
     * own name, so a prefix test misses it — and it then falls
     * through to HIT, because no noun in the shape list follows a
     * "no" or a "not". Matched as a phrase instead, with the word
     * boundaries [hasPhrase] applies.
     */
    private val NOT_ASKED_PHRASES = listOf(
        "needs an ipv4", "needs an ip address", "needs a coordinate",
        "too short to permute", "holds researchers, not organisations",
        "no author search was run", "reads as an organisation"
    )

    private fun saysNotAsked(head: String): Boolean =
        NOT_ASKED.any { head.startsWith(it) } ||
            NOT_ASKED_PHRASES.any { hasPhrase(head, it) }

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
        if (saysNotAsked(head)) return true
        // A sweep that reached a fraction of its list is closer to "could
        // not ask" than to "looked and found nothing".
        return output.contains("This is not a clean sheet", ignoreCase = true)
    }

    /** True when a successful result is really a report of absence. */
    fun saysNothing(output: String): Boolean {
        val head = output.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        // A tool that returned nothing at all has not reported an
        // absence. It has said nothing, which is what UNREACHABLE is
        // for — a blank answer was being filed as "looked and found
        // nothing", which is a claim about the world made out of
        // silence.
        if (head.isBlank()) return false
        if (UNREACHABLE.any { hasPhrase(head, it) }) return false
        if (saysNotAsked(head)) return false
        // A sweep that says in its own words that it is not a clean sheet
        // is not reporting an absence; it is reporting partial coverage.
        if (output.contains("This is not a clean sheet", ignoreCase = true)) return false
        if (NOTHING.any { hasPhrase(head, it) }) return true
        return NOTHING_SHAPE.containsMatchIn(head)
    }

    /**
     * The shape of a sentence that reports an absence.
     *
     * The list above is a list of exact phrases, and it was always
     * going to lose: a tool writes "No PTR record", the list holds
     * "no record", and the result is filed as a finding. Two dozen of
     * them were — "No DNS records found", "No urlscan.io scans for
     * x", "No PGP key published for y", "is not a Tor exit node" —
     * each one a source that looked, found nothing, and was counted
     * in "N of M sources returned something".
     *
     * This matches the shape instead: a "no" or "not" near the start,
     * then within a few words one of the nouns these tools actually
     * use for the thing they were looking for. Bounded so it cannot
     * run across a sentence, and checked against forty-five real
     * first lines — every absence in them matches and none of the
     * findings do.
     */
    private val NOTHING_SHAPE = Regex(
        "^[^;]{0,34}?\\b(?:no|not)\\b[^;:]{0,44}?\\b(?:" +
            "record|records|data|result|results|match|matches|matched|" +
            "account|accounts|entry|entries|entity|entities|key|keys|" +
            "profile|profiles|repositor\\w*|filing|filings|photo|photos|" +
            "mention|mentions|transaction|transactions|scan|scans|activity|" +
            "channel|channels|page|pages|site|sites|sitemap|alert|alerts|" +
            "coverage|place|places|camera|cameras|feed|feeds|cve|cves|" +
            "breach|breaches|aircraft|relay|relays|imagery|user|users|" +
            "address|addresses|node|exit|exits|found" +
            ")\\b"
    )

    /** A result that carries something, as opposed to one that errored or
     *  looked and found nothing. */
    fun isHit(row: ResultRow): Boolean = kindOf(row) == Kind.HIT

    /**
     * The verdict for a row: what the tool stated, if it stated one, and
     * only otherwise what its prose suggests.
     */
    fun kindOf(row: ResultRow): Kind = when (row.status) {
        "success" -> when {
            // Nothing at all is not a finding and not an absence.
            row.rawOutput.isBlank() -> Kind.UNREACHABLE
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
