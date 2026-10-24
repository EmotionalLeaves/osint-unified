package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow

/**
 * Reads every tool's raw output and pulls out the identifiers worth chasing
 * next — emails, hosts, IPs, handles, wallets, hashes, coordinates, phone
 * numbers, tail numbers.
 *
 * This is what makes one search lead to the next: a domain lookup surfaces an
 * admin email, that email surfaces a username, that username surfaces a
 * Keybase proof. Each pivot is a one-tap new investigation.
 */
object EntityExtractor {

    data class Pivot(
        val kind: String,
        val value: String,
        val count: Int,
        /**
         * True when the app worked this value out of a longer one
         * rather than the source stating it.
         *
         * "Vienna, Austria" is a claim about a city and implies one
         * about a country, and that implication is what lets a source
         * saying only "AT" agree with it — but it must not be able to
         * CONTRADICT anything. An IP whose prefix is registered in
         * the United States and whose geolocation reads Frankfurt,
         * Hesse, Germany is the ordinary state of the internet, and
         * both answers were graded improbable because the country
         * split out of the second looked like a rival of the first. A
         * derived value corroborates and does not refute.
         */
        val derived: Boolean = false
    )

    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,24}""")
    private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private val host = Regex("""\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+(?:com|net|org|io|dev|app|co|uk|de|fr|ru|cn|jp|edu|gov|mil|info|biz|me|xyz|cloud|ai|tech|site|online|us|ca|au|nl|se|no|ch|it|es|pl|br|in|za)\b""")
    private val btc = Regex("""\b(?:bc1[a-z0-9]{25,62}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})\b""")
    private val eth = Regex("""\b0x[a-fA-F0-9]{40}\b""")
    private val md5 = Regex("""\b[a-fA-F0-9]{32}\b""")
    private val sha256 = Regex("""\b[a-fA-F0-9]{64}\b""")
    private val phone = Regex("""\+\d{1,3}[\s-]?\(?\d{2,4}\)?[\s-]?\d{3,4}[\s-]?\d{3,4}""")
    private val coord = Regex("""(-?\d{1,2}\.\d{3,8})\s*,\s*(-?\d{1,3}\.\d{3,8})""")
    private val handle = Regex("""(?:twitter\.com|x\.com|instagram\.com|github\.com|t\.me|tiktok\.com/@|facebook\.com|reddit\.com/user|linkedin\.com/in)/([A-Za-z0-9_.-]{2,30})""")
    // Two digits at least, like the router's own tail pattern. One was
    // enough to make "N5" and any other N-prefixed token an aircraft
    // registration, and a pivot the investigator then has to rule out
    // by hand is worse than no pivot.
    private val tail = Regex("""\bN[0-9]{2,5}[A-Z]{0,2}\b""")

    /**
     * Hosts that are never the target, matched by registrable domain.
     *
     * These are the app's own plumbing: the sites it calls, and the
     * services whose links it prints. Offering one as a pivot invites the
     * person to open a dossier on a CDN — "storage13.openstreetcam.org"
     * was appearing as a lead because a photo link happened to be served
     * from there. Matching by suffix rather than by exact host is what
     * catches that whole family at once.
     */
    private val noiseDomains = setOf(
        "github.com", "twitter.com", "x.com", "google.com", "googleapis.com",
        "yandex.com", "bing.com", "tineye.com", "wikipedia.org", "wikidata.org",
        "wikimedia.org", "archive.org", "archive.ph", "archive.today",
        "keybase.io", "ycombinator.com", "reddit.com", "t.me", "telegram.org",
        "nist.gov", "exploit-db.com", "osintframework.com", "openstreetmap.org",
        "openstreetcam.org", "kartaview.org", "mapillary.com", "insecam.org",
        // registrable() keeps two labels outside its short list of
        // two-part suffixes, so an entry with three can never match
        // anything: "panoramax.openstreetmap.fr" arrives here as
        // "openstreetmap.fr" and "dot.ca.gov" as "ca.gov". Both are
        // this app's own source domains and were being emitted as
        // pivots. "nhc.noaa.gov" was redundant with noaa.gov.
        "panoramax.xyz", "openstreetmap.fr", "ca.gov",
        "earthcam.com", "crt.sh", "certspotter.com", "urlscan.io", "shodan.io",
        "hackertarget.com", "rapiddns.io", "blockchain.com", "blockchain.info",
        "etherscan.io", "ethplorer.io", "oxt.me", "jetphotos.com", "adsb.lol",
        "adsbexchange.com", "flightaware.com", "sec.gov", "fbi.gov",
        "gleif.org", "xposedornot.com", "haveibeenpwned.com", "suncalc.org",
        "open-meteo.com", "police.uk", "hexdb.io", "gravatar.com",
        "example.com", "dns.google", "first.org", "circl.lu",
        "securitytxt.org", "owasp.org", "treas.gov", "torproject.org",
        "alienvault.com", "ripe.net", "bgpview.io", "rdap.org", "ipwho.is",
        "stackexchange.com", "stackoverflow.com", "npmjs.com", "docker.com",
        "gitlab.com", "bitbucket.org", "mastodon.social", "bsky.app",
        "noaa.gov", "tsunami.gov", "faa.gov", "safecast.org",
        "nasa.gov", "usgs.gov", "gdacs.org", "gdeltproject.org",
        "commoncrawl.org", "hudsonrock.com", "openpgp.org", "ubuntu.com",
        "w3.org", "schema.org", "creativecommons.org", "gstatic.com",
        "cloudflare.com", "amazonaws.com", "akamaized.net", "cloudfront.net"
    )


    /** The registrable part of a host, roughly — enough to match a family
     *  of subdomains without shipping a public-suffix list. */
    private fun registrable(host: String): String {
        val parts = host.split(".")
        if (parts.size < 2) return host
        val last = parts.takeLast(2).joinToString(".")
        // Two-part public suffixes that would otherwise collapse to the
        // suffix itself and match everything under it.
        val twoPart = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au",
            "co.jp", "com.br", "co.za", "com.cn", "co.in", "gov.au")
        return if (last in twoPart && parts.size >= 3) parts.takeLast(3).joinToString(".") else last
    }

    /**
     * Pivots across all successful results, most-corroborated first.
     *
     * [seedIsPlace] drops host pivots entirely: a search for a coordinate
     * turns up the hostnames of whatever services were consulted about it,
     * and not one of them is a lead about the place.
     */
    fun extract(
        results: List<ResultRow>,
        seed: String,
        limitPerKind: Int = 12,
        seedIsPlace: Boolean = false
    ): List<Pivot> {
        // Only rows that found something.
        //
        // Filtering on the status alone meant "no account named X at
        // github.com/x" was mined for identifiers like any other
        // answer, so the dossier's IDENTIFIERS DISCOVERED list carried
        // pivots taken out of sentences saying the thing does not
        // exist. The corroboration pass beside it uses the stricter
        // test, so the two lists disagreed with each other.
        //
        // Snapshots are left out as well: they store a hash of the
        // page they captured, which this then extracted as a SHA-256
        // belonging to the target. So are the other nine tools whose
        // output is a copy of somebody else's page — a crawl, a web
        // search, a news query. This excluded "snapshot" alone, so a
        // crawled page's cdn host, contact address and copyright year
        // all arrived in IDENTIFIERS DISCOVERED as things found out
        // about the target. The list lives in ToolRegistry now,
        // because keeping three copies of it is how they came to
        // disagree.
        val text = results
            .filter {
                Findings.kindOf(it) == Findings.Kind.HIT &&
                    it.toolId !in ToolRegistry.QUOTES_OTHERS
            }
            .joinToString("\n") { it.rawOutput }
        if (text.isBlank()) return emptyList()
        val seedLower = seed.trim().lowercase()
        val out = ArrayList<Pivot>()

        fun collect(kind: String, regex: Regex, limit: Int = limitPerKind, transform: (MatchResult) -> String? = { it.value }) {
            val counts = LinkedHashMap<String, Int>()
            regex.findAll(text).forEach { m ->
                val v = transform(m)?.trim()?.trimEnd('.', ',', ')', ';') ?: return@forEach
                if (v.isBlank() || v.lowercase() == seedLower) return@forEach
                // The seed, written to a different number of places,
                // is still the seed.
                //
                // Several coordinate tools echo the point back in
                // their own header at their own precision —
                // histweather and ukcrime at four decimals,
                // photosnear and sunterrain at five — and the guard
                // above is an exact string match. Type
                // "51.5074,-0.1278" with no space and they print
                // "51.5074, -0.1278", so the user's own input came
                // back as a COORD entity asserted by two independent
                // B-grade sources and was graded B1, "confirmed by
                // other sources": the dossier reporting the question
                // as one of its findings.
                if (kind == "COORD" && Coordinates.same(v, seedLower)) return@forEach
                counts[v] = (counts[v] ?: 0) + 1
            }
            counts.entries.sortedByDescending { it.value }.take(limit).forEach {
                out += Pivot(kind, it.key, it.value)
            }
        }

        collect("EMAIL", email)
        collect("HANDLE", handle) { it.groupValues.getOrNull(1) }
        if (!seedIsPlace) {
            collect("HOST", host, limitPerKind) { m ->
                val v = m.value.lowercase()
                when {
                    registrable(v) in noiseDomains -> null
                    // The same list Corroboration grades against.
                    //
                    // Corroboration knows twenty-six CDN and cloud
                    // families and this knew five, and the exporter
                    // calls extract() directly — so IDENTIFIERS
                    // DISCOVERED carried cdn.jsdelivr.net,
                    // lh3.googleusercontent.com, *.fastly.net and
                    // *.b-cdn.net as things found out about the
                    // target, off the back of any page that loads
                    // Bootstrap.
                    Corroboration.isInfrastructure(v) -> null
                    v.endsWith(".png") || v.endsWith(".jpg") -> null
                    else -> v
                }
            }
        }
        collect("IP", ipv4) { m ->
            // Drop the obvious non-routables, they are never worth a pivot.
            val v = m.value
            if (v.startsWith("0.") || v.startsWith("127.") || v == "255.255.255.255") null else v
        }
        collect("PHONE", phone, 6)
        collect("WALLET", btc, 6)
        collect("WALLET", eth, 6)
        collect("TAIL", tail, 6)
        collect("COORD", coord, 6) { "${it.groupValues[1]}, ${it.groupValues[2]}" }
        collect("SHA256", sha256, 4)
        collect("MD5", md5, 4)

        out += labelled(results, seedLower, limitPerKind)
        return out
    }

    // ------------------------------------------------------------------
    // What the sources say about a person, rather than about a machine
    // ------------------------------------------------------------------

    /**
     * Labels this app's own tools print, and what each one asserts.
     *
     * Everything above this line is a machine identifier, and that was
     * the whole of what the corroboration engine could see — while the
     * sources added most recently return almost nothing else: chess,
     * lichess, codeforces, codeberg, Hugging Face, GitHub, ORCID and
     * the nonprofit registry all emit a declared name, an employer, a
     * city or a country. So the single most valuable thing a handle
     * search can produce — five platforms independently saying Vienna
     * — never reached the engine built to say exactly that, and for a
     * person's name, which routes to fourteen fetching tools, the
     * corroborated section was near-empty by construction. The analyst
     * read six cards and did the merge in their head.
     *
     * This is a parse and not any kind of guessing at prose: these
     * lines are `Label: value`, written by this app, one per line.
     */
    private val PERSON_LABELS = setOf(
        "declared name", "full name", "real name", "display name", "legal name"
    )
    private val ORG_LABELS = setOf(
        "company", "organisation", "organization", "declared organisation",
        "declared organization", "institution", "institutions", "employer",
        "affiliation", "registrant organization", "registrant organisation",
        "org", "works at"
    )
    private val PLACE_LABELS = setOf(
        "location", "declared location", "country", "declared country",
        "city", "declared city", "based in", "hometown",
        "registrant country", "place"
    )

    /**
     * Tools whose subject is a person or an account they hold, where a
     * bare `Name:` is a claim about a person.
     *
     * Everywhere else it is the name of a company, an autonomous
     * system or a certificate holder, and filing those as PERSON would
     * put an ASN operator in the list of what this person is called.
     */
    private val NAME_MEANS_PERSON = setOf("username", "name", "email", "image")

    /**
     * Tools whose subject can have an employer or a membership.
     *
     * Everywhere else an `Org:` or a `Company:` line is the operator
     * of an autonomous system, the holder of a certificate or the
     * registrar of a domain — facts about infrastructure. An IP
     * dossier was reporting "Cloudflare, Inc." as an organisation
     * discovered about the subject, which is the same mistake
     * NAME_MEANS_PERSON was added to stop `Name:` making.
     */
    private val ORG_MEANS_AFFILIATION =
        setOf("username", "name", "email", "image", "url")

    /**
     * A labelled claim about the subject, at the left margin.
     *
     * Indented and bulleted lines are deliberately excluded, because
     * that is how every tool here writes a list of CANDIDATES.
     * `ghname` reads five different GitHub accounts that merely carry
     * the searched name and prints "  Name:", "  Company:" and
     * "  Location:" under each, and its own last line says they are
     * not the same person until something ties them together. Reading
     * those gave two strangers who both live in London a corroborated
     * finding, made every candidate's name contradict every other,
     * and let the third unrelated account's city become the dossier's
     * headline location. `orcid` does the same with twenty
     * researchers and `torRelay` with five relays in five countries.
     * The indentation was already carrying the distinction; nothing
     * was reading it.
     */
    private val labelLine =
        Regex("""^([A-Za-z][A-Za-z /'-]{1,28}):[ \t]*(.+?)\s*$""")

    /** Values that are the source declining to say, not an answer. */
    private val emptyValues = setOf(
        "n/a", "na", "none", "null", "unknown", "not set", "not stated",
        "not specified", "not provided", "undisclosed", "redacted",
        "private", "-", "--", "?", "(none)", "(not set)", "hidden"
    )

    private fun labelled(
        results: List<ResultRow>,
        seedLower: String,
        limitPerKind: Int
    ): List<Pivot> {
        val counts = LinkedHashMap<Triple<String, String, Boolean>, Int>()
        results.forEach { row ->
            if (Findings.kindOf(row) != Findings.Kind.HIT) return@forEach
            if (row.toolId in ToolRegistry.QUOTES_OTHERS) return@forEach
            val inputType = ToolRegistry.byId(row.toolId)?.inputType.orEmpty()
            row.rawOutput.lineSequence().take(400).forEach inner@{ line ->
                val m = labelLine.find(line) ?: return@inner
                val label = m.groupValues[1].trim().lowercase()
                val raw = m.groupValues[2].trim().trimEnd('.', ';', ',')
                if (raw.length < 2 || raw.length > 80) return@inner
                if (raw.lowercase() in emptyValues) return@inner
                if (raw.lowercase() == seedLower) return@inner
                // An address, a link or a number is already collected
                // above as the thing it is.
                if (raw.contains('@') || raw.contains("http", true)) return@inner
                if (raw.none { it.isLetter() }) return@inner
                val kind = when {
                    label in PERSON_LABELS -> "PERSON"
                    label == "name" && inputType in NAME_MEANS_PERSON -> "PERSON"
                    label in ORG_LABELS && inputType in ORG_MEANS_AFFILIATION -> "ORG"
                    label in PLACE_LABELS -> "PLACE"
                    else -> return@inner
                }
                valuesOf(kind, raw).forEach { (v, derived) ->
                    if (v == seedLower) return@forEach
                    val k = Triple(kind, v, derived)
                    counts[k] = (counts[k] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .groupBy { it.key.first }
            .flatMap { (_, entries) ->
                entries.sortedByDescending { it.value }
                    .take(limitPerKind)
                    .map { Pivot(it.key.first, it.key.second, it.value, it.key.third) }
            }
    }

    /**
     * One written value as the things it asserts.
     *
     * A place is also a claim about the country it is in, which is
     * what lets a source that only says "AT" agree with one that says
     * "Vienna, Austria". A name and an employer are counted whole:
     * splitting "Smith, Jones and Partners" on its comma would invent
     * two firms.
     */
    internal fun valuesOf(kind: String, raw: String): List<Pair<String, Boolean>> =
        when (kind) {
            "PLACE" -> {
                // The whole is what the source said; everything after
                // it this app worked out by splitting on a comma.
                Places.parts(raw).mapIndexed { index, value -> value to (index > 0) }
            }
            "PERSON" -> listOfNotNull(PersonName.normalise(raw)).map { it to false }
            else -> listOfNotNull(
                raw.lowercase().replace(Regex("\\s+"), " ").trim()
                    // A semicolon-joined list of institutions is a
                    // list, and joining it into one name invents an
                    // organisation nobody belongs to.
                    .takeIf { it.length in 2..60 && !it.contains(';') }
            ).map { it to false }
        }
}
