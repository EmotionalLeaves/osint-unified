package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow

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
        val facts = mutableListOf<Fact>()

        // Location: prefer a named place, else fall back to raw coordinates.
        val location = ok.firstNotNullOfOrNull { r ->
            locationRegex.find(r.rawOutput)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("null", true) && it.replace(",", "").isNotBlank() }
        }
        if (location != null) {
            facts += Fact("LOCATION", location)
        } else {
            ok.firstNotNullOfOrNull { GeoExtractor.firstCoordinate(it.rawOutput) }?.let { (lat, lon) ->
                facts += Fact("COORDINATE", "%.4f, %.4f".format(lat, lon))
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

        // Text tools that state their own count in the first line.
        parseCount(ok, "crtsh", Regex("""—\s*(\d+)\s+names"""), "CERT NAMES", facts)
        parseCount(ok, "wikinearby", Regex("""places\s*\((\d+)\)"""), "NEARBY SITES", facts)
        parseCount(ok, "gdelt", Regex("""hotspots.*\((\d+)\)"""), "NEWS HOTSPOTS", facts)

        facts += Fact("SOURCES", "${ok.size}/${results.size}")
        return facts
    }

    private fun parseCount(
        ok: List<ResultRow>, toolId: String, regex: Regex, label: String, into: MutableList<Fact>
    ) {
        val row = ok.firstOrNull { it.toolId == toolId } ?: return
        val n = regex.find(row.rawOutput)?.groupValues?.getOrNull(1) ?: return
        if (n != "0") into += Fact(label, n)
    }
}
