package com.osint.unified

import com.osint.unified.data.ImageText
import com.osint.unified.domain.ImageClues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the words in a photograph into things to look up.
 *
 * This is the keyless half of image geolocation, so the thing
 * that matters is that it sorts honestly: a phone number routed
 * to the phone tools, a coordinate to the coordinate tools, and
 * nothing invented.
 */
class ImageCluesTest {

    private fun read(vararg lines: String) = ImageText.Read(
        lines.mapIndexed { i, t -> ImageText.Line(t, i / 10f) },
        1200, 1600
    )

    private fun of(kind: ImageClues.Kind, r: ImageText.Read) =
        ImageClues.clues(r).filter { it.kind == kind }.map { it.value }

    @Test
    fun aNumberOnAVanIsAPhoneNumber() {
        val r = read("ACME PLUMBING", "+44 20 7946 0958")
        assertTrue(of(ImageClues.Kind.PHONE, r).any { it.contains("7946") })
    }

    @Test
    fun aSiteOnAWallIsADomain() {
        val r = read("acmeplumbing.co.uk")
        assertEquals(listOf("acmeplumbing.co.uk"), of(ImageClues.Kind.DOMAIN, r))
    }

    /** An address already carries its domain; listing both is one lead
     *  reported as two. */
    @Test
    fun anAddressIsNotAlsoCountedAsItsOwnDomain() {
        val r = read("info@acmeplumbing.co.uk")
        assertEquals(listOf("info@acmeplumbing.co.uk"), of(ImageClues.Kind.EMAIL, r))
        assertTrue(of(ImageClues.Kind.DOMAIN, r).isEmpty())
    }

    @Test
    fun aCoordinatePrintedInTheFrameIsACoordinate() {
        val r = read("48.8584, 2.2945")
        assertEquals(listOf("48.8584, 2.2945"), of(ImageClues.Kind.COORDINATE, r))
    }

    @Test
    fun aStreetNameIsRecognisedInSeveralLanguages() {
        listOf(
            "Kurfürstenstrasse", "Rue de Rivoli", "Calle Mayor",
            "Oxford Street", "Market Rd"
        ).forEach {
            val kinds = ImageClues.clues(read(it)).map { c -> c.kind }
            assertTrue(it, ImageClues.Kind.ROAD in kinds)
        }
    }

    /**
     * The alphabet excludes most of the planet before a single
     * word is understood, which makes it the cheapest cue there
     * is — and the only one that needs no lookup at all.
     */
    @Test
    fun theScriptsPresentAreReported() {
        assertEquals(listOf("Latin"), ImageClues.scripts(read("EXIT")))
        assertTrue("Cyrillic" in ImageClues.scripts(read("Выход")))
        assertTrue("Arabic" in ImageClues.scripts(read("خروج")))
        val mixed = ImageClues.scripts(read("出口 EXIT"))
        assertTrue(mixed.toString(), "Han" in mixed && "Latin" in mixed)
    }

    @Test
    fun aPictureWithNoWordsProducesNoClues() {
        assertTrue(ImageClues.clues(read()).isEmpty())
        assertTrue(ImageClues.scripts(read()).isEmpty())
    }

    @Test
    fun theSameStringTwiceIsOneLead() {
        val r = read("acme.com", "acme.com", "ACME.COM")
        assertEquals(1, of(ImageClues.Kind.DOMAIN, r).size)
    }

    /** It must not invent a location, and the checklist is the part
     *  no model does for anybody. */
    @Test
    fun theChecklistCoversWhatAModelWillNotDo() {
        val all = ImageClues.CHECKLIST.joinToString(" ").lowercase()
        listOf("side of the road", "plate", "shadow", "vegetation", "socket")
            .forEach { assertTrue(it, it in all) }
    }
}
