package com.osint.unified.data

/**
 * Reads Wikipedia's "List of ongoing armed conflicts" into rows.
 *
 * Pure and Android-free, so it can be tested against a fixture shaped
 * like the real page rather than only against the live one.
 *
 * Why this page and not Wikidata: the conflict layer asked Wikidata for
 * conflicts with no recorded end date and called them current, which is
 * not the same thing — it once put the Second World War on a live map,
 * and later a 2017 skirmish beside this year's wars. Wikidata carries no
 * "still active" signal at all. This list does: every row carries the
 * deaths recorded *this year* and last, maintained by editors against
 * ACLED and UCDP tallies. A conflict with deaths this year is active, by
 * a measure somebody can check. UCDP and ACLED themselves now require an
 * account, which this app does not ask for.
 *
 * The page's shape, read off the live HTML: one `<tr>` per conflict, and
 * seven cells — start of the conflict as a whole, a tree of names whose
 * first link is the conflict and whose "(since 2021)" is the current
 * phase, continent, the countries as linked names, cumulative deaths,
 * and the two most recent years' deaths. Death counts carry citation
 * superscripts and sort keys in attributes, and are often ranges or
 * "N+".
 */
internal object OngoingConflicts {

    /** Deaths in one year, as the page states them. */
    data class Deaths(
        val year: Int,
        /** The lower bound: "21,994–80,000+" is at least 21,994. */
        val atLeast: Int,
        /** False for a range or an "N+", which are floors, not counts. */
        val exact: Boolean
    )

    data class Row(
        val name: String,
        /** The current phase, as the page puts it: "since 2021". */
        val phase: String?,
        val article: String?,
        /** When the conflict as a whole began — often decades ago, which is
         *  why it is kept off the label. */
        val began: Int?,
        val countries: List<String>,
        val thisYear: Deaths?,
        val lastYear: Deaths?
    )

    data class Parsed(
        val rows: List<Row>,
        /** Rows that looked like conflicts but did not have the shape. */
        val skipped: Int
    )

    fun parse(html: String, fallbackYear: Int): Parsed {
        val (lastYear, thisYear) = years(html, fallbackYear)
        val rows = ArrayList<Row>()
        val seen = HashSet<String>()
        var skipped = 0
        var at = 0
        while (true) {
            val open = html.indexOf("<tr", at)
            if (open < 0) break
            val close = html.indexOf("</tr>", open)
            if (close < 0) break
            at = close + 5
            val cells = cellsOf(html.substring(open, close))
            if (cells.isEmpty()) continue
            // A conflict row opens with the year it began. Anything else
            // — a header, a totals row, the other tables on the page — is
            // not a row this reader is for, and is passed over silently.
            val began = YEAR.find(text(cells[0]))?.value?.toIntOrNull()
            if (began == null || began !in 1500..thisYear) continue
            if (cells.size < 7) {
                // It began like a conflict and then did not have the
                // columns of one: a rowspan, or the page changed. Counted,
                // so the layer can say it is incomplete.
                skipped++
                continue
            }
            val nameCell = cells[1]
            val name = firstLinkText(nameCell)
                ?: text(nameCell).substringBefore("  ").trim().takeIf { it.isNotBlank() }
            if (name == null) {
                skipped++
                continue
            }
            if (!seen.add(name.lowercase())) continue
            val phase = PHASE.find(text(nameCell).take(200))?.groupValues?.get(1)
            val article = HREF.find(nameCell)?.groupValues?.get(1)
                ?.let { "https://en.wikipedia.org$it" }
            val countries = linkTexts(cells[3])
                .distinct()
                .toList()
            rows.add(
                Row(
                    name = name,
                    phase = phase,
                    article = article,
                    began = began,
                    countries = countries,
                    thisYear = deaths(cells[cells.size - 1], thisYear),
                    lastYear = deaths(cells[cells.size - 2], lastYear)
                )
            )
        }
        return Parsed(rows, skipped)
    }

    /**
     * Which two years the death columns are for, read off the headers.
     *
     * Not assumed to be this year and last: the page is edited by hand,
     * and in January it can still be headed with last year's pair. A
     * label saying "killed in 2027" over a 2026 count would be wrong in
     * exactly the way this layer exists to stop being.
     */
    private fun years(html: String, fallback: Int): Pair<Int, Int> {
        val found = TH.findAll(html)
            .map { text(it.groupValues[1]) }
            .filter { it.contains("fatalit", ignoreCase = true) }
            .mapNotNull { YEAR.find(it)?.value?.toIntOrNull() }
            .filter { it in 1990..fallback + 1 }
            .toSortedSet()
        val current = found.lastOrNull() ?: return (fallback - 1) to fallback
        val previous = found.headSet(current).lastOrNull() ?: (current - 1)
        return previous to current
    }

    private fun cellsOf(row: String): List<String> {
        val out = ArrayList<String>(8)
        var at = 0
        while (true) {
            val open = row.indexOf("<td", at)
            if (open < 0) break
            val body = row.indexOf('>', open)
            if (body < 0) break
            val close = row.indexOf("</td>", body)
            val end = if (close < 0) row.length else close
            out.add(row.substring(body + 1, end))
            if (close < 0) break
            at = close + 5
        }
        return out
    }

    private fun deaths(cell: String, year: Int): Deaths? {
        val t = text(cell)
        val m = NUMBER.find(t) ?: return null
        val n = m.value.replace(",", "").toIntOrNull() ?: return null
        val rest = t.removeRange(m.range).trim()
        // Exact only when nothing else is written beside the number.
        val exact = rest.isEmpty()
        return Deaths(year, n, exact)
    }

    /**
     * The first link in the cell that has words in it.
     *
     * Read through any markup inside the link. A name in italics —
     * `<a><i>Name</i></a>` — did not match a link of plain text, so the
     * first link found was the next one, which in this table is the
     * phase ("Phase 2"), and the conflict was named after that.
     */
    private fun firstLinkText(cell: String): String? =
        linkTexts(cell).firstOrNull()

    private fun linkTexts(cell: String): Sequence<String> =
        LINK.findAll(cell)
            .map { text(it.groupValues[1]) }
            .filter { it.isNotBlank() && it.any(Char::isLetter) }

    /** A cell's visible text: citations, styles and tags out, entities decoded. */
    internal fun text(html: String): String =
        decode(
            html.replace(SUP, " ")
                .replace(STYLE, " ")
                .replace(TAG, " ")
        ).replace(SPACE, " ").trim()

    private fun decode(s: String): String =
        s.replace(NUMERIC_ENTITY) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
            .replace("&nbsp;", " ")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    /**
     * The ISO code for a country as Wikipedia names it, where that name
     * is not the one the bundled centroids carry.
     *
     * The centroids are Natural Earth's, keyed by ISO code with short
     * names — "Dem. Rep. Congo", "S. Sudan", "Central African Rep." —
     * and Somaliland is not in them at all (its code is -99). Matching
     * Wikipedia's full names against those by text would have dropped a
     * third of Africa's wars off the globe. Null means "try the name as
     * written", which covers the many countries whose names agree.
     */
    internal fun isoFor(name: String): String? = ISO_ALIASES[name.trim().lowercase()]

    private val ISO_ALIASES = mapOf(
        "democratic republic of the congo" to "CD",
        "dr congo" to "CD",
        "republic of the congo" to "CG",
        "central african republic" to "CF",
        "south sudan" to "SS",
        "western sahara" to "EH",
        "sahrawi republic" to "EH",
        "sahrawi arab democratic republic" to "EH",
        // Not in Natural Earth's admin set; the fighting is in Somalia's
        // north, and Somalia's centroid is the nearest honest point.
        "somaliland" to "SO",
        "palestine" to "PS",
        "state of palestine" to "PS",
        "gaza strip" to "PS",
        "west bank" to "PS",
        "bosnia and herzegovina" to "BA",
        "dominican republic" to "DO",
        "ivory coast" to "CI",
        "côte d'ivoire" to "CI",
        "cote d'ivoire" to "CI",
        "united states" to "US",
        "kosovo" to "XK",
        "equatorial guinea" to "GQ",
        "solomon islands" to "SB",
        "czech republic" to "CZ",
        "eswatini" to "SZ",
        "east timor" to "TL",
        "timor-leste" to "TL",
        "north korea" to "KP",
        "south korea" to "KR",
        "myanmar" to "MM",
        "burma" to "MM",
        "turkey" to "TR",
        "türkiye" to "TR",
    )

    private val YEAR = Regex("""\b(1[5-9]\d\d|20\d\d)\b""")
    // Spaces allowed inside the brackets: the phase is a link, and the
    // tags around it become spaces when the cell is read as text.
    private val PHASE = Regex("""\(\s{0,3}(since \d{4})\s{0,3}\)""")
    private val LINK = Regex("""(?s)<a\b[^>]*>(.{1,400}?)</a>""")
    private val HREF = Regex("href=\"(/wiki/[^\"#]{1,200})\"")
    private val TH = Regex("""<th\b[^>]*>(.{0,400}?)</th>""", RegexOption.DOT_MATCHES_ALL)
    // Bounded: an unbounded group repetition is compiled recursively by
    // java.util.regex, and the pre-flight refuses it for that reason.
    private val NUMBER = Regex("""\d{1,3}(?:,\d{3}){1,4}|\d+""")
    private val SUP = Regex("""<sup\b.{0,600}?</sup>""", RegexOption.DOT_MATCHES_ALL)
    private val STYLE = Regex("""<style\b.{0,4000}?</style>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<[^>]{0,2000}>""")
    private val SPACE = Regex("""\s+""")
    private val NUMERIC_ENTITY = Regex("""&#(\d{1,7});""")
}
