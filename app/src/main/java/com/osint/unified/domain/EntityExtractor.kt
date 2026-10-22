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
    private val tail = Regex("""\bN[0-9]{1,5}[A-Z]{0,2}\b""")

    // Hosts that appear in nearly every result and are never the target.
    private val noise = setOf(
        "github.com", "twitter.com", "x.com", "google.com", "www.google.com",
        "lens.google.com", "yandex.com", "bing.com", "www.bing.com", "tineye.com",
        "wikipedia.org", "en.wikipedia.org", "wikidata.org", "www.wikidata.org",
        "archive.org", "web.archive.org", "keybase.io", "news.ycombinator.com",
        "reddit.com", "www.reddit.com", "t.me", "nvd.nist.gov", "exploit-db.com",
        "www.exploit-db.com", "osintframework.com", "openstreetmap.org",
        "nominatim.openstreetmap.org", "crt.sh", "urlscan.io", "shodan.io",
        "internetdb.shodan.io", "hackertarget.com", "api.hackertarget.com",
        "rapiddns.io", "blockchain.com", "www.blockchain.com", "etherscan.io",
        "jetphotos.com", "www.jetphotos.com", "adsb.lol", "globe.adsb.lol",
        "sec.gov", "www.sec.gov", "efts.sec.gov", "fbi.gov", "www.fbi.gov",
        "api.fbi.gov", "gleif.org", "www.gleif.org", "api.gleif.org",
        "xposedornot.com", "suncalc.org", "www.suncalc.org", "open-meteo.com",
        "data.police.uk", "oxt.me", "hexdb.io", "gravatar.com", "example.com",
        "dns.google", "cwwp2.dot.ca.gov", "first.org", "www.first.org",
        "cve.circl.lu", "securitytxt.org", "owasp.org", "treas.gov"
    )

    /**
     * Pivots across all successful results, most-corroborated first. [seed] is
     * the current target, which is dropped so it never pivots to itself.
     */
    fun extract(results: List<ResultRow>, seed: String, limitPerKind: Int = 12): List<Pivot> {
        val text = results.filter { it.status == "success" }.joinToString("\n") { it.rawOutput }
        if (text.isBlank()) return emptyList()
        val seedLower = seed.trim().lowercase()
        val out = ArrayList<Pivot>()

        fun collect(kind: String, regex: Regex, limit: Int = limitPerKind, transform: (MatchResult) -> String? = { it.value }) {
            val counts = LinkedHashMap<String, Int>()
            regex.findAll(text).forEach { m ->
                val v = transform(m)?.trim()?.trimEnd('.', ',', ')', ';') ?: return@forEach
                if (v.isBlank() || v.lowercase() == seedLower) return@forEach
                counts[v] = (counts[v] ?: 0) + 1
            }
            counts.entries.sortedByDescending { it.value }.take(limit).forEach {
                out += Pivot(kind, it.key, it.value)
            }
        }

        collect("EMAIL", email)
        collect("HANDLE", handle) { it.groupValues.getOrNull(1) }
        collect("HOST", host, limitPerKind) { m ->
            val v = m.value.lowercase()
            if (v in noise || v.endsWith(".png") || v.endsWith(".jpg")) null else v
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
