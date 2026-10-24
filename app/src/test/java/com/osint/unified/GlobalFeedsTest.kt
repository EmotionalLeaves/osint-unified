package com.osint.unified

import com.osint.unified.data.CountryCentroids
import com.osint.unified.data.EarthquakeDataSource
import com.osint.unified.data.OutageAlerts
import com.osint.unified.data.SeaLevelDataSource
import com.osint.unified.ui.godeye.Quake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The feeds added to make this map cover the planet rather than the
 * places that happen to publish in English.
 */
class GlobalFeedsTest {

    // ---- Two seismic agencies reporting one Earth --------------------

    private val quakes = EarthquakeDataSource()

    private fun quake(lat: Double, lon: Double, mag: Double, at: Long?) =
        Quake(lat = lat, lon = lon, mag = mag, place = "somewhere", atMillis = at)

    /**
     * The agencies do not share identifiers, so the same earthquake
     * arrives twice under different numbers. A map that draws it twice
     * is saying there were two.
     */
    @Test
    fun theSameEarthquakeFromBothAgenciesIsOneEarthquake() {
        val usgs = listOf(quake(38.80, 14.20, 3.0, 1_000_000L))
        val emsc = listOf(quake(38.81, 14.19, 3.1, 1_030_000L))
        assertEquals(1, quakes.merge(usgs, emsc).size)
    }

    /**
     * An aftershock is not its mainshock. Loosening the match until
     * they merge throws away the part of a sequence worth seeing.
     */
    @Test
    fun anAftershockSurvivesTheMerge() {
        val main = listOf(quake(38.80, 14.20, 6.0, 1_000_000L))
        val after = listOf(quake(38.80, 14.20, 4.8, 1_000_000L + 400_000L))
        assertEquals(2, quakes.merge(main, after).size)
    }

    @Test
    fun anEventOnlyOneAgencySawIsKept() {
        val usgs = listOf(quake(38.8, 14.2, 3.0, 1_000_000L))
        val emsc = listOf(quake(-20.0, 120.0, 2.1, 1_000_000L))
        assertEquals(2, quakes.merge(usgs, emsc).size)
    }

    /** Same place, same minute, very different size: two events. */
    @Test
    fun magnitudeAloneCanTellThemApart() {
        val a = listOf(quake(0.0, 0.0, 2.0, 1_000_000L))
        val b = listOf(quake(0.0, 0.0, 5.5, 1_000_000L))
        assertEquals(2, quakes.merge(a, b).size)
    }

    // ---- The global tide-gauge network --------------------------------

    private val tides = SeaLevelDataSource()

    private fun station(
        code: String,
        lat: Double,
        lon: Double,
        value: String,
        time: String,
        country: String = "Norway"
    ) = """{"Code":"$code","Location":"$code harbour","countryname":"$country",
        "lat":$lat,"lon":$lon,"lastvalue":$value,"lasttime":"$time"}"""

    /** 2026-10-23 12:00:00Z. */
    private val now = 1792800000000L

    @Test
    fun aReportingGaugeIsPlottedWithItsReadingAndItsAge() {
        val blips = tides.parse(
            "[" + station("ABED", 57.14, -2.08, "1.82", "2026-10-23 11:40:00") + "]",
            now
        )
        assertEquals(1, blips.size)
        assertEquals(57.14, blips.first().lat, 1e-9)
        assertTrue(blips.first().label.contains("1.82 m"))
        assertTrue(blips.first().label.contains("Norway"))
    }

    /**
     * The register holds every station ever installed, including ones
     * that stopped sending in 2011. A gauge drawn on a live map is read
     * as a live reading.
     */
    /**
     * The real feed sends milliseconds on the timestamp. A formatter
     * that takes the stamp without them dates every reading as
     * unknown, every station is then filtered away as stale, and the
     * layer is dark for a reason nobody would find by looking at it.
     */
    @Test
    fun theTimestampTheFeedActuallySendsIsUnderstood() {
        val blips = tides.parse(
            "[" + station("ABSH", 44.02, 144.29, "1.82", "2026-10-23 11:40:00.000") + "]",
            now
        )
        assertEquals(1, blips.size)
    }

    @Test
    fun aGaugeThatStoppedYearsAgoIsNotDrawn() {
        val blips = tides.parse(
            "[" + station("DEAD", 10.0, 10.0, "0.4", "2011-03-11 06:00:00") + "]",
            now
        )
        assertTrue(blips.isEmpty())
    }

    @Test
    fun aStationWithNoReadingOrNoPositionIsSkipped() {
        val body = """[
            {"Code":"A","Location":"A","lat":10.0,"lon":10.0,"lasttime":"2026-10-23 11:40:00"},
            {"Code":"B","Location":"B","lastvalue":1.0,"lasttime":"2026-10-23 11:40:00"},
            {"Code":"C","Location":"C","lat":999.0,"lon":10.0,"lastvalue":1.0,
             "lasttime":"2026-10-23 11:40:00"}
        ]"""
        assertTrue(tides.parse(body, now).isEmpty())
    }

    @Test
    fun rubbishIsAFailureAndAnEmptyListIsAnAnswer() {
        assertThrows(IOException::class.java) { tides.parse("not json", now) }
        assertTrue(tides.parse("[]", now).isEmpty())
    }

    // ---- Countries going dark -----------------------------------------

    private val countries = mapOf(
        "MW" to CountryCentroids.Place(-13.25, 34.30, "Malawi"),
        "AF" to CountryCentroids.Place(33.94, 67.71, "Afghanistan")
    )

    private fun alert(
        fqid: String,
        type: String,
        code: String,
        name: String,
        level: String = "critical",
        datasource: String = "bgp"
    ) = """{"datasource":"$datasource","entity":{"code":"$code","name":"$name",
        "type":"$type","attrs":{"fqid":"$fqid"}},"time":1792195200,"level":"$level"}"""

    /**
     * One country losing connectivity trips every detection method at
     * once, several times over an hour. Twelve markers on Malawi is
     * reporting twelve outages.
     */
    @Test
    fun manyAlertsForOnePlaceAreOneMarker() {
        val body = "[" + listOf(
            alert("geo.netacuity.AF.MW", "country", "MW", "Malawi", datasource = "bgp"),
            alert("geo.netacuity.AF.MW", "country", "MW", "Malawi", datasource = "ping-slash24"),
            alert("geo.netacuity.AF.MW", "region", "4508", "Blantyre", datasource = "bgp")
        ).joinToString(",") + "]"
        val blips = OutageAlerts.parse(body, countries)
        assertEquals(1, blips.size)
        val label = blips.single().label
        assertTrue(label.startsWith("Malawi"))
        assertTrue(label.contains("3 alerts"))
        assertTrue(label.contains("Blantyre"))
        assertTrue(label.contains("routing"))
        assertTrue(label.contains("pings"))
    }

    /**
     * A region's own code is a number that names nowhere; the country
     * is in the fully qualified id whether the alert is about the
     * country or a province of it.
     */
    @Test
    fun aRegionIsPlacedInItsCountry() {
        val blips = OutageAlerts.parse(
            "[" + alert("geo.netacuity.AF.MW", "region", "4508", "Blantyre") + "]",
            countries
        )
        assertEquals(-13.25, blips.single().lat, 1e-9)
        // The country, not the outage: nothing may measure a distance
        // to this.
        assertTrue(blips.single().approximate)
    }

    @Test
    fun degradedAndDownAreNotTheSameWord() {
        val down = OutageAlerts.parse(
            "[" + alert("geo.netacuity.AF.MW", "country", "MW", "Malawi", "critical") + "]",
            countries
        ).single().label
        val degraded = OutageAlerts.parse(
            "[" + alert("geo.netacuity.AF.MW", "country", "MW", "Malawi", "warning") + "]",
            countries
        ).single().label
        assertTrue(down.contains("down"))
        assertTrue(degraded.contains("degraded"))
    }

    /** A country the table does not know is left off, not put at 0,0. */
    @Test
    fun anUnknownCountryIsNotPlottedInTheGulfOfGuinea() {
        val blips = OutageAlerts.parse(
            "[" + alert("geo.netacuity.XX.ZZ", "country", "ZZ", "Atlantis") + "]",
            countries
        )
        assertTrue(blips.isEmpty())
    }

    @Test
    fun aCountryCanBeFoundByCodeOrByName() {
        assertEquals("Malawi", CountryCentroids.find(countries, "mw")?.name)
        assertEquals("Malawi", CountryCentroids.find(countries, "Malawi")?.name)
        assertEquals(null, CountryCentroids.find(countries, ""))
        assertEquals(null, CountryCentroids.find(countries, null))
    }
}
