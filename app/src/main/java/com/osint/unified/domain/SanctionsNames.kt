package com.osint.unified.domain

import java.text.Normalizer

/**
 * Name matching for sanctions screening.
 *
 * Lifted out of the executor so that the test exercises the code that
 * runs. The test used to carry its own copy of these two functions and
 * check that against itself, which passes whatever production does —
 * worse than no test, because it reads like coverage.
 */
object SanctionsNames {

    /** Accents dropped, punctuation flattened, case removed. */
    fun normalise(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** The query's words, long enough to be worth matching on. */
    /**
     * The words of a query worth matching on.
     *
     * Three letters, not two. At two, "al x" reduced to ["al"] and
     * matched every al-something on the list — forty of them, capped,
     * and reported as a hit against a real person's name. A two-letter
     * particle is not a search.
     */
    fun tokens(query: String): List<String> =
        normalise(query).split(" ").filter { it.length >= 3 }

    /**
     * Whether every token of the query appears as a whole word in the name.
     *
     * A raw substring search made "ali" match "Australia" and buried real
     * matches under junk, while a multi-word query only matched when the
     * list happened to order the names the same way.
     */
    fun matches(tokens: List<String>, candidate: String): Boolean {
        val words = normalise(candidate).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || tokens.isEmpty()) return false
        return tokens.all { token -> words.any { it == token } }
    }

    fun matches(query: String, candidate: String): Boolean =
        matches(tokens(query), candidate)
}
