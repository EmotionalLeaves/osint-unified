package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow

/**
 * When somebody is awake, against where they say they are.
 *
 * Two tools already print it: a posting peak and a six-hour quiet
 * window, both in UTC, labelled "timezone hint" in so many words. The
 * hint went nowhere. It was grabbed as a literal string for the
 * summary block and never turned into a number, and nothing compared
 * it with the declared location sitting in the same dossier — which,
 * since the corroboration engine learned to read declared places, is
 * now a thing the dossier reliably holds.
 *
 * "Declared San Francisco; commits peak at 09:00 UTC and go quiet
 * 22:00–04:00 UTC" is a contradiction with both halves already on
 * screen, in different sections, for the reader to notice.
 *
 * Everything here is INFERRED and says so. The inference is weak on
 * purpose and the output states its own weakness: a quiet window is
 * when somebody is not posting, which is usually when they are
 * asleep and sometimes when they are at work, and a scheduler or a
 * second contributor breaks it entirely.
 */
object ActivityHours {

    /** A quiet window read out of a tool's output, in UTC hours. */
    data class Quiet(val fromHour: Int, val toHour: Int, val toolId: String)

    private val QUIET = Regex("""quiet\s+(\d{1,2}):00[–\-](\d{1,2}):00\s*UTC""", RegexOption.IGNORE_CASE)

    /** Every quiet window any source reported. */
    fun quietWindows(results: List<ResultRow>): List<Quiet> =
        results.filter { Findings.kindOf(it) == Findings.Kind.HIT }
            .flatMap { row ->
                QUIET.findAll(row.rawOutput).mapNotNull { m ->
                    val from = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val to = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    if (from !in 0..23 || to !in 0..23) null else Quiet(from, to, row.toolId)
                }.toList()
            }

    /**
     * The UTC offsets a quiet window is consistent with.
     *
     * A six-hour gap centred on local 03:00 is the ordinary shape of
     * a night, so the offset is 3 minus the window's midpoint. Given
     * as a band rather than a figure, because the middle of somebody's
     * night is not a constant and the window itself is six hours wide.
     */
    fun offsetsFor(quiet: Quiet): IntRange {
        val midpoint = ((quiet.fromHour + 3) % 24)
        val centre = Math.floorMod(3 - midpoint + 12, 24) - 12
        return (centre - BAND)..(centre + BAND)
    }

    /** How far either side of the centre still counts as agreement. */
    private const val BAND = 2

    /**
     * Whether a real offset sits inside the band, counting round the
     * dateline.
     *
     * `offset in band` is plain integer containment on a quantity
     * that is circular, and the band is folded into −12..11. A
     * Wellington user at UTC+12 whose quiet window is exactly the
     * model's night produced a centre of −12 and a band of −14..−10,
     * so the one case where the inference is perfectly right was
     * reported as a discrepancy — under a sentence naming "UTC−14",
     * which is not an offset anybody has.
     */
    internal fun agrees(offset: Int, centre: Int): Boolean {
        val apart = Math.floorMod(offset - centre + 12, 24) - 12
        return kotlin.math.abs(apart) <= BAND
    }

    /** An offset written the way people write it, folded to −11..+12. */
    internal fun asOffset(hours: Int): String {
        val folded = Math.floorMod(hours + 11, 24) - 11
        return if (folded < 0) "$folded" else "+$folded"
    }

    /**
     * What the hours and the declared places say about each other.
     *
     * Returns null when either half is missing, or when the declared
     * place is somewhere this cannot put an offset on — which is most
     * of the world's cities and every country wide enough to hold
     * several zones. Saying nothing is the right answer far more often
     * than saying something here.
     */
    fun compare(
        results: List<ResultRow>,
        places: List<String>
    ): Timeline.Observation? {
        val quiet = quietWindows(results).firstOrNull() ?: return null
        val band = offsetsFor(quiet)
        val claimed = places.firstNotNullOfOrNull { place ->
            OFFSETS[place]?.let { place to it }
        } ?: return null
        val (name, offset) = claimed
        val source = ToolRegistry.byId(quiet.toolId)?.name ?: quiet.toolId
        val gap = "${quiet.fromHour}:00–${quiet.toHour}:00 UTC"
        val centre = (band.first + band.last) / 2
        val agrees = agrees(offset, centre)
        return Timeline.Observation(
            what = buildString {
                append(if (agrees) "Consistent: " else "Worth checking: ")
                append("$source saw no activity between $gap, which fits ")
                append("somebody living around UTC")
                // The centre with a tolerance, not the two ends. The
                // ends wrap — a band centred on UTC+12 runs from +10
                // to -10 — and "+10 to -10" reads as nonsense.
                append(asOffset(centre))
                append(", give or take $BAND hours. The declared location is ")
                append(name.replaceFirstChar { it.uppercase() })
                append(", around UTC")
                append(asOffset(offset))
                append(if (agrees) ", which is inside that band." else ", which is not.")
                append(" A quiet window is when somebody is not posting — ")
                append("usually asleep, sometimes at work, and nothing at all ")
                append("if the account is scheduled or shared.")
            },
            kind = Grading.Kind.INFERRED
        )
    }

    /**
     * Places this is willing to put an offset on.
     *
     * Only countries narrow enough to have one, and the handful of
     * cities most likely to be in a profile. Everywhere wide enough
     * for several zones — the United States, Russia, Canada,
     * Australia, Brazil — is deliberately absent, because an average
     * of Maine and Hawaii is a number that would contradict somebody
     * truthfully. Winter offsets: summer time moves these by an hour,
     * which is well inside the band the comparison allows.
     */
    private val OFFSETS: Map<String, Int> = buildMap {
        // Western Europe
        listOf("united kingdom", "ireland", "portugal", "iceland").forEach { put(it, 0) }
        listOf(
            "france", "germany", "spain", "italy", "netherlands", "belgium",
            "switzerland", "austria", "poland", "czechia", "denmark", "norway",
            "sweden", "hungary", "croatia", "slovakia", "slovenia", "serbia",
            "north macedonia", "bosnia and herzegovina", "luxembourg", "tunisia",
            "algeria", "nigeria", "cameroon"
        ).forEach { put(it, 1) }
        listOf(
            "finland", "estonia", "latvia", "lithuania", "greece", "romania",
            "bulgaria", "ukraine", "moldova", "israel", "egypt", "south africa",
            "zimbabwe", "lebanon"
        ).forEach { put(it, 2) }
        // East Africa Time, which is +3 and was written as +2.
        listOf("kenya", "uganda", "ethiopia", "tanzania").forEach { put(it, 3) }
        listOf("turkey", "belarus", "iraq", "saudi arabia", "qatar", "kuwait", "jordan")
            .forEach { put(it, 3) }
        listOf("united arab emirates", "georgia", "armenia", "azerbaijan").forEach { put(it, 4) }
        listOf("pakistan", "uzbekistan").forEach { put(it, 5) }
        listOf("sri lanka").forEach { put(it, 5) }
        listOf("bangladesh").forEach { put(it, 6) }
        listOf("thailand", "vietnam", "cambodia").forEach { put(it, 7) }
        listOf("china", "singapore", "malaysia", "philippines", "taiwan", "mongolia")
            .forEach { put(it, 8) }
        listOf("japan", "south korea").forEach { put(it, 9) }
        listOf("new zealand").forEach { put(it, 12) }
        listOf("iran").forEach { put(it, 3) }
        listOf("india", "nepal").forEach { put(it, 5) }
        // Mexico and Indonesia are not here. Both span three zones,
        // which is the thing the comment below says is excluded, and
        // both were in the table anyway — Mexico at -5 while its own
        // capital is at -6 two lines down, disagreeing with itself.
        listOf("colombia", "peru", "ecuador", "panama", "cuba").forEach { put(it, -5) }
        listOf("costa rica").forEach { put(it, -6) }
        listOf("chile", "venezuela", "bolivia").forEach { put(it, -4) }
        listOf("argentina", "uruguay").forEach { put(it, -3) }

        // Cities, where they are more specific than their country or
        // where their country is too wide to carry one.
        listOf(
            "london" to 0, "dublin" to 0, "lisbon" to 0, "reykjavik" to 0,
            "paris" to 1, "berlin" to 1, "munich" to 1, "cologne" to 1,
            "hamburg" to 1, "frankfurt" to 1, "vienna" to 1, "zurich" to 1,
            "geneva" to 1, "berne" to 1, "amsterdam" to 1, "rotterdam" to 1,
            "brussels" to 1, "antwerp" to 1, "ghent" to 1, "bruges" to 1,
            "madrid" to 1, "barcelona" to 1, "seville" to 1, "rome" to 1,
            "milan" to 1, "naples" to 1, "turin" to 1, "florence" to 1,
            "venice" to 1, "prague" to 1, "warsaw" to 1, "cracow" to 1,
            "copenhagen" to 1, "oslo" to 1, "stockholm" to 1, "gothenburg" to 1,
            "budapest" to 1, "belgrade" to 1, "zagreb" to 1, "the hague" to 1,
            "helsinki" to 2, "tallinn" to 2, "riga" to 2, "vilnius" to 2,
            "athens" to 2, "bucharest" to 2, "kyiv" to 2, "lviv" to 2,
            "cairo" to 2, "istanbul" to 3, "moscow" to 3, "saint petersburg" to 3,
            "dubai" to 4, "mumbai" to 5, "delhi" to 5, "bengaluru" to 5,
            "chennai" to 5, "kolkata" to 5, "bangkok" to 7, "jakarta" to 7,
            "singapore" to 8, "beijing" to 8, "shanghai" to 8, "guangzhou" to 8,
            "hong kong" to 8, "taipei" to 8, "tokyo" to 9, "kyoto" to 9,
            "osaka" to 9, "seoul" to 9,
            // The wide countries, by city rather than by country.
            "new york" to -5, "boston" to -5, "washington" to -5,
            "philadelphia" to -5, "atlanta" to -5, "miami" to -5,
            "toronto" to -5, "montreal" to -5, "ottawa" to -5,
            "chicago" to -6, "austin" to -6, "dallas" to -6, "houston" to -6,
            "mexico city" to -6, "denver" to -7, "phoenix" to -7,
            "san francisco" to -8, "san francisco bay area" to -8,
            "los angeles" to -8, "san diego" to -8, "seattle" to -8,
            "portland" to -8, "vancouver" to -8,
            "sao paulo" to -3, "rio de janeiro" to -3, "buenos aires" to -3,
            "sydney" to 10, "melbourne" to 10, "brisbane" to 10,
            "perth" to 8, "auckland" to 12, "wellington" to 12,
        ).forEach { (city, offset) -> put(city, offset) }
    }
}
