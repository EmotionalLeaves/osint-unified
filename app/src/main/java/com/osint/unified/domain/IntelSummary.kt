package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow
import java.util.Locale

/**
 * Synthesizes a handful of headline facts across every tool result so the
 * dossier can show a quick glance ("located in …", "14 subdomains", "6 news
 * hotspots") instead of making the user read each card. Purely derived from
 * text the tools already returned; always defensive.
 */
object IntelSummary {

    data class Fact(val label: String, val value: String)

    private val locationRegex = Regex("""Location:\s*(.+)""")
    private val numberRegex = Regex("""\d[\d,]*""")

    fun synthesize(results: List<ResultRow>): List<Fact> {
        val ok = results.filter { it.status == "success" }
        // Rows that speak for themselves, as opposed to rows that
        // quote somebody else's page. Defined here rather than fifty
        // lines down, because the LOCATION block below was reading
        // `ok` — the comment that explains this filter sat underneath
        // the two extractions that most needed it. Snapshot any page
        // containing the literal string "Location: Moscow, Russia" —
        // job adverts, event pages, header dumps all do — while
        // investigating an unrelated handle, and the dossier's own
        // summary printed LOCATION Moscow, Russia at the top, in the
        // report's voice. Results arrive newest first, so a fresh
        // snapshot beat a real geocode to it.
        val structured = ok.filterNot { it.toolId in QUOTES_OTHERS }
        val facts = mutableListOf<Fact>()

        // Location: prefer a named place, else fall back to raw coordinates.
        val location = structured.firstNotNullOfOrNull { r ->
            locationRegex.find(r.rawOutput)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("null", true) && it.replace(",", "").isNotBlank() }
        }
        if (location != null) {
            facts += Fact("LOCATION", location)
        } else {
            structured.firstNotNullOfOrNull { GeoExtractor.firstCoordinate(it.rawOutput) }?.let { (lat, lon) ->
                facts += Fact("COORDINATE", "%.4f, %.4f".format(Locale.US, lat, lon))
            }
        }

        // NDJSON tools: count comes from the formatter headline.
        linkedMapOf(
            "subfinder" to "SUBDOMAINS",
            "httpx" to "LIVE HOSTS",
            "overpass_turbo" to "MAP FEATURES"
        ).forEach { (id, label) ->
            val row = ok.firstOrNull { it.toolId == id } ?: return@forEach
            val n = numberRegex.find(ResultFormatter.format(id, row.rawOutput).headline)?.value
            if (!n.isNullOrBlank() && n != "0") facts += Fact(label, n)
        }

        // Facts are read out of what a tool said about the target, and
        // not out of what a tool quoted from somewhere else.
        //
        // These patterns used to be matched against every successful
        // row. Several tools store fetched page text verbatim — a page
        // snapshot, a crawl, a web search, a news query — so any page
        // that happened to contain the right phrase was promoted
        // straight into this summary as though a tool had established
        // it. Snapshot a news article about an OFAC designation while
        // looking at an unrelated person and the dossier's own summary
        // block prints SANCTIONED for them.
        //
        // The exporter already takes this seriously: it prefixes every
        // line of a source's output with a bar so that "nothing a
        // source wrote can be mistaken for part of this report". This
        // was reading the same text and printing it unprefixed, in the
        // report's own voice, at the top.
        fun grab(label: String, regex: Regex, from: List<ResultRow> = structured) {
            from.firstNotNullOfOrNull { regex.find(it.rawOutput)?.groupValues?.getOrNull(1)?.trim() }
                ?.takeIf { it.isNotBlank() }?.let { facts += Fact(label, it) }
        }

        /** For the handful where a false positive is an accusation. */
        fun only(toolId: String): List<ResultRow> = ok.filter { it.toolId == toolId }
        grab("ASN", Regex("""ASN:\s*(AS[\w ]+)"""))
        grab("ISP", Regex("""ISP:\s*(.+)"""))
        grab("OPEN PORTS", Regex("""Open ports:\s*(.+)"""))
        grab("TLS EXPIRES IN", Regex("""Days remaining:\s*(-?\d+)""").let { it })
        grab("HTTP HEADERS", Regex("""Security headers:\s*(\d+/\d+)"""))
        grab("SPOOFING RISK", Regex("""Spoofing risk:\s*(\w+)"""))
        grab("REGISTERED", Regex("""Registration:\s*([\d-]+)"""))
        grab("PTR", Regex("""PTR:\s*(.+)"""))
        if (structured.any { it.rawOutput.contains("IS a Tor exit node") }) {
            facts += Fact("TOR EXIT", "yes")
        }
        structured.firstNotNullOfOrNull {
            Regex("""Covers (\d+) hostnames""").find(it.rawOutput)?.groupValues?.get(1)
        }?.let { facts += Fact("CERT COVERS", it) }
        structured.firstNotNullOfOrNull {
            Regex("""Vulnerabilities:\s*(.+)""").find(it.rawOutput)?.groupValues?.get(1)
        }?.let { facts += Fact("CVES", it.split(",").size.toString()) }

        // ---- Headline findings from the deep keyless sources ----
        grab("BREACHED IN", Regex("""Found in (\d+) public breaches"""))
        grab("BREACH RISK", Regex("""Risk label:\s*(\w+)"""))
        // These two name a person as a sanctions target or a subject
        // of an FBI notice. Nothing but the tool that actually
        // searched those lists may put them here.
        grab(
            "SANCTIONED",
            Regex("""Specially Designated Nationals\s*—\s*(\d+) match"""),
            only("sanctions")
        )
        grab(
            "FBI LIST",
            Regex("""seeking information\s*—\s*'[^']*'\s*\((\d+) match"""),
            only("fbi")
        )
        grab("LEI", Regex("""LEI:\s*(\S+)"""))
        grab("CIK", Regex("""\(CIK (\d+)\)"""))
        grab("AIRCRAFT OWNER", Regex("""Registered owner:\s*(.+)"""))
        grab("AIRCRAFT TYPE", Regex("""Type:\s*(.+)"""))
        grab("WALLET BALANCE", Regex("""Balance:\s*([\d.]+ (?:BTC|ETH))"""))
        grab("UNIQUE HOSTS", Regex("""(\d+) unique hosts"""))
        grab("EXPLOIT ODDS", Regex("""EPSS:\s*([\d.]+%)"""))
        grab("CAMERAS NEARBY", Regex("""Public cameras within 50 km[^—]*—\s*(\d+) found"""))
        grab("OFFENCES", Regex("""(\d+) offences reported"""))
        grab("SUN ANGLE", Regex("""Elevation:\s*(-?[\d.]+°)"""))
        grab("GROUND ELEV", Regex("""Ground elevation:\s*(\d+ m)"""))
        grab("MGRS", Regex("""MGRS:\s*(\S+)"""))
        grab("TIMEZONE HINT", Regex("""(?:Posting|Commit) peak:\s*(\d+:00 UTC)"""))
        grab("KARMA", Regex("""Karma:\s*([\d,]+)"""))

        // Proven identities are the strongest single signal a username search
        // can return, so count them rather than just noting the tool ran.
        ok.firstOrNull { it.toolId == "keybase" }?.let { row ->
            val n = row.rawOutput.lines().count { it.startsWith("• ") }
            if (n > 0) facts += Fact("PROVEN ACCOUNTS", n.toString())
        }
        ok.firstOrNull { it.toolId == "ghactivity" }?.let { row ->
            Regex("""Commit emails:\s*(.+)""").find(row.rawOutput)?.groupValues?.get(1)
                ?.split(",")?.size?.let { if (it > 0) facts += Fact("COMMIT EMAILS", it.toString()) }
        }

        // Text tools that state their own count in the first line.
        parseCount(ok, "crtsh", Regex("""—\s*(\d+)\s+names"""), "CERT NAMES", facts)
        parseCount(ok, "wikinearby", Regex("""places\s*\((\d+)\)"""), "NEARBY SITES", facts)
        parseCount(ok, "gdelt", Regex("""hotspots.*\((\d+)\)"""), "NEWS HOTSPOTS", facts)

        // Sources that returned something, not rows that did not throw.
        //
        // `ok` is every row whose status is "success", which includes
        // every tool that reached its source and was told there is
        // nothing there. Counted as answers, this printed SOURCES
        // 56/60 on the same page where the dossier's own assessment —
        // which goes through Findings — said "4 of 60 sources
        // returned something". One document, two numbers, five-fold
        // apart, and the generous one at the top.
        val found = results.count { Findings.kindOf(it) == Findings.Kind.HIT }
        facts += Fact("SOURCES", "$found/${results.size}")
        return facts
    }

    /**
     * Tools whose output is somebody else's words rather than their
     * own finding.
     *
     * A page snapshot, a crawl, a search, a news query: what is stored
     * is text fetched from elsewhere, and anyone who can get a phrase
     * onto a page the investigator looks at can get it into this list
     * otherwise. Their content still appears in the dossier — quoted,
     * attributed and bar-prefixed, which is the whole difference.
     */
    private val QUOTES_OTHERS = ToolRegistry.QUOTES_OTHERS

    private fun parseCount(
        ok: List<ResultRow>, toolId: String, regex: Regex, label: String, into: MutableList<Fact>
    ) {
        val row = ok.firstOrNull { it.toolId == toolId } ?: return
        val n = regex.find(row.rawOutput)?.groupValues?.getOrNull(1) ?: return
        if (n != "0") into += Fact(label, n)
    }
}
