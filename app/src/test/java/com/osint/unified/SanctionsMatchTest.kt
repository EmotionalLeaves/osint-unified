package com.osint.unified

import java.text.Normalizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name matching for sanctions screening, which is the most consequential
 * thing this app does. Mirrors the two functions in Executors so the rules
 * are pinned by a test rather than by inspection.
 */
class SanctionsMatchTest {

    private fun normalise(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun matches(query: String, candidate: String): Boolean {
        val tokens = normalise(query).split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        val words = normalise(candidate).split(" ").filter { it.isNotBlank() }
        return tokens.all { token -> words.any { it == token } }
    }

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
}
