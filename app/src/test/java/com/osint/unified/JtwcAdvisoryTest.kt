package com.osint.unified

import com.osint.unified.data.JtwcAdvisory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The west Pacific and Indian Ocean, which the hurricane centre does not
 * cover.
 *
 * Most of the world's tropical cyclones form where the NHC does not
 * look, so a typhoon making landfall on Luzon did not exist on this map
 * and the layer said "no cyclones" while it happened. The JTWC publishes
 * the rest as the plain military message text it has used for decades,
 * where the position is a phrase in a sentence rather than a field.
 *
 * The fixture below is a real advisory, shortened.
 */
class JtwcAdvisoryTest {

    private val advisory = """
ABPW10 PGTW 230600
MSGID/GENADMIN/JOINT TYPHOON WRNCEN PEARL HARBOR HI//
SUBJ/SIGNIFICANT TROPICAL WEATHER ADVISORY FOR THE WESTERN AND SOUTH
PACIFIC OCEANS/230600Z-240600ZOCT2026//
REF/A/MSG/JOINT TYPHOON WRNCEN PEARL HARBOR HI/230151ZOCT2026//
NARR/REF A IS A TROPICAL CYCLONE WARNING.//
RMKS/
1. WESTERN NORTH PACIFIC AREA (180 TO MALAY PENINSULA):
   A. TROPICAL CYCLONE SUMMARY:
      (1) AT 23OCT26 0000Z, TYPHOON 29W (CHAMPI) WAS LOCATED NEAR
 22.7N 123.8E, APPROXIMATELY 316 NM SOUTHWEST OF KADENA AB, AND HAD
 TRACKED NORTH-NORTHWESTWARD AT 09 KNOTS OVER THE PAST SIX HOURS.
 MAXIMUM SUSTAINED SURFACE WINDS WERE ESTIMATED AT 90 KNOTS GUSTING TO
 110 KNOTS. SEE REF A (WTPN32 PGTW 230300) FOR FURTHER DETAILS.
      (2) NO OTHER TROPICAL CYCLONES.
   B. TROPICAL DISTURBANCE SUMMARY:
      (1) THE AREA OF CONVECTION (INVEST 97W) PREVIOUSLY LOCATED NEAR
 11.0N 165.9E IS NOW LOCATED NEAR 11.0N 165.8E, APPROXIMATELY 181 NM
 NORTHWEST OF KWAJALEIN. A 2214Z ASCAT-C PASS REVEALED A HIGHLY
 ASYMMETRIC WIND FIELD, WITH 20-25KT WINDS ALONG THE WESTERN QUADRANTS
 OF THE LOW-LEVEL CIRCULATION CENTER (LLCC). LOW VERTICAL WIND SHEAR
 (10-15KTS), AND WARM SEA SURFACE TEMPERATURES (28-29C). MAXIMUM
 SUSTAINED SURFACE WINDS ARE ESTIMATED AT 18 TO 23 KNOTS. MINIMUM SEA
 LEVEL PRESSURE IS ESTIMATED TO BE NEAR 1005 MB. THE POTENTIAL FOR THE
 DEVELOPMENT OF A SIGNIFICANT TROPICAL CYCLONE WITHIN THE NEXT 24
 HOURS REMAINS HIGH. SEE REF B (WTPN21 PGTW 230130) FOR FURTHER DETAILS.
      (2) NO OTHER SUSPECT AREAS.
   C. SUBTROPICAL SYSTEM SUMMARY: NONE.
2. SOUTH PACIFIC AREA (WEST COAST OF SOUTH AMERICA TO 135 EAST):
   A. TROPICAL CYCLONE SUMMARY: NONE.
   B. TROPICAL DISTURBANCE SUMMARY: NONE.//
""".trimIndent()

    private val url = "https://www.metoc.navy.mil/jtwc/products/abpwweb.txt"

    @Test
    fun theNamedTyphoonIsReadOutOfTheSentence() {
        val storm = JtwcAdvisory.parse(advisory, url)
            .first { it.label.startsWith("CHAMPI") }
        assertEquals(22.7, storm.lat, 1e-9)
        assertEquals(123.8, storm.lon, 1e-9)
        assertTrue(storm.label.contains("(29W)"))
        assertTrue(storm.label.contains("typhoon"))
        assertTrue(storm.label.contains("90 kt (104 mph)"))
        assertTrue(storm.label.contains("tracking north-northwest at 9 kt"))
        assertEquals(url, storm.url)
    }

    /**
     * "PREVIOUSLY LOCATED NEAR 11.0N 165.9E IS NOW LOCATED NEAR 11.0N
     * 165.8E" — the last position in the sentence is where it is, and
     * the first is where it was.
     */
    @Test
    fun theCurrentPositionWinsOverTheOneItMovedFrom() {
        val invest = JtwcAdvisory.parse(advisory, url)
            .first { it.label.startsWith("97W") }
        assertEquals(165.8, invest.lon, 1e-9)
    }

    /** An invest is a maybe, and the label has to say so. */
    @Test
    fun anAreaBeingWatchedIsNotCalledAStorm() {
        val invest = JtwcAdvisory.parse(advisory, url)
            .first { it.label.startsWith("97W") }
        assertTrue(invest.label.contains("area being watched"))
        assertTrue(invest.label.contains("development potential high"))
        assertTrue(invest.label.contains("23 kt"))
    }

    /** "NO OTHER TROPICAL CYCLONES" is not a cyclone. */
    @Test
    fun emptySummariesProduceNothing() {
        assertEquals(2, JtwcAdvisory.parse(advisory, url).size)
    }

    /**
     * Parenthesised text is everywhere in these messages. Only the
     * advisory's own item numbers may split it, or "(LLCC)" and
     * "(28-29C)" would each start a new system.
     */
    @Test
    fun bracketsInsideTheProseDoNotSplitIt() {
        val labels = JtwcAdvisory.parse(advisory, url).map { it.label }
        assertTrue(labels.none { it.startsWith("Unnamed system") })
    }

    @Test
    fun aSouthernOrWesternFixIsSigned() {
        val southern = JtwcAdvisory.parse(
            """RMKS/ 1. SOUTH PACIFIC AREA: A. TROPICAL CYCLONE SUMMARY:
               (1) AT 23OCT26 0000Z, TROPICAL STORM 01P (ALFRED) WAS LOCATED
               NEAR 15.4S 170.2W, AND HAD TRACKED SOUTHWARD AT 05 KNOTS.""",
            url
        )
        assertEquals(-15.4, southern.single().lat, 1e-9)
        assertEquals(-170.2, southern.single().lon, 1e-9)
    }

    @Test
    fun anAdvisoryWithNothingInItIsEmptyRatherThanWrong() {
        assertTrue(JtwcAdvisory.parse("", url).isEmpty())
        assertTrue(
            JtwcAdvisory.parse(
                "RMKS/ 1. WESTERN NORTH PACIFIC AREA: A. TROPICAL CYCLONE " +
                    "SUMMARY: NONE. B. TROPICAL DISTURBANCE SUMMARY: NONE.//",
                url
            ).isEmpty()
        )
    }
}
