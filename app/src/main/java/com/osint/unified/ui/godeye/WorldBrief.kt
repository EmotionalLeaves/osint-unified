package com.osint.unified.ui.godeye

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

    /** Alert words GDACS and the NWS use, strongest first. */
    private val RED = listOf("red", "extreme")
    private val ORANGE = listOf("orange", "severe")

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
            val top = source
                .sortedWith(
                    compareByDescending<GeoBlip> { rank(it) }
                        .thenBy { ageMinutes(it.label) ?: Int.MAX_VALUE }
                )
                .take(PER_SECTION)
                .map { Entry(it.label, it.lat, it.lon, it.url) }
            sections.add(
                Section(
                    "$title — ${source.size}",
                    // A section of ordinary items should not outrank a
                    // section holding a red alert, so the worst item in it
                    // lifts the whole block.
                    severity + (source.maxOfOrNull { rank(it) } ?: 1),
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
            sections.add(Section("SPACE WEATHER", if (sw.kp >= 5) 9 else 2, entries))
        }

        blips("CONFLICT", state.conflicts, 10)
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
                    shown.map { Entry("M%.1f".format(it.mag), it.lat, it.lon) },
                    note = if (strong.isEmpty()) "nothing above M4.5" else null
                )
            )
        }

        blips("SEVERE WEATHER", state.weather, 6)
        blips("WILDFIRES", state.fires, 5)
        blips("MILITARY AIRCRAFT", state.milAircraft, 5, rank = { 1 })
        blips("HIDDEN-OWNER AIRCRAFT", state.ladd, 4, rank = { 1 })
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
        fun maybeQuiet(key: String, label: String, size: Int) {
            if (key in state.loaded && size == 0) quiet.add(label)
        }
        maybeQuiet("gdacs", "disaster alerts", state.gdacs.size)
        maybeQuiet("conflicts", "conflicts", state.conflicts.size)
        maybeQuiet("volcanoes", "volcanoes", state.volcanoes.size)
        maybeQuiet("fires", "wildfires", state.fires.size)
        maybeQuiet("weather", "severe weather", state.weather.size)
        maybeQuiet("milAircraft", "military aircraft", state.milAircraft.size)

        return Brief(sections.sortedByDescending { it.severity }, quiet)
    }

    /** One sentence for the HUD, before anyone opens the brief. */
    fun headline(brief: Brief): String {
        val top = brief.sections.firstOrNull() ?: return "nothing loaded yet"
        return top.entries.firstOrNull()?.text?.take(70) ?: top.title
    }
}
