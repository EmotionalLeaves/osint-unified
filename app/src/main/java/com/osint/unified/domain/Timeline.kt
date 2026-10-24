package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

    /**
     * What a reader would notice if they did the subtraction.
     *
     * The finding in a timeline is almost never a date. It is a gap:
     * the domain registered three days before the account was
     * created, six accounts across different platforms all created in
     * one fortnight, a certificate issued the week the breach is
     * dated. Every one of those is a subtraction the app had both
     * operands for and did not perform — it printed a list in
     * chronological order and left the reader to spot them.
     *
     * Stamped INFERRED, because the app worked these out rather than
     * reading them anywhere. [Grading.Kind] has had that value since
     * it was written and nothing had ever put anything on it.
     */
    data class Observation(
        val what: String,
        val kind: Grading.Kind = Grading.Kind.INFERRED,
        /** The events it was derived from, newest last. */
        val from: List<Event> = emptyList()
    )

    /**
     * Clusters and intervals over a built timeline.
     *
     * Deliberately conservative about what it calls a cluster. Three
     * dated lines within a fortnight is a coincidence when a dossier
     * holds two hundred of them and a finding when it holds nine, so
     * a cluster has to be a real share of the whole as well as close
     * together.
     */
    fun observations(events: List<Event>, limit: Int = 6): List<Observation> {
        if (events.size < 2) return emptyList()
        val sorted = events.sortedBy { it.date }
        val out = ArrayList<Observation>()

        // Events that happened together, across different sources.
        //
        // From one source they are one record being read: a
        // certificate log listing four issuances in a week says
        // something about a renewal schedule, not about a fortnight
        // in which several unrelated things happened.
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size &&
                ChronoUnit.DAYS.between(sorted[i].date, sorted[j + 1].date) <= CLUSTER_DAYS
            ) {
                j++
            }
            val near = sorted.subList(i, j + 1)
            val sources = near.map { it.toolId }.distinct()
            if (near.size >= 3 && sources.size >= 2 &&
                near.size * 4 >= sorted.size
            ) {
                out.add(
                    Observation(
                        "${near.size} dated records fall inside " +
                            "${ChronoUnit.DAYS.between(near.first().date, near.last().date) + 1} " +
                            "days (${near.first().date.format(FORMAT)} to " +
                            "${near.last().date.format(FORMAT)}), from " +
                            "${sources.size} different sources. That is " +
                            "${near.size} of the ${sorted.size} dates this case holds.",
                        from = near.toList()
                    )
                )
            }
            i = j + 1
        }

        // The span, which is the one interval every timeline has.
        val span = ChronoUnit.DAYS.between(sorted.first().date, sorted.last().date)
        if (span >= 1) {
            out.add(
                Observation(
                    "Everything dated in this case falls between " +
                        "${sorted.first().date.format(FORMAT)} and " +
                        "${sorted.last().date.format(FORMAT)} — " +
                        describeGap(span) + " apart."
                )
            )
        }

        // The two closest events from different sources, when they are
        // close enough to be worth saying.
        var tightest: Pair<Event, Event>? = null
        var tightestDays = Long.MAX_VALUE
        for (a in sorted.indices) {
            for (b in a + 1 until sorted.size) {
                if (sorted[a].toolId == sorted[b].toolId) continue
                val days = ChronoUnit.DAYS.between(sorted[a].date, sorted[b].date)
                if (days > NEAR_DAYS) break
                if (days < tightestDays) {
                    tightestDays = days
                    tightest = sorted[a] to sorted[b]
                }
            }
        }
        tightest?.let { (a, b) ->
            out.add(
                Observation(
                    "\"${a.what.take(60)}\" (${a.toolName}) and " +
                        "\"${b.what.take(60)}\" (${b.toolName}) are " +
                        describeGap(tightestDays) + " apart.",
                    from = listOf(a, b)
                )
            )
        }
        return out.take(limit)
    }

    private fun describeGap(days: Long): String = when {
        days == 0L -> "the same day"
        days == 1L -> "one day"
        days < 14L -> "$days days"
        days < 60L -> "${days / 7} weeks"
        days < 730L -> "${days / 30} months"
        else -> "${days / 365} years"
    }

    /** A fortnight: close enough that a reader would ask why. */
    private const val CLUSTER_DAYS = 14L

    /** Beyond this, two events being near each other means nothing. */
    private const val NEAR_DAYS = 10L

    /** "2019 · 3 events", for a heading. */
    fun byYear(events: List<Event>): List<Pair<Int, List<Event>>> =
        events.groupBy { it.year }.toList().sortedBy { it.first }

    private const val MAX_LINES_PER_RESULT = 400

    /** How a date reads in a list. */
    val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
}
