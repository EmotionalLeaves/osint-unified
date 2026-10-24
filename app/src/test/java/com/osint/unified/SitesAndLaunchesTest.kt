package com.osint.unified

import com.osint.unified.data.LaunchDataSource
import com.osint.unified.data.SitesDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Two layers that answer "what is here" and "what is about to
 * happen there".
 */
class SitesAndLaunchesTest {

    // ---- Controlled places from OpenStreetMap -------------------------

    private val sites = SitesDataSource()

    private fun element(
        type: String,
        lat: Double,
        lon: Double,
        tags: String,
        centre: Boolean = false
    ): String {
        val position = if (centre) """"center":{"lat":$lat,"lon":$lon}"""
        else """"lat":$lat,"lon":$lon"""
        return """{"type":"$type","id":1,$position,"tags":{$tags}}"""
    }

    private fun elements(vararg rows: String) = """{"elements":[${rows.joinToString(",")}]}"""

    /**
     * The kind leads. "Border crossing · Kapitan Andreevo" is useful
     * at a glance; "Kapitan Andreevo" is a word.
     */
    @Test
    fun eachSiteSaysWhatItIsBeforeItSaysWhatItIsCalled() {
        val blips = sites.parse(
            elements(
                element("node", 41.7, 26.3, """"barrier":"border_control","name":"Kapitan Andreevo""""),
                element("way", 52.5, 13.4, """"amenity":"embassy","name":"Embassy","country":"FR"""", centre = true),
                element("way", 51.5, -0.1, """"amenity":"prison","name":"Pentonville""""),
                element("node", 48.9, 2.3, """"power":"substation","voltage":"400000"""")
            )
        )
        assertEquals(4, blips.size)
        assertTrue(blips[0].label.startsWith("Border crossing · Kapitan Andreevo"))
        assertTrue(blips[1].label.startsWith("Embassy"))
        assertTrue(blips[1].label.contains("FR"))
        assertTrue(blips[2].label.startsWith("Prison · Pentonville"))
        assertTrue(blips[3].label.contains("400000 V"))
    }

    /** A way's position is on its centre, not on the way itself. */
    @Test
    fun anAreaIsPlottedAtItsCentre() {
        val blips = sites.parse(
            elements(element("way", 10.5, 20.5, """"amenity":"prison"""", centre = true))
        )
        assertEquals(10.5, blips.single().lat, 1e-9)
    }

    /**
     * Overpass returns the same site as a node and as a way. Two
     * markers on one prison is two prisons to whoever is counting.
     */
    @Test
    fun theSameSiteTwiceIsOneMarker() {
        val blips = sites.parse(
            elements(
                element("node", 51.5, -0.1, """"amenity":"prison","name":"Pentonville""""),
                element("way", 51.5, -0.1, """"amenity":"prison","name":"HMP Pentonville"""")
            )
        )
        assertEquals(1, blips.size)
    }

    @Test
    fun somethingWithNoRecognisedTagIsNotInvented() {
        val blips = sites.parse(
            elements(element("node", 1.0, 1.0, """"amenity":"cafe","name":"Not a prison""""))
        )
        assertTrue(blips.isEmpty())
    }

    /**
     * Rubbish is a raised failure; an empty answer is an answer.
     *
     * An empty list from `parse` is read out loud as "no border
     * crossing, embassy or prison within 120 km of here", so a body
     * that fetched and then did not parse must not produce one. The
     * `{"version":0.6}` case is the interesting one: Overpass sends
     * exactly that when it refuses.
     */
    @Test
    fun rubbishFromOverpassIsAFailureRatherThanAnAbsence() {
        assertThrows(IOException::class.java) { sites.parse("not json") }
        assertThrows(IOException::class.java) { sites.parse("""{"version":0.6}""") }
    }

    @Test
    fun anEmptyOverpassAnswerIsAnAnswer() {
        assertTrue(sites.parse("""{"elements":[]}""").isEmpty())
    }

    // ---- Launches ------------------------------------------------------

    private val launches = LaunchDataSource()

    /** 2026-10-23 12:00:00Z. */
    private val now = 1792800000000L

    private fun launch(
        name: String,
        net: String,
        lat: String,
        lon: String,
        place: String,
        status: String = "Go"
    ) = """{"name":"$name","net":"$net","status":{"abbrev":"$status"},
        "pad":{"name":"LC-1","latitude":"$lat","longitude":"$lon",
        "location":{"name":"$place"}}}"""

    private fun schedule(vararg rows: String) =
        """{"count":${rows.size},"results":[${rows.joinToString(",")}]}"""

    // The horizon is measured against the time passed in, but the
    // "in 2d" on the label is measured against the real clock — so
    // nothing here asserts on that, or the suite would start failing
    // on a Sunday in 2027 for no reason anybody could guess.
    @Test
    fun aLaunchIsPlottedAtItsPadAndSaysWhichMissionItIs() {
        val blips = launches.parse(
            schedule(
                launch("Electron | Some Mission", "2026-10-25T04:00:00Z", "-39.26", "177.86", "Mahia")
            ),
            now
        )
        assertEquals(1, blips.size)
        assertEquals(-39.26, blips.single().lat, 1e-9)
        assertTrue(blips.single().label.startsWith("Mahia"))
        assertTrue(blips.single().label.contains("Electron"))
    }

    /** Two launches from one pad is one marker that says two. */
    @Test
    fun onePadIsOneMarker() {
        val blips = launches.parse(
            schedule(
                launch("Falcon 9 | A", "2026-10-24T04:00:00Z", "28.56", "-80.57", "Canaveral"),
                launch("Falcon 9 | B", "2026-10-26T04:00:00Z", "28.56", "-80.57", "Canaveral")
            ),
            now
        )
        assertEquals(1, blips.size)
        assertTrue(blips.single().label.contains("+1 more"))
    }

    /**
     * A launch three months out is a plan, not an event, and a map
     * covered in plans is a map nobody reads.
     */
    @Test
    fun aLaunchBeyondTheHorizonIsNotAnEventYet() {
        val blips = launches.parse(
            schedule(launch("Starship | Someday", "2027-06-01T04:00:00Z", "25.9", "-97.1", "Boca Chica")),
            now
        )
        assertTrue(blips.isEmpty())
    }

    /** A hold or a scrub is worth knowing; "Go" is the default and is not. */
    @Test
    fun aStatusThatIsNotGoIsSaidOutLoud() {
        val held = launches.parse(
            schedule(launch("Vulcan | X", "2026-10-25T04:00:00Z", "28.5", "-80.5", "Canaveral", "TBD")),
            now
        ).single().label
        val going = launches.parse(
            schedule(launch("Vulcan | X", "2026-10-25T04:00:00Z", "28.5", "-80.5", "Canaveral", "Go")),
            now
        ).single().label
        assertTrue(held.contains("(TBD)"))
        assertTrue(!going.contains("(Go)"))
    }

    @Test
    fun aLaunchWithNoPadPositionIsSkipped() {
        val body = """{"results":[{"name":"Mystery","net":"2026-10-25T04:00:00Z",
            "pad":{"name":"unknown","location":{"name":"somewhere"}}}]}"""
        assertTrue(launches.parse(body, now).isEmpty())
    }

    @Test
    fun aBrokenScheduleIsAFailureAndAnEmptyOneIsAnAnswer() {
        assertThrows(IOException::class.java) { launches.parse("not json", now) }
        assertThrows(IOException::class.java) { launches.parse("""{"count":0}""", now) }
        assertTrue(launches.parse("""{"results":[]}""", now).isEmpty())
    }
}
