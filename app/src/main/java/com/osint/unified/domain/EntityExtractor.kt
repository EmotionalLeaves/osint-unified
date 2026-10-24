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

    data class Pivot(val kind: String, val value: String, val count: Int)

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

    /**
     * Whether two written coordinates are the same place.
     *
     * Compared as numbers, rounded to four decimals — about eleven
     * metres, which is finer than any of these tools claims and
     * coarser than the difference between two spellings of one point.
     */
    private fun sameCoordinate(a: String, b: String): Boolean {
        fun parse(v: String): Pair<Double, Double>? {
            val parts = v.split(',').map { it.trim() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            return lat to lon
        }
        val one = parse(a) ?: return false
        val two = parse(b) ?: return false
        return Math.round(one.first * 10000) == Math.round(two.first * 10000) &&
            Math.round(one.second * 10000) == Math.round(two.second * 10000)
    }

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
                if (kind == "COORD" && sameCoordinate(v, seedLower)) return@forEach
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

        return out
    }
}
