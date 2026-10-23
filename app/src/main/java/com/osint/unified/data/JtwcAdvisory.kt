package com.osint.unified.data

import com.osint.unified.ui.godeye.GeoBlip
import java.util.Locale

/**
 * The west Pacific and Indian Ocean, which the hurricane centre does not
 * cover.
 *
 * The cyclone layer was the US National Hurricane Center, so it knew
 * about the Atlantic and the east Pacific and nothing else. Most of the
 * world's tropical cyclones form in the west Pacific; a typhoon making
 * landfall on Luzon simply did not exist on this map, and the layer said
 * "no cyclones" while it happened.
 *
 * The Joint Typhoon Warning Center publishes the rest, keyless, as the
 * plain military message text it has used for decades. No JSON, no
 * coordinates field — the position is a phrase in a sentence:
 *
 *     (1) AT 23OCT26 0000Z, TYPHOON 29W (CHAMPI) WAS LOCATED NEAR
 *     22.7N 123.8E, APPROXIMATELY 316 NM SOUTHWEST OF KADENA AB
 *
 * So this reads the sentence. It is a narrow parser on purpose: it takes
 * what it can prove is a position and a designation, and anything it
 * cannot read it drops rather than guesses at, because a cyclone drawn
 * in the wrong ocean is worse than one that is missing.
 */
object JtwcAdvisory {

    /**
     * Each numbered item under a summary heading is one system.
     *
     * Split on "(1) ", "(2) " and so on — the advisory's own numbering.
     * Parenthesised text elsewhere in the message is never a bare
     * number, so nothing else splits here: not "(LLCC)", not
     * "(10-15KTS)", not "(WTPN32 PGTW 230300)".
     */
    private val ITEM = Regex("""\(\d{1,2}\)\s""")

    /** "22.7N 123.8E", wherever in the sentence it falls. */
    private val POSITION = Regex(
        """(\d{1,2}(?:\.\d{1,2})?)\s*([NS])\s+(\d{1,3}(?:\.\d{1,2})?)\s*([EW])"""
    )

    /** "TYPHOON 29W (CHAMPI)", "INVEST 97W", "TROPICAL STORM 04B". */
    private val SYSTEM = Regex(
        """(SUPER TYPHOON|TYPHOON|TROPICAL STORM|TROPICAL DEPRESSION|""" +
            """SUBTROPICAL STORM|TROPICAL CYCLONE|CYCLONE|INVEST)\s+""" +
            """(\d{2}[A-Z])\s*(?:\(([A-Z][A-Z\- ]{1,20})\))?"""
    )

    private val WINDS = Regex(
        """WINDS\s+(?:WERE|ARE|IS)?\s*ESTIMATED\s+AT\s+(\d{1,3})(?:\s+TO\s+(\d{1,3}))?\s+KNOTS"""
    )

    private val MOVEMENT = Regex("""TRACKED\s+([A-Z\-]+)\s+AT\s+(\d{1,3})\s+KNOTS""")

    private val POTENTIAL = Regex(
        """POTENTIAL FOR THE DEVELOPMENT OF A SIGNIFICANT TROPICAL CYCLONE""" +
            """.{0,60}?(HIGH|MEDIUM|LOW)"""
    )

    /**
     * Systems in one advisory.
     *
     * [url] is the advisory itself, so a tap goes to the message this
     * was read from rather than to a front page.
     */
    fun parse(text: String, url: String): List<GeoBlip> {
        // The header names basins and references other messages; nothing
        // before the remarks section is a system.
        val body = text.substringAfter("RMKS/", text)
        val flat = body.replace(Regex("""\s+"""), " ")
        return ITEM.split(flat).drop(1).mapNotNull { chunk -> system(chunk, url) }
    }

    private fun system(chunk: String, url: String): GeoBlip? {
        if (chunk.startsWith("NO ")) return null
        // "PREVIOUSLY LOCATED NEAR 11.0N 165.9E IS NOW LOCATED NEAR
        // 11.0N 165.8E" — the last position in the sentence is the
        // current one, and the first is where it used to be.
        val fix = POSITION.findAll(chunk).lastOrNull() ?: return null
        val lat = fix.groupValues[1].toDoubleOrNull() ?: return null
        val lon = fix.groupValues[3].toDoubleOrNull() ?: return null
        val signedLat = if (fix.groupValues[2] == "S") -lat else lat
        val signedLon = if (fix.groupValues[4] == "W") -lon else lon
        if (signedLat !in -90.0..90.0 || signedLon !in -180.0..180.0) return null

        val found = SYSTEM.find(chunk)
        val kind = found?.groupValues?.get(1)?.lowercase() ?: "tropical disturbance"
        val designation = found?.groupValues?.get(2).orEmpty()
        val name = found?.groupValues?.get(3)?.takeIf { it.isNotBlank() }

        val winds = WINDS.find(chunk)
        val low = winds?.groupValues?.get(1)?.toDoubleOrNull()
        val high = winds?.groupValues?.get(2)?.toDoubleOrNull()
        val moving = MOVEMENT.find(chunk)
        val potential = POTENTIAL.find(chunk)?.groupValues?.get(1)?.lowercase()

        val label = buildString {
            when {
                name != null && designation.isNotEmpty() -> append("$name ($designation)")
                designation.isNotEmpty() -> append(designation)
                else -> append("Unnamed system")
            }
            append(" · ")
            // "invest" is the JTWC's own word for an area it is watching
            // and not yet a cyclone. Saying so is the difference between
            // a storm and a maybe.
            append(if (kind == "invest") "area being watched" else kind)
            if (low != null) {
                val top = high ?: low
                append(" · %.0f kt (%.0f mph)".format(Locale.US, top, top * 1.15078))
            }
            moving?.let {
                append(" · tracking ")
                append(it.groupValues[1].lowercase().replace("ward", ""))
                append(" at ${it.groupValues[2].trimStart('0').ifEmpty { "0" }} kt")
            }
            potential?.let { append(" · development potential $it") }
            append(" · JTWC")
        }
        return GeoBlip(signedLat, signedLon, label, url)
    }
}
