package com.osint.unified

import com.osint.unified.data.TsunamiDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The two non-US tsunami authorities.
 *
 * Both publish in their own language and their own conventions, and
 * both were read wrong the first time in ways that would have been
 * invisible on a map: a coordinate string whose fields are separated
 * by their own signs, and a feed of earthquakes where the single field
 * that makes one belong on this layer is a sentence in Indonesian.
 */
class TsunamiSourcesTest {

    private val source = TsunamiDataSource()

    private fun justNow(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .format(Instant.now().minusSeconds(600).atOffset(ZoneOffset.UTC))

    @Test
    fun theSignsAreTheSeparators() {
        assertEquals(7.5 to -80.8, source.iso6709("+07.5-080.8/"))
        // Three fields: the last is depth in metres, not a coordinate.
        assertEquals(23.8 to 122.9, source.iso6709("+23.8+122.9-10000/"))
        assertEquals(-3.1 to 102.14, source.iso6709("-03.1+102.14/"))
    }

    @Test
    fun aCoordinateThatIsNotOneIsRefused() {
        assertNull(source.iso6709(null))
        assertNull(source.iso6709(""))
        assertNull(source.iso6709("+07.5/"))
        // Latitude out of range: a misread depth field landing in the
        // first slot would look exactly like this.
        assertNull(source.iso6709("+107.5-080.8/"))
    }

    /**
     * The whole meaning of a JMA bulletin is which of four words is in
     * its title, and three of the four contain a shorter one of the
     * others. Checked worst-first or the ladder collapses.
     */
    @Test
    fun theSeverityLadderIsReadLongestFirst() {
        assertTrue(source.severityOf("大津波警報").startsWith("MAJOR"))
        assertEquals("TSUNAMI WARNING", source.severityOf("津波警報"))
        assertEquals("TSUNAMI ADVISORY", source.severityOf("津波注意報"))
        assertTrue(source.severityOf("津波予報").startsWith("FORECAST"))
        assertEquals("BULLETIN", source.severityOf("地震情報"))
    }

    @Test
    fun aJapaneseBulletinBecomesAMarkerWhereTheQuakeWas() {
        val body = """
            [{"ctt":"x","eid":"1","rdt":"${justNow()}","ttl":"津波警報",
              "anm":"中米","cod":"+07.5-080.8/","mag":"8.0",
              "kind":[{"code":"100","kind":"津波予報"}]}]
        """.trimIndent()
        val out = source.jma(body)
        assertEquals(1, out.size)
        assertEquals(7.5, out[0].lat, 1e-9)
        assertEquals(-80.8, out[0].lon, 1e-9)
        assertTrue(out[0].label.contains("TSUNAMI WARNING"))
        assertTrue(out[0].label.contains("M8.0"))
        // The agency's own name for the place, untranslated.
        assertTrue(out[0].label.contains("中米"))
    }

    @Test
    fun aBulletinFromLastMonthIsNotAWarning() {
        val old = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.now().minusSeconds(40L * 24 * 3600).atOffset(ZoneOffset.UTC))
        val body = """[{"rdt":"$old","ttl":"津波警報","anm":"中米","cod":"+07.5-080.8/","mag":"8.0"}]"""
        assertTrue(source.jma(body).isEmpty())
    }

    /**
     * The filter that keeps this a tsunami layer. BMKG's feed is
     * earthquakes; only the ones it says can make a wave belong here,
     * and the verdict for the rest begins with "Tidak".
     */
    @Test
    fun indonesianQuakesWithNoWaveStayOnTheQuakeLayer() {
        val body = """
            {"Infogempa":{"gempa":[
              {"DateTime":"${justNow()}","Coordinates":"-3.11,102.14","Magnitude":"5.2",
               "Wilayah":"6 km BaratLaut LEBONG-BENGKULU","Potensi":"Tidak berpotensi tsunami"},
              {"DateTime":"${justNow()}","Coordinates":"-8.20,118.40","Magnitude":"7.1",
               "Wilayah":"Laut Flores","Potensi":"Berpotensi tsunami"}
            ]}}
        """.trimIndent()
        val out = source.bmkg(body)
        assertEquals(1, out.size)
        assertEquals(-8.20, out[0].lat, 1e-9)
        assertTrue(out[0].label.contains("Laut Flores"))
        assertTrue(out[0].label.contains("M7.1"))
    }

    /** An agency that says nothing about potential has not said no. */
    @Test
    fun anAbsentVerdictIsNotAnAllClear() {
        val body = """
            {"Infogempa":{"gempa":[
              {"DateTime":"${justNow()}","Coordinates":"-8.20,118.40","Magnitude":"7.1",
               "Wilayah":"Laut Flores"}
            ]}}
        """.trimIndent()
        assertEquals(1, source.bmkg(body).size)
    }
}
