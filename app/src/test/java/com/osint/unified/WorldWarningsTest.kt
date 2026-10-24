package com.osint.unified

import com.osint.unified.data.WorldWarningsDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Every official severe-weather warning on Earth.
 *
 * The weather layer was the United States and nothing else, not because
 * anyone decided that but because api.weather.gov was what could be
 * reached without a key.
 */
class WorldWarningsTest {

    private val source = WorldWarningsDataSource()

    private val members = """
        [
          {"ra": 2, "members": [
            {"mid": "001", "name": "China", "dept": "China Meteorological Administration",
             "lat": 35.86166, "lng": 104.195397},
            {"mid": "107", "name": "Russian Federation", "dept": "Roshydromet",
             "lat": 61.524, "lng": 105.3188}
          ]},
          {"ra": 4, "members": [
            {"mid": "093", "name": "United States of America", "dept": "NWS",
             "lat": 37.09024, "lng": -95.712891},
            {"mid": "999", "name": "Nowhere", "dept": "x", "lat": 1000.0, "lng": 0.0}
          ]}
        ]
    """.trimIndent()

    private fun warning(
        mid: String,
        event: String,
        severity: Int,
        expires: String,
        area: String = "Somewhere"
    ) = """{"id": "urn:oid:x", "event": "$event", "headline": "h", "sent": "2026-10-22 07:19:47",
        "expires": "$expires", "areaDesc": "$area", "mid": "$mid", "ra": "6", "s": $severity,
        "u": 4, "c": 3, "capURL": "x.xml", "effective": "2026-10-22 07:19:00"}"""

    private fun feed(vararg items: String) =
        """{"itemCount": ${items.size}, "lastUpdated": "2026-10-23 05:53:12",
            "items": [${items.joinToString(",")}]}"""

    /** 2026-10-23 06:00:00 UTC. */
    private val now = 1792735200000L

    @Test
    fun membersAreReadAcrossRegionalAssociations() {
        val parsed = source.members(members)
        assertEquals(3, parsed.size)
        assertEquals("Russian Federation", parsed["107"]?.name)
        // A member whose coordinates could not be a place on Earth is
        // dropped rather than drawn at the edge of the map.
        assertTrue("999" !in parsed)
    }

    @Test
    fun oneMarkerPerIssuingService() {
        val blips = source.parse(
            feed(
                warning("093", "Blizzard Warning", 0, "2026-10-24 00:00:00"),
                warning("093", "Flood Watch", 2, "2026-10-24 00:00:00"),
                warning("107", "Forestfire", 2, "2026-10-25 12:00:00")
            ),
            members,
            now
        )
        assertEquals(2, blips.size)
        val us = blips.first { it.label.startsWith("United States") }
        assertTrue(us.label.contains("2 warnings"))
        // The position is the service's, not the weather's, and nothing
        // in the app may measure a distance to it.
        assertTrue(us.approximate)
    }

    /**
     * The file keeps warnings for a while after they lapse. A map still
     * saying "Blizzard Warning" over Alaska two days later is lying in
     * the most ordinary way a live map can.
     */
    @Test
    fun expiredWarningsAreDropped() {
        val blips = source.parse(
            feed(
                warning("093", "Blizzard Warning", 0, "2026-10-23 05:00:00"),
                warning("107", "Forestfire", 2, "2026-10-25 12:00:00")
            ),
            members,
            now
        )
        assertEquals(1, blips.size)
        assertTrue(blips.first().label.startsWith("Russian Federation"))
    }

    /** A warning with no readable expiry is kept, not guessed about. */
    @Test
    fun anUnreadableExpiryKeepsTheWarning() {
        val blips = source.parse(
            feed(warning("093", "Blizzard Warning", 0, "not a date")),
            members,
            now
        )
        assertEquals(1, blips.size)
    }

    /**
     * A country with one Extreme warning and forty Minor ones must not
     * read as forty Minor ones.
     */
    @Test
    fun theWorstWarningLeadsTheLabel() {
        val items = (1..6).map { warning("093", "Wind Advisory", 3, "2026-10-24 00:00:00") } +
            warning("093", "Tornado Warning", 0, "2026-10-24 00:00:00")
        val blips = source.parse(feed(*items.toTypedArray()), members, now)
        val label = blips.single().label
        assertTrue(label.contains("Extreme"))
        assertTrue(label.indexOf("Tornado Warning") < label.indexOf("Wind Advisory"))
        assertTrue(label.contains("Wind Advisory ×6"))
    }

    @Test
    fun aWarningFromAnUnknownMemberIsSkipped() {
        val blips = source.parse(
            feed(warning("404", "Ghost Warning", 0, "2026-10-24 00:00:00")),
            members,
            now
        )
        assertTrue(blips.isEmpty())
    }

    @Test
    fun severityOutOfRangeIsNotGuessedAt() {
        assertEquals("Extreme", source.severityName(0))
        assertEquals("Minor", source.severityName(3))
        assertEquals("severity not stated", source.severityName(7))
    }

    @Test
    fun theFileSendsPlainTimestampsAndMeansUtc() {
        assertEquals(now, source.instantOf("2026-10-23 06:00:00"))
        assertEquals(null, source.instantOf("2026-10-23T06:00:00Z"))
        assertEquals(null, source.instantOf(""))
        assertEquals(null, source.instantOf(null))
    }

    /**
     * Rubbish in is a raised failure, not an empty list.
     *
     * An empty list from here is printed as "no severe-weather
     * warning anywhere in the world", so a body that fetched and
     * then did not parse must not produce one. This test used to
     * assert the opposite, which is how the behaviour survived.
     */
    @Test
    fun rubbishInIsAFailureRatherThanAnAllClear() {
        assertThrows(IOException::class.java) { source.parse("not json", members, now) }
        assertThrows(IOException::class.java) { source.parse(feed(), "not json", now) }
    }

    /** An empty but well-formed feed is a real all-clear. */
    @Test
    fun anEmptyFeedIsAnAnswer() {
        assertTrue(source.parse("""{"items": []}""", members, now).isEmpty())
    }
}
