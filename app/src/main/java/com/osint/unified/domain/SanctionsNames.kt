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
            // A few Latin letters have no canonical decomposition, so
            // NFKD leaves them whole and the class below turned them
            // into spaces: "Jørgensen" became "j rgensen" and could
            // never match the list's romanised JORGENSEN. Mapped by
            // hand, the way every transliteration table does.
            .let { t ->
                val sb = StringBuilder(t.length)
                t.forEach { c ->
                    when (c) {
                        'ø' -> sb.append("o")
                        'æ' -> sb.append("ae")
                        'ł' -> sb.append("l")
                        'đ', 'ð' -> sb.append("d")
                        'þ' -> sb.append("th")
                        'ß' -> sb.append("ss")
                        'œ' -> sb.append("oe")
                        'ı' -> sb.append("i")
                        'ħ' -> sb.append("h")
                        'ŋ' -> sb.append("ng")
                        else -> sb.append(c)
                    }
                }
                sb.toString()
            }
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** True when a query has no Latin letters left after normalising. */
    fun isUnromanised(raw: String): Boolean =
        raw.isNotBlank() && normalise(raw).isBlank()

    /** The query's words, long enough to be worth matching on. */
    /**
     * Particles that are not a search on their own.
     *
     * The old rule was a three-letter floor, which was right about
     * "al" and wrong about everything else: Korean, Chinese and
     * Vietnamese names are mostly two-letter syllables, so "Ri Yong
     * Ho" reduced to ["yong"] and matched every OFAC entry containing
     * that one syllable — an innocent person reported as sanctioned
     * on a shared syllable — while "Li Na" reduced to nothing at all
     * and was refused as "give at least three characters" for a
     * five-character query. A stop list is what the floor was
     * standing in for.
     */
    private val PARTICLES = setOf(
        "al", "el", "bin", "ibn", "abu", "umm", "bint", "van", "von",
        "der", "den", "de", "del", "della", "di", "da", "dos", "das",
        "du", "la", "le", "les", "mac", "mc", "ben", "bar", "ter",
        "ten", "op", "aan", "the", "and", "of"
    )

    /**
     * The words of a query worth matching on.
     *
     * Everything that is not a particle, at any length — because in
     * a great many of the world's naming systems the discriminating
     * part of a name IS two letters, and throwing it away is both a
     * false positive against whatever is left and a refusal to
     * search when nothing is.
     */
    fun tokens(query: String): List<String> =
        normalise(query).split(" ")
            .filter { it.isNotBlank() && it !in PARTICLES }

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
