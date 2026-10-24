package com.osint.unified

import com.osint.unified.data.EarthquakeDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The third seismic agency, which publishes a pipe-separated table.
 *
 * Positional formats fail quietly: read one column off by one and
 * every quake on the map gets the depth of the one next to it, with
 * nothing anywhere to say so. So the column indices are pinned here
 * against the real header line.
 */
class GeofonTest {

    private val source = EarthquakeDataSource()

    private val header =
        "#EventID|Time|Latitude|Longitude|Depth/km|Author|Catalog|Contributor|" +
            "ContributorID|MagType|Magnitude|MagAuthor|EventLocationName|EventType"

    private fun recently(): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        .format(Instant.now().minusSeconds(1800).atZone(ZoneOffset.UTC).toLocalDateTime())

    @Test
    fun everyColumnLandsWhereTheHeaderSaysItDoes() {
        val body = header + "\n" +
            "gfz2026uuee|${recently()}|38.87|14.402|10.0|||GFZ|gfz2026uuee|mb|4.51||Sicily, Italy|earthquake"
        val out = source.geofon(body)
        assertEquals(1, out.size)
        val q = out[0]
        assertEquals(38.87, q.lat, 1e-9)
        assertEquals(14.402, q.lon, 1e-9)
        assertEquals(4.51, q.mag, 1e-9)
        assertEquals(10.0, q.depthKm!!, 1e-9)
        assertEquals("Sicily, Italy", q.place)
        assertTrue(q.url!!.endsWith("gfz2026uuee"))
    }

    /** The timestamps carry no zone. FDSN means UTC by them, and
     *  reading them as the phone's local time would date a quake up to
     *  half a day wrong — enough to filter a live one off the map. */
    @Test
    fun timesAreUtcNotWhereverThePhoneIs() {
        val body = header + "\n" +
            "x|${recently()}|38.87|14.402|10.0|||GFZ|x|mb|4.51||Sicily, Italy|earthquake"
        val at = source.geofon(body)[0].atMillis!!
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - at - 1_800_000L) < 120_000L)
    }

    @Test
    fun yesterdaysQuakesAreNotTodaysMap() {
        val old = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .format(Instant.now().minusSeconds(3L * 24 * 3600).atZone(ZoneOffset.UTC).toLocalDateTime())
        val body = header + "\n" +
            "x|$old|38.87|14.402|10.0|||GFZ|x|mb|4.51||Sicily, Italy|earthquake"
        assertTrue(source.geofon(body).isEmpty())
    }

    /**
     * One bad row among good ones is skipped; every row being bad
     * is a feed that changed shape.
     *
     * This test used to feed three malformed rows and assert the
     * result was empty — which is the globe reporting "no
     * earthquakes anywhere in twenty-four hours" from a body it
     * could not read, and the test was protecting it. An FDSN
     * maintenance page or a changed column layout reaches here as
     * exactly that.
     */
    @Test
    fun aTruncatedOrEmptyRowIsSkippedRatherThanGuessed() {
        val body = header + "\n" +
            "x|${recently()}|38.87|14.402\n" +
            "\n" +
            "y|${recently()}|notalatitude|14.402|10.0|||GFZ|y|mb|4.51||Somewhere|earthquake\n" +
            "good|${recently()}|38.87|14.402|10.0|||GFZ|g|mb|4.51||Sicily, Italy|earthquake"
        val quakes = source.geofon(body)
        assertEquals(1, quakes.size)
        assertEquals("Sicily, Italy", quakes.first().place)
    }

    @Test
    fun everyRowBeingUnreadableIsAFailureAndNotAQuietDay() {
        val body = header + "\n" +
            "x|${recently()}|38.87|14.402\n" +
            "y|${recently()}|notalatitude|14.402|10.0|||GFZ|y|mb|4.51||Somewhere|earthquake"
        assertThrows(IOException::class.java) { source.geofon(body) }
    }

    /** And a feed with no rows at all is still an honest zero. */
    @Test
    fun aHeaderWithNoRowsIsAnAnswer() {
        assertTrue(source.geofon(header).isEmpty())
    }

    /** Two agencies reporting one earthquake is one earthquake. */
    @Test
    fun theThirdAgencyDoesNotTripleTheCount() {
        val body = header + "\n" +
            "x|${recently()}|38.87|14.402|10.0|||GFZ|x|mb|4.51||Sicily, Italy|earthquake"
        val gfz = source.geofon(body)
        // The same event as the USGS would have it: a slightly
        // different solution, which is the normal case.
        val usgs = gfz.map { it.copy(lat = it.lat + 0.08, mag = it.mag + 0.2, place = "Sicily") }
        assertEquals(1, source.merge(usgs, gfz).size)
        // And a real second quake a long way off is still two.
        val elsewhere = gfz.map { it.copy(lat = -20.0, lon = 120.0) }
        assertEquals(2, source.merge(usgs, elsewhere).size)
    }
}
