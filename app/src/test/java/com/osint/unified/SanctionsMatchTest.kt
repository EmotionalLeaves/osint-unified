package com.osint.unified

import com.osint.unified.domain.SanctionsNames
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name matching for sanctions screening, which is the most consequential
 * thing this app does. Exercises the production functions so the rules
 * are pinned by a test rather than by inspection.
 */
class SanctionsMatchTest {

    // The production rules, not a copy of them. The copy this file used
    // to carry passed whatever the app did, including the bugs.
    private fun normalise(raw: String) = SanctionsNames.normalise(raw)

    private fun matches(query: String, candidate: String) =
        SanctionsNames.matches(query, candidate)

    /** A raw substring search made "ali" match "Australia". */
    @Test
    fun aShortNameDoesNotMatchTheMiddleOfAWord() {
        assertFalse(matches("ali", "AUSTRALIA TRADING CO"))
        assertTrue(matches("ali", "ALI, Mohammed"))
    }

    /** Accents and punctuation vary; neither decides whether someone is found. */
    @Test
    fun accentsAndPunctuationDoNotHideAMatch() {
        assertTrue(matches("Jose Garcia", "GARCÍA, José"))
        assertTrue(matches("o'brien", "O'BRIEN, Sean"))
        assertTrue(matches("al-Assad", "AL ASSAD, Bashar"))
    }

    /** Word order in the list is not the order anyone types. */
    @Test
    fun orderDoesNotMatter() {
        assertTrue(matches("Bashar Assad", "AL ASSAD, Bashar"))
        assertFalse(matches("Bashar Smith", "AL ASSAD, Bashar"))
    }

    /**
     * The UN list search used to be a substring match over the whole XML
     * block — nationality, designation and a paragraph of comments
     * included — so "ali" matched every person from Mali and Somalia, and
     * because a name is split across four tags, "ali hassan" matched
     * nobody.
     */
    @Test
    fun aNameIsMatchedAgainstNamesAndNotAgainstABiography() {
        assertFalse(matches("ali", "Operated in Mali and Somalia"))
        assertFalse(matches("ali", "IBRAHIM TRAORE"))
        assertTrue(matches("ali", "ALI HASSAN"))
        assertTrue(matches("ali hassan", "ALI HASSAN"))
        assertTrue(matches("hassan ali", "ALI HASSAN"))
        assertTrue(matches("aqim", "AQIM"))
    }

    /** Accents and punctuation are not a different person. */
    @Test
    fun accentsAndPunctuationDoNotHideAName() {
        assertTrue(matches("traore", "TRAORÉ, Ibrahim"))
        assertTrue(matches("al qaida", "AL-QAIDA"))
        assertTrue(matches("muhammad", "MUḤAMMAD"))
    }
}
