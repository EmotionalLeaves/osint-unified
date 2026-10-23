package com.osint.unified.ui.godeye

import java.util.Locale

/**
 * What is happening in the world right now, from the layers already loaded.
 *
 * The globe shows tens of thousands of markers, and a marker count is not a
 * situation report: eleven thousand aircraft is the ordinary state of the
 * sky, while one red GDACS alert is the thing you needed to know. A person
 * cannot find that by panning around a planet.
 *
 * So this ranks what is loaded by how much it would matter — severity
 * first, then recency, then size — and writes it out as lines you can tap
 * to fly to. It reads state the app already holds: no network, instant,
 * and honest about which layers have not reported yet.
 */
object WorldBrief {

    /** One line of the brief. [lat]/[lon] fly the globe there when tapped. */
    data class Entry(
        val text: String,
        val lat: Double? = null,
        val lon: Double? = null,
        val url: String? = null
    )

    /** A headed block of the brief. [severity] orders blocks, high first. */
    data class Section(
        val title: String,
        val severity: Int,
        val entries: List<Entry>,
        val note: String? = null
    )

    data class Brief(val sections: List<Section>, val quiet: List<String>)

    private const val PER_SECTION = 6

    /** Above the merely calm, below anything that happened. */
    private const val TROUBLE_SEVERITY = 3

    /** Alert words GDACS and the NWS use, strongest first. */
    private val RED = listOf("red", "extreme")
    private val ORANGE = listOf("orange", "severe")

    /** The category token TsunamiDataSource writes as "<centre> <WORD>: …". */
    internal fun categoryWord(label: String): String =
        label.substringBefore(':').trim().substringAfterLast(' ').uppercase()

    private fun alertRank(label: String): Int {
        val l = label.lowercase()
        return when {
            RED.any { l.contains("· $it") || l.contains(" $it ") || l.endsWith(" $it") } -> 3
            ORANGE.any { l.contains("· $it") || l.contains(" $it ") || l.endsWith(" $it") } -> 2
            else -> 1
        }
    }

    /**
     * Minutes-ago, read back out of the label.
     *
     * Every feed already appends a human age ("· 3h ago", "· 40m ago")
     * built by the same helper, so the brief can order by recency without
     * the sources having to carry a timestamp all the way through.
     */
    private val AGE = Regex("""(\d+)\s*([mhd])\s*ago""", RegexOption.IGNORE_CASE)

    fun ageMinutes(label: String): Int? {
        val m = AGE.find(label) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "m" -> n
            "h" -> n * 60
            else -> n * 60 * 24
        }
    }

    fun build(state: GodEyeUiState): Brief {
        val sections = ArrayList<Section>()
        val quiet = ArrayList<String>()

        fun blips(
            title: String,
            source: List<GeoBlip>,
            severity: Int,
            note: String? = null,
            // Last, so a trailing lambda binds here rather than to `note`.
            rank: (GeoBlip) -> Int = { alertRank(it.label) }
        ) {
            if (source.isEmpty()) return
            // Ranking reads a regex out of each label, and the military
            // aircraft layer alone holds thousands. A comparator that calls
            // it would run that regex tens of thousands of times per
            // rebuild, on the thread drawing the globe — so each item is
            // scored exactly once and the sort works on the scores.
            val scored = source.map { blip ->
                Triple(rank(blip), ageMinutes(blip.label) ?: Int.MAX_VALUE, blip)
            }
            val top = scored
                .sortedWith(compareByDescending<Triple<Int, Int, GeoBlip>> { it.first }
                    .thenBy { it.second })
                .take(PER_SECTION)
                .map { (_, _, b) -> Entry(b.label, b.lat, b.lon, b.url) }
            sections.add(
                Section(
                    "$title — ${source.size}",
                    // A section of ordinary items should not outrank a
                    // section holding a red alert, so the worst item in it
                    // lifts the whole block.
                    severity + (scored.maxOfOrNull { it.first } ?: 1),
                    top,
                    note
                )
            )
        }

        state.spaceWeather?.let { sw ->
            val entries = ArrayList<Entry>()
            entries.add(Entry(sw.summary))
            sw.alerts.take(4).forEach { entries.add(Entry(it)) }
            // Kp 5 is a geomagnetic storm; below that it is just weather.
            sections.add(Section("SPACE WEATHER", if ((sw.kp ?: 0.0) >= 5) 9 else 2, entries))
        }

        // A conflict is real but it is not news: it scored above a
        // magnitude 6 and carried no age, so the headline showed the same
        // war for months and stopped being read. It stays in the brief,
        // below the things that changed today.
        blips("CONFLICT", state.conflicts, 3, rank = { 1 })
        // A tsunami warning outranks everything else on the map — but only
        // the bulletin's own category word may decide that. These bulletins
        // routinely name the issuing body ("National Tsunami Warning
        // Center") in their title, so searching the whole label for
        // "warning" promoted every information statement to a live warning.
        // The category is the second token, written in capitals by
        // TsunamiDataSource.
        blips("TSUNAMI BULLETINS · PAC/CARIB", state.tsunamis, 10, rank = { b ->
            when (categoryWord(b.label)) {
                "WARNING" -> 3
                "WATCH", "ADVISORY" -> 2
                else -> 1
            }
        })
        blips("TROPICAL CYCLONES · WORLD", state.cyclones, 9, rank = { b ->
            val l = b.label.lowercase()
            when {
                l.contains("typhoon") || l.contains("hurricane") -> 3
                l.contains("storm") -> 2
                else -> 1
            }
        })
        blips("DISASTER ALERTS", state.gdacs, 9)
        blips("VOLCANOES", state.volcanoes, 7)

        if (state.quakes.isNotEmpty()) {
            val strong = state.quakes.filter { it.mag >= 4.5 }.sortedByDescending { it.mag }
            val shown = (if (strong.isNotEmpty()) strong else state.quakes.sortedByDescending { it.mag })
                .take(PER_SECTION)
            sections.add(
                Section(
                    "EARTHQUAKES — ${state.quakes.size}",
                    // A magnitude 6 is a different kind of news from a 3.
                    6 + when {
                        state.quakes.any { it.mag >= 6.0 } -> 3
                        state.quakes.any { it.mag >= 5.0 } -> 2
                        else -> 1
                    },
                    shown.map { Entry(it.label, it.lat, it.lon, it.url) },
                    note = if (strong.isEmpty()) "nothing above M4.5" else null
                )
            )
        }

        blips("SEVERE WEATHER · WORLD", state.worldWarnings, 7)
        blips("SEVERE WEATHER · US", state.weather, 6)
        blips("WILDFIRES", state.fires, 5)
        blips("MILITARY AIRCRAFT", state.milAircraft, 5, rank = { 1 })
        blips("AIRPORT STATUS · US", state.airportStatus, 4, rank = { b ->
            if (b.label.contains("GROUND STOP") || b.label.contains("CLOSED")) 3 else 1
        })
        blips("NEWS HOTSPOTS", state.news, 3, rank = { 1 })

        if (state.aircraft.isNotEmpty() || state.vessels.isNotEmpty()) {
            val entries = ArrayList<Entry>()
            if (state.aircraft.isNotEmpty()) entries.add(Entry("${state.aircraft.size} aircraft tracked"))
            if (state.vessels.isNotEmpty()) entries.add(Entry("${state.vessels.size} vessels tracked"))
            if (state.satellites.isNotEmpty()) entries.add(Entry("${state.satellites.size} satellites propagated"))
            sections.add(Section("TRAFFIC", 1, entries))
        }

        // Layers that have reported and found nothing are worth one line
        // each — "no disaster alerts anywhere" is itself information, and
        // it is not the same as a layer that has not run.
        // "Nothing anywhere" may only be said about a feed that answered,
        // and whose last answer is the one being counted. A feed that
        // answered an hour ago and has failed since is neither.
        val troubled = ArrayList<String>()
        fun maybeQuiet(key: String, label: String, size: Int) {
            when {
                key in state.feedTrouble -> troubled.add(label)
                key in state.loaded && size == 0 -> quiet.add(label)
            }
        }
        maybeQuiet("gdacs", "disaster alerts", state.gdacs.size)
        maybeQuiet("conflicts", "conflicts", state.conflicts.size)
        maybeQuiet("volcanoes", "volcanoes", state.volcanoes.size)
        maybeQuiet("fires", "wildfires", state.fires.size)
        maybeQuiet(
            "worldWarnings",
            "severe-weather warnings anywhere in the world",
            state.worldWarnings.size
        )
        maybeQuiet("weather", "US severe-weather alerts", state.weather.size)
        maybeQuiet("milAircraft", "military aircraft", state.milAircraft.size)
        // Named by their real coverage. A regional source reporting nothing
        // is not the world reporting nothing, and "no tropical cyclones"
        // during a West Pacific typhoon is a lie the NHC never told.
        maybeQuiet("cyclones", "tropical cyclones anywhere in the world", state.cyclones.size)
        maybeQuiet("tsunamis", "Pacific and Caribbean tsunami bulletins", state.tsunamis.size)
        maybeQuiet("airportStatus", "US airport delays", state.airportStatus.size)

        if (troubled.isNotEmpty()) {
            sections.add(
                Section(
                    "NOT REPORTING — ${troubled.size}",
                    // Below anything that is actually happening. At 8 it
                    // outranked most of the news, so the amber ticker read
                    // "military aircraft" when what it meant was "the
                    // military aircraft feed is down" — a sentence that
                    // looks exactly like an event.
                    TROUBLE_SEVERITY,
                    troubled.map { Entry(it) },
                    note = "These feeds could not be reached. Their absence " +
                        "from the brief above means nothing."
                )
            )
        }
        return Brief(sections.sortedByDescending { it.severity }, quiet)
    }

    /** One sentence for the HUD, before anyone opens the brief. */
    /**
     * The one line shown on the HUD.
     *
     * A broken feed is not a headline. It belongs in the brief, where the
     * list of what is not reporting is useful, but the ticker is for what
     * is happening — and the entries under NOT REPORTING are feed names,
     * which read as events. If nothing else has anything to say, the
     * ticker says so in those words rather than naming a feed.
     */
    fun headline(brief: Brief): String {
        val top = brief.sections.firstOrNull { it.title.startsWith("NOT REPORTING").not() }
        if (top == null) {
            val down = brief.sections.firstOrNull()?.entries?.size ?: 0
            return if (down > 0) "$down feed${if (down == 1) "" else "s"} not reporting"
            else "nothing loaded yet"
        }
        return top.entries.firstOrNull()?.text?.take(70) ?: top.title
    }
}
