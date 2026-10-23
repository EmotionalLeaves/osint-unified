package com.osint.unified

import com.osint.unified.domain.PersonName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a person's name apart.
 *
 * Every other observable in this app has a shape the code understands.
 * A name was a string handed to each source exactly as typed, which is
 * most of why people were the thing it was worst at.
 */
class PersonNameTest {

    @Test
    fun aPlainNameSplitsIntoTwo() {
        val p = PersonName.parse("John Smith")
        assertEquals("John", p.given)
        assertEquals("Smith", p.family)
        assertTrue(p.middles.isEmpty())
    }

    @Test
    fun theFormEveryOfficialListUsesIsUnderstood() {
        // "Smith, John" is how OFAC, the UN list and most registries
        // write it, and it used to be searched for verbatim.
        val p = PersonName.parse("Smith, John")
        assertEquals("John", p.given)
        assertEquals("Smith", p.family)
    }

    @Test
    fun titlesAndSuffixesAreNotPartOfTheName() {
        val p = PersonName.parse("Dr. Jane A. Goodall PhD")
        assertEquals("Jane", p.given)
        assertEquals("Goodall", p.family)
        assertEquals(listOf("A."), p.middles)
        assertEquals("PhD", p.suffix)
    }

    @Test
    fun aParticleBelongsToTheFamilyName() {
        // Splitting on spaces makes "der" a middle name and "Berg" the
        // surname, which is how a Dutch name misses everything.
        assertEquals("van der Berg", PersonName.parse("Jan van der Berg").family)
        assertEquals("Jan", PersonName.parse("Jan van der Berg").given)
        assertEquals("de la Cruz", PersonName.parse("Maria de la Cruz").family)
        assertEquals("Ibn Saud", PersonName.parse("Abdul Ibn Saud").family)
    }

    @Test
    fun aCompanyIsNotAPerson() {
        assertTrue(PersonName.looksLikeOrganisation("Acme Holdings Ltd"))
        assertTrue(PersonName.looksLikeOrganisation("Jane Goodall Institute"))
        assertTrue(PersonName.looksLikeOrganisation("Northern Trust Corporation"))
        assertFalse(PersonName.looksLikeOrganisation("John Smith"))
        assertFalse(PersonName.looksLikeOrganisation("Maria de la Cruz"))
    }

    @Test
    fun shortFormsGoBothWays() {
        assertTrue("bob" in PersonName.diminutives("Robert"))
        assertTrue("robert" in PersonName.diminutives("Bob"))
        assertTrue("jack" in PersonName.diminutives("John"))
        assertTrue(PersonName.diminutives("Xiulan").isEmpty())
    }

    @Test
    fun variantsCoverTheFormsASourceWouldHold() {
        val v = PersonName.variants("Robert J. Smith")
        assertEquals("Robert J. Smith", v.first())
        assertTrue(v.toString(), v.contains("Smith, Robert"))
        assertTrue(v.toString(), v.contains("R. Smith"))
        assertTrue(v.toString(), v.contains("Bob Smith"))
    }

    @Test
    fun aCompanyGetsNoPersonVariants() {
        // Searching for "Ltd, Acme Holdings" helps nobody.
        assertEquals(listOf("Acme Holdings Ltd"), PersonName.variants("Acme Holdings Ltd"))
    }

    @Test
    fun oneWordIsNotRearranged() {
        assertEquals(listOf("Cher"), PersonName.variants("Cher"))
    }

    @Test
    fun initialsComeOffTheFrontOfTheName() {
        assertEquals("JA", PersonName.parse("Jane Alice Goodall").initials)
        assertEquals("J", PersonName.parse("Jan van der Berg").initials)
    }
}
