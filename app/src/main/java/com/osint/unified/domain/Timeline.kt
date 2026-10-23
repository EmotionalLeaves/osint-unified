package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When things happened, in one order.
 *
 * A dossier answers "what is there" and says nothing about "when". But
 * the dates are already in the answers — a certificate issued in March,
 * a breach in 2019, a domain registered the week before the account was
 * created, a photograph taken on a Tuesday — and laid end to end they
 * are frequently the whole story. Scattered across sixty result cards,
 * nobody ever sees them.
 *
 * Dates are read out of what the sources returned rather than asked for
 * separately, so this costs no requests and gains nothing the case did
 * not already know.
 */
object Timeline {

    data class Event(
        val date: LocalDate,
        val what: String,
        val toolId: String,
        val toolName: String,
        val reliability: Grading.Reliability
    ) {
        val year: Int get() = date.year
    }

    /**
     * Dates in the shapes these sources write them.
     *
     * Deliberately not "any number that could be a date": a run of
     * digits in a hash or an identifier is not a date, and a timeline
     * full of 1970 tells you only that something was parsed wrong.
     */
    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val SLASHED = Regex("""\b(\d{4})/(\d{2})/(\d{2})\b""")
    private val SPELLED = Regex(
        """\b(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val SPELLED_FIRST = Regex(
        """\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{1,2}),?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec"
    )

    /**
     * Lines whose date is about the thing, not about the request.
     *
     * Nearly every response carries a generated-at timestamp, an expiry,
     * a cache header. Those are facts about the lookup and putting them
     * on a timeline of a person's life is noise with a date on it.
     */
    private val IGNORE = listOf(
        "generated", "retrieved", "fetched", "queried", "as of", "captured:",
        // Both spellings. Only the hyphenated one was here, so the
        // HTTP header "Last Modified: 2020-01-01" — cache metadata
        // about a web request — passed on INTERESTING's "modified"
        // and landed on the subject's timeline as an event.
        "last-modified", "last modified",
        "cache", "valid until", "copyright",
        "snapshot date", "report date", "accessed"
    )

    /**
     * Checked after INTERESTING, not before it.
     *
     * IGNORE held "expires" and INTERESTING held "expires on", and
     * IGNORE won because it was tested first — so a domain's or a
     * certificate's expiry date, which INTERESTING lists in so many
     * words as worth showing, could never reach the timeline. These
     * are the phrases that are interesting DESPITE matching IGNORE.
     */
    private val DESPITE = listOf("expires on", "expiry", "expiration date")

    /** Words that make a date worth putting on a timeline. */
    private val INTERESTING = listOf(
        "registered", "created", "creation", "issued", "not before", "notbefore",
        "breach", "leaked", "added", "listed", "first seen", "firstseen",
        "joined", "since", "founded", "incorporated", "updated", "modified",
        "start", "began", "published", "filed", "taken", "date", "born",
        "expiry", "expires on", "expiration date", "renewed",
        "last seen", "lastseen"
    )

    /**
     * Everything dated that the case already holds.
     *
     * [results] are the stored rows; only those that found something are
     * read, because a source reporting an absence has no dates in it
     * that belong to anybody.
     */
    fun build(results: List<ResultRow>, limit: Int = 120): List<Event> {
        val today = LocalDate.now()
        val earliest = LocalDate.of(1970, 1, 1)
        val seen = HashSet<String>()
        val out = ArrayList<Event>()

        results.forEach { row ->
            if (Findings.kindOf(row) != Findings.Kind.HIT) return@forEach
            // Not from a page this app merely stored a copy of.
            //
            // A snapshot's status is "success", so it is a HIT, and
            // its body is whatever was on the page. EntityExtractor
            // excludes these and says why; IntelSummary excludes them
            // and says why; this did not. Snapshot a Wikipedia
            // article while investigating an unrelated handle and
            // "Founded 1923" became a dated event in that person's
            // life, printed in the report's own voice and graded B
            // for the snapshot tool's reliability. IGNORE caught the
            // snapshot's own "Captured:" header and nothing in its
            // body.
            if (row.toolId in ToolRegistry.QUOTES_OTHERS) return@forEach
            val tool = ToolRegistry.byId(row.toolId)
            row.rawOutput.lineSequence().take(MAX_LINES_PER_RESULT).forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.length > 300) return@forEach
                val lower = line.lowercase()
                val rescued = DESPITE.any { lower.contains(it) }
                if (!rescued && IGNORE.any { lower.contains(it) }) return@forEach
                if (INTERESTING.none { lower.contains(it) }) return@forEach
                val date = firstDate(line) ?: return@forEach
                if (date.isBefore(earliest) || date.isAfter(today.plusYears(30))) return@forEach

                val what = tidy(line)
                // The same fact arriving from two sources is one event.
                if (!seen.add("$date|${what.lowercase()}")) return@forEach
                out.add(
                    Event(
                        date = date,
                        what = what,
                        toolId = row.toolId,
                        toolName = row.toolName,
                        reliability = tool?.reliability ?: Grading.Reliability.UNJUDGED
                    )
                )
            }
        }
        // The newest events, not the oldest.
        //
        // `sortedBy { it.date }.take(limit)` keeps the 120 EARLIEST
        // and silently drops everything after them, so a case with
        // more than that lost the present — which is the part of a
        // timeline anybody is reading it for.
        return out.sortedByDescending { it.date }.take(limit).sortedBy { it.date }
    }

    /** The first real date in a line, in any of the shapes used. */
    internal fun firstDate(line: String): LocalDate? {
        ISO.find(line)?.let { m ->
            return of(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        SLASHED.find(line)?.let { m ->
            return of(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        SPELLED.find(line)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            return of(m.groupValues[3], month.toString(), m.groupValues[1])
        }
        SPELLED_FIRST.find(line)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            return of(m.groupValues[3], month.toString(), m.groupValues[2])
        }
        return null
    }

    private fun of(year: String, month: String, day: String): LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    /** The line, without its bullet, its timestamp tail or its padding. */
    internal fun tidy(line: String): String = line
        .removePrefix("•").removePrefix("-").removePrefix("›")
        .replace(Regex("""[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?Z?"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(140)

    /** "2019 · 3 events", for a heading. */
    fun byYear(events: List<Event>): List<Pair<Int, List<Event>>> =
        events.groupBy { it.year }.toList().sortedBy { it.first }

    private const val MAX_LINES_PER_RESULT = 400

    /** How a date reads in a list. */
    val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
}
