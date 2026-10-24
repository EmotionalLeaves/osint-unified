package com.osint.unified

import com.osint.unified.domain.GeoExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a finding gets plotted.
 *
 * firstCoordinate decides which point on the globe a result is
 * drawn at, and the extractor promised "in order of appearance"
 * while scanning pattern by pattern — so whichever pattern came
 * first in the list won, whatever the text said.
 */
class GeoExtractorTest {

    @Test
    fun theFirstCoordinateIsTheFirstOneInTheText() {
        val text = """
            Map: https://www.google.com/maps?q=35.6895,139.6917
            Coordinates: 48.8584, 2.2945
        """.trimIndent()
        assertEquals(35.6895 to 139.6917, GeoExtractor.firstCoordinate(text))
        assertEquals(
            listOf(35.6895 to 139.6917, 48.8584 to 2.2945),
            GeoExtractor.allCoordinates(text)
        )
    }

    @Test
    fun theOtherOrderIsAlsoRespected() {
        val text = """
            Coordinates: 48.8584, 2.2945
            Map: https://www.google.com/maps?q=35.6895,139.6917
        """.trimIndent()
        assertEquals(48.8584 to 2.2945, GeoExtractor.firstCoordinate(text))
    }

    @Test
    fun outOfRangeAndNullIslandAreNotCoordinates() {
        assertNull(GeoExtractor.firstCoordinate("Coordinates: 0, 0"))
        assertNull(GeoExtractor.firstCoordinate("Coordinates: 91.0, 10.0"))
        assertNull(GeoExtractor.firstCoordinate("Coordinates: 10.0, 181.0"))
        assertNull(GeoExtractor.firstCoordinate("nothing here"))
    }

    @Test
    fun theSamePlaceTwiceIsOnePlace() {
        val text = "Coordinates: 1.5, 2.5 and again mlat=1.5&mlon=2.5"
        assertEquals(listOf(1.5 to 2.5), GeoExtractor.allCoordinates(text))
    }
}
