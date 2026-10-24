package com.osint.unified

import com.osint.unified.domain.SanctionsNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a name against a sanctions list.
 *
 * The two failure modes here are opposite and both serious: naming
 * somebody who is not on the list, and failing to name somebody who
 * is. A length floor managed both at once.
 */
class SanctionsNamesTest {

    @Test
    fun aTwoLetterSyllableIsStillPartOfTheName() {
        // The old rule kept only words of three letters or more, so
        // this reduced to ["yong"] and matched every entry on the
        // list containing that one syllable.
        val tokens = SanctionsNames.tokens("Ri Yong Ho")
        assertEquals(listOf("ri", "yong", "ho"), tokens)
        assertFalse(SanctionsNames.matches(tokens, "KIM, Yong Chol"))
        assertFalse(SanctionsNames.matches(tokens, "PAK Yong Sik"))
        assertTrue(SanctionsNames.matches(tokens, "RI, Yong Ho"))
    }

    @Test
    fun aTwoWordTwoLetterNameIsStillASearch() {
        // "Li Na" reduced to nothing and was refused as "give at
        // least three characters" for a five-character query.
        assertEquals(listOf("li", "na"), SanctionsNames.tokens("Li Na"))
    }

    @Test
    fun particlesAreStillDropped() {
        // Which is what the length floor was standing in for.
        assertEquals(listOf("mansour"), SanctionsNames.tokens("al Mansour"))
        assertEquals(listOf("berg"), SanctionsNames.tokens("van der Berg"))
    }

    @Test
    fun theLatinLettersWithoutDecompositionsSurvive() {
        // NFKD leaves these whole and the class that follows it
        // turned them into spaces, so "Jørgensen" became "j rgensen"
        // and could never match the list's romanised JORGENSEN.
        assertEquals("jorgensen", SanctionsNames.normalise("Jørgensen"))
        assertEquals("aenes", SanctionsNames.normalise("Ænes"))
        assertEquals("lodz", SanctionsNames.normalise("Łódź"))
        assertEquals("strasse", SanctionsNames.normalise("Straße"))
    }

    @Test
    fun accentsStillFlatten() {
        assertEquals("jose", SanctionsNames.normalise("José"))
        assertEquals("muller", SanctionsNames.normalise("Müller"))
    }

    @Test
    fun aNonLatinQueryIsRecognisedRatherThanRefusedAsTooShort() {
        // The bundled list is romanised, so this cannot match — but
        // "give at least three characters" for a 20-character query
        // is a misleading way to say so.
        assertTrue(SanctionsNames.isUnromanised("Владимир Путин"))
        assertTrue(SanctionsNames.isUnromanised("محمد"))
        assertFalse(SanctionsNames.isUnromanised("Vladimir Putin"))
    }
}
