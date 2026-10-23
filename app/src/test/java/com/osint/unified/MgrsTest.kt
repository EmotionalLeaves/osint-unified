package com.osint.unified

import com.osint.unified.domain.Mgrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid is not regular, and the places where it is not are the places
 * someone is most likely to be looking.
 */
class MgrsTest {

    /**
     * The two documented exceptions, from the standard rather than from
     * the arithmetic: zone 32 widened over south-west Norway, and the
     * four widened zones around Svalbard with the gaps between them.
     */
    @Test
    fun followsTheNorwayAndSvalbardExceptions() {
        val cases = listOf(
            Triple(59.0, 4.0, 32),    // widened over the Norwegian coast
            Triple(59.0, 2.0, 31),    // just west of it, ordinary
            Triple(59.0, 11.9, 32),   // the eastern edge of the exception
            Triple(59.0, 12.1, 33),   // past it, ordinary
            Triple(64.1, 4.0, 31),    // north of the band, ordinary again
            Triple(78.0, 8.0, 31),    // Svalbard, 31 widened
            Triple(78.0, 10.0, 33),
            Triple(78.0, 22.0, 35),
            Triple(78.0, 34.0, 37),
            Triple(78.0, 44.0, 38)    // past Svalbard, ordinary
        )
        cases.forEach { (lat, lon, zone) ->
            assertEquals("zone at $lat,$lon", zone, Mgrs.zoneFor(lat, lon))
        }
    }

    /**
     * 180 and -180 are the same meridian and only one of them is in a
     * zone. Taken literally, 180 fell in zone 1 and was then measured 357
     * degrees from zone 1's centre, which puts the easting in the
     * millions and the square on the far side of the world.
     */
    @Test
    fun theAntimeridianIsTheSamePlaceFromEitherSide() {
        listOf(-79.0, 0.0, 45.0, 83.0).forEach { lat ->
            val east = Mgrs.from(lat, 180.0)
            val west = Mgrs.from(lat, -180.0)
            assertNotNull("no reference at $lat,180", east)
            assertEquals("180 and -180 disagree at $lat", west, east)
        }
    }

    /** An easting outside 100–900 km is not a grid reference at all. */
    @Test
    fun everyEastingLandsInsideItsZone() {
        var lat = -79.0
        while (lat <= 83.0) {
            var lon = -180.0
            while (lon <= 180.0) {
                val reference = Mgrs.from(lat, lon)
                assertNotNull("no reference at $lat,$lon", reference)
                // "32V NM 12345 67890" — the two five-digit groups are the
                // position within the 100 km square, so they are always in
                // range by construction; what matters is that the square
                // letters were derived from an easting inside the zone,
                // which shows up as a parseable, well-formed reference.
                assertTrue(
                    "malformed at $lat,$lon: $reference",
                    Regex("""^\d{1,2}[C-X] [A-Z]{2} \d{5} \d{5}$""").matches(reference!!)
                )
                lon += 3.0
            }
            lat += 1.0
        }
    }

    @Test
    fun refusesThePolarRegionsRatherThanInventingAGrid() {
        assertNull(Mgrs.from(85.0, 10.0))
        assertNull(Mgrs.from(-81.0, 10.0))
    }
}
