package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow

/**
 * What the case actually found, as things rather than as tool output.
 *
 * Nobody investigates by tool. The dossier was organised around this
 * app's own code — SOCMINT, NETINT, one card per source — which is the
 * arrangement that makes sense to whoever wrote it and to nobody else.
 * An investigator is looking at a person, their accounts, and their
 * infrastructure, and wants to know which of those are solid.
 *
 * So every result is read for the identifiers it asserts, the assertions
 * are merged, and each one carries the sources that made it. That is also
 * the only way to do the thing every OSINT framework puts at its centre
 * and this app did not do at all: corroboration. One source saying
 * something is a lead. Two independent sources saying the same thing is a
 * finding, and the difference has to be visible.
 */
object Corroboration {

    /**
     * One thing the case found, and everything known about how.
     *
     * [sources] are the tool ids that asserted it; [independent] is how
     * many of those count separately (see [INDEPENDENCE]).
     */
    data class Entity(
        val kind: String,
        val value: String,
        val sources: List<String>,
        val independent: Int,
        val reliability: Grading.Reliability,
        val credibility: Grading.Credibility,
        val mentions: Int,
        /** Why it is not being counted as a finding, when it is not. */
        val caveat: String? = null,
        /** The investigator marked this wrong. */
        val dismissed: Boolean = false,
        /** Their reason, when they gave one. */
        val dismissalReason: String = "",
        /**
         * The source's grade was lowered for this case because it has
         * been wrong here more often than it has been right.
         */
        val demoted: Boolean = false
    ) {
        val grade: String get() = Grading.code(reliability, credibility)

        val sourceNames: List<String>
            get() = sources.map { ToolRegistry.byId(it)?.name ?: it }
    }

    /**
     * Sources that are not independent of each other.
     *
     * Two tools reading the same upstream are one witness. Subfinder's
     * passive mode is largely certificate transparency, which is what
     * crt.sh is; gau and waybackurls both read the Wayback index; the
     * three username sweeps share most of their rulesets, so three of
     * them "agreeing" is one ruleset agreeing with itself. Counting those
     * as corroboration would manufacture confidence out of nothing, which
     * is worse than having no corroboration at all.
     */
    private val INDEPENDENCE: Map<String, String> = buildMap {
        listOf("crtsh", "subfinder", "tlscert").forEach { put(it, "certificates") }
        listOf("wayback", "waybackurls", "gau", "archive", "commoncrawl")
            .forEach { put(it, "web-archives") }
        listOf("sherlock", "maigret", "blackbird", "socialsweep", "emailaccounts")
            .forEach { put(it, "username-sweep") }
        listOf("dns", "ptr", "subsweep").forEach { put(it, "dns") }
        listOf("breaches", "breachcat", "hudsonrock", "h8mail").forEach { put(it, "breach-data") }
        listOf("sanctions", "unsanctions", "opensanctions").forEach { put(it, "sanctions") }
        listOf("github", "ghactivity", "emailgithub").forEach { put(it, "github") }
    }

    private fun family(toolId: String): String = INDEPENDENCE[toolId] ?: toolId

    /**
     * Addresses that belong to the machinery rather than to a person.
     *
     * A registrar's privacy proxy, an abuse mailbox and a no-reply sender
     * are all real addresses that appear in real records, and all three
     * are worthless as leads. They were being offered as identifiers to
     * investigate, which sends someone chasing a hosting company.
     */
    private val MAILBOX_NOISE = setOf(
        "abuse", "postmaster", "hostmaster", "webmaster", "noreply", "no-reply",
        "donotreply", "do-not-reply", "support", "admin", "info", "contact",
        "security", "dns-admin", "registrar"
    )

    private val PRIVACY_PROXIES = listOf(
        "whoisguard", "domainsbyproxy", "privacyprotect", "withheldforprivacy",
        "privacyguardian", "contactprivacy", "perfectprivacy", "anonymize",
        "redacted", "data-protected", "whoisprivacy", "identity-protect"
    )

    /**
     * Hosts that are somebody's infrastructure and never the target's.
     *
     * A CDN edge or a cloud bucket tells you where a file was served
     * from, which is not a lead about a person — and these turn up in
     * nearly every crawl, so without this they dominate the list.
     */
    private val INFRASTRUCTURE = listOf(
        "cloudflare", "cloudfront", "akamai", "akamaiedge", "akamaized",
        "fastly", "amazonaws", "azureedge", "azurewebsites", "windows.net",
        "googleusercontent", "gstatic", "ggpht", "cdn77", "stackpathdns",
        "edgekey", "edgesuite", "llnwd", "bunnycdn", "b-cdn", "jsdelivr",
        "unpkg", "cdnjs", "gravatar", "wp.com", "shopifycdn", "squarespace"
    )

    /** Why this identifier is machinery rather than a lead, or null. */
    internal fun noise(kind: String, value: String): String? {
        val v = value.lowercase()
        return when (kind) {
            "EMAIL" -> {
                val local = v.substringBefore('@')
                val domain = v.substringAfter('@', "")
                when {
                    local in MAILBOX_NOISE -> "a role mailbox, not a person"
                    PRIVACY_PROXIES.any { domain.contains(it) } ->
                        "a registrar's privacy proxy"
                    MAILBOX_NOISE.any { local.startsWith("$it.") || local.startsWith("$it-") } ->
                        "a role mailbox, not a person"
                    else -> null
                }
            }
            "HOST" -> if (INFRASTRUCTURE.any { v.contains(it) })
                "a content delivery or cloud host" else null
            else -> null
        }
    }

    /**
     * Merges every result into the things it asserts.
     *
     * [seed] is what was searched, which is never reported back as a
     * discovery of itself.
     */
    fun merge(
        results: List<ResultRow>,
        seed: String,
        seedIsPlace: Boolean = false,
        /** kind to lowercased value, for findings marked wrong, and why. */
        dismissed: Map<Pair<String, String>, String> = emptyMap()
    ): List<Entity> {
        val assertions = HashMap<Pair<String, String>, MutableList<Pair<String, Int>>>()

        results.forEach { row ->
            if (Findings.kindOf(row) != Findings.Kind.HIT) return@forEach
            EntityExtractor.extract(
                listOf(row), seed, limitPerKind = 40, seedIsPlace = seedIsPlace
            ).forEach { pivot ->
                val key = pivot.kind to pivot.value.lowercase()
                assertions.getOrPut(key) { mutableListOf() }.add(row.toolId to pivot.count)
            }
        }

        val unreliableHere = caughtOut(assertions, dismissed)

        return assertions.map { (key, claims) ->
            val (kind, value) = key
            val sources = claims.map { it.first }.distinct()
            val independent = sources.map { family(it) }.distinct().size
            // The best source that asserted it — the grade should reflect
            // the strongest witness, not an average that a weak one drags
            // down. A source this case has caught being wrong is read one
            // grade lower, which is the only way marking something wrong
            // can affect anything beyond the row it was marked on.
            val demoted = sources.any { family(it) in unreliableHere }
            val reliability = sources
                .mapNotNull { id ->
                    ToolRegistry.byId(id)?.reliability?.let {
                        if (family(id) in unreliableHere) Grading.demote(it) else it
                    }
                }
                .minOrNull() ?: Grading.Reliability.UNJUDGED
            val wrong = dismissed[key]
            val caveat = when {
                wrong != null -> "you marked this wrong"
                else -> noise(kind, value)
            }
            Entity(
                kind = kind,
                value = value,
                sources = sources,
                independent = independent,
                reliability = reliability,
                credibility = if (caveat != null) Grading.Credibility.DOUBTFUL
                else Grading.credibility(reliability, agreeing = independent - 1),
                mentions = claims.sumOf { it.second },
                caveat = caveat,
                dismissed = wrong != null,
                dismissalReason = wrong.orEmpty(),
                demoted = demoted && wrong == null
            )
        }.sortedWith(
            compareBy<Entity> { it.dismissed }
                .thenBy { it.caveat != null }
                .thenBy { it.credibility.code }
                .thenBy { it.reliability.code }
                .thenByDescending { it.mentions }
        )
    }

    /**
     * Which source families this case has caught being wrong.
     *
     * A source earns this by having at least two of its assertions
     * marked wrong *and* more wrong than kept. Two, because one bad hit
     * is an ordinary day for a username sweep and demoting on it would
     * make the grade a record of the last thing the investigator
     * clicked. More wrong than kept, because a source that also found
     * five things that stood up is not the problem.
     *
     * Families rather than tools: three sweeps sharing one ruleset
     * produce the same false hit, and demoting only the one that was
     * tapped would leave the other two still vouching for it.
     */
    private fun caughtOut(
        assertions: Map<Pair<String, String>, MutableList<Pair<String, Int>>>,
        dismissed: Map<Pair<String, String>, String>
    ): Set<String> {
        if (dismissed.isEmpty()) return emptySet()
        val wrong = HashMap<String, Int>()
        val kept = HashMap<String, Int>()
        assertions.forEach { (key, claims) ->
            val bucket = if (key in dismissed) wrong else kept
            claims.map { family(it.first) }.distinct().forEach {
                bucket[it] = (bucket[it] ?: 0) + 1
            }
        }
        return wrong.filter { (fam, bad) -> bad >= 2 && bad > (kept[fam] ?: 0) }.keys
    }

    /**
     * One line saying what the case amounts to.
     *
     * A dossier that opens with sixty tool cards makes the reader do the
     * summarising. This is the sentence they would have written.
     */
    fun assessment(entities: List<Entity>, results: List<ResultRow>): String {
        val confirmed = entities.count {
            it.caveat == null && it.credibility == Grading.Credibility.CONFIRMED
        }
        val leads = entities.count { it.caveat == null && it.credibility.code in 2..3 }
        val wrong = entities.count { it.dismissed }
        val sorted = Findings.sort(results)
        val parts = ArrayList<String>()
        if (confirmed > 0) parts.add("$confirmed corroborated")
        if (leads > 0) parts.add("$leads single-source")
        if (parts.isEmpty()) parts.add("nothing corroborated")
        if (wrong > 0) parts.add("$wrong marked wrong")
        return parts.joinToString(" · ") +
            " · ${sorted.hits.size} of ${results.size} sources returned something" +
            if (sorted.failed.isNotEmpty()) " · ${sorted.failed.size} unreachable" else ""
    }
}
