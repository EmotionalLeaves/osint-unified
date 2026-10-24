package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Corroboration
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.PersonName
import com.osint.unified.domain.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five platforms independently saying Vienna.
 *
 * The corroboration engine could only see machine identifiers, while
 * the sources most likely to be asked about a person return almost
 * nothing else: a declared name, an employer, a city. These tests are
 * about the merge — three spellings of one place counting as one
 * assertion with three witnesses rather than three assertions with
 * one each — because if that does not hold the feature is noise.
 */
class DeclaredAttributesTest {

    private fun row(tool: String, output: String) = ResultRow(
        id = 0,
        investigationId = 1,
        toolId = tool,
        toolName = tool,
        input = "jdoe",
        status = "success",
        rawOutput = output,
        startedAt = 1,
        finishedAt = 2
    )

    // ---- Places --------------------------------------------------

    @Test
    fun theSamePlaceWrittenFiveWaysIsOnePlace() {
        val forms = listOf("Vienna", "vienna", "Wien", "WIEN", "Vienna ")
        val canonical = forms.map { Places.canonical(it) }.distinct()
        assertEquals("all five should reduce to one: $canonical", 1, canonical.size)
    }

    @Test
    fun diacriticsDoNotSplitAPlace() {
        assertEquals(Places.canonical("Zurich"), Places.canonical("Zürich"))
        assertEquals(Places.canonical("Munich"), Places.canonical("München"))
        assertEquals(Places.canonical("Cologne"), Places.canonical("Köln"))
    }

    @Test
    fun aCountryCodeMeansTheCountry() {
        assertEquals("austria", Places.canonical("AT"))
        assertEquals("austria", Places.canonical("aut"))
        assertEquals("austria", Places.canonical("Österreich"))
        assertEquals("united kingdom", Places.canonical("UK"))
        assertEquals("united states", Places.canonical("USA"))
    }

    @Test
    fun aCityAndItsCountryAreBothAsserted() {
        val parts = Places.parts("Vienna, Austria")
        assertTrue("$parts should contain the country", parts.contains("austria"))
        assertTrue("$parts should contain the city", parts.any { it.contains("vienna") })
    }

    /**
     * The failure mode that would make this worse than nothing: two
     * different places counted as one.
     */
    @Test
    fun anAmbiguousTwoLetterCodeResolvesToNeither() {
        // CA is California and Canada; DE is Delaware and Germany.
        assertEquals("ca", Places.canonical("CA"))
        assertEquals("de", Places.canonical("DE"))
        // And an unambiguous one still resolves.
        assertEquals("texas", Places.canonical("TX"))
    }

    @Test
    fun aShrugIsNotAPlace() {
        listOf("N/A", "unknown", "the internet", "/dev/null", "Earth", "-", "127.0.0.1")
            .forEach { assertNull("$it should not be a place", Places.canonical(it)) }
    }

    // ---- Names ---------------------------------------------------

    @Test
    fun oneNameWrittenFourWaysIsOneName() {
        val forms = listOf("José García", "Jose Garcia", "García, José", "Dr. José García")
        val normalised = forms.map { PersonName.normalise(it) }.distinct()
        assertEquals("all four should reduce to one: $normalised", 1, normalised.size)
        assertEquals("jose garcia", normalised.first())
    }

    @Test
    fun aMiddleNameDoesNotSplitAPerson() {
        assertEquals(
            PersonName.normalise("John Smith"),
            PersonName.normalise("John Quincy Smith")
        )
    }

    @Test
    fun anOrganisationIsNotAPerson() {
        assertNull(PersonName.normalise("Example Holdings Ltd"))
        assertNotNull(PersonName.normalise("Ada Lovelace"))
    }

    // ---- The merge -----------------------------------------------

    @Test
    fun threePlatformsDeclaringOneCityCorroborateEachOther() {
        val rows = listOf(
            row("chess", "chess.com — jdoe\nDeclared name: Jane Doe\nDeclared location: Wien"),
            row("lichess", "lichess — jdoe\nDeclared name: Jane Doe\nDeclared location: Vienna, Austria"),
            row("github", "GitHub — jdoe\nName: Jane Doe\nCompany: Example GmbH\nLocation: Vienna")
        )
        val entities = Corroboration.merge(rows, seed = "jdoe")

        val place = entities.firstOrNull { it.kind == "PLACE" && it.value.contains("vienna") }
        assertNotNull("the three declarations of Vienna should be one entity", place)
        assertEquals(
            "and should carry all three sources",
            3,
            place!!.sources.size
        )

        val person = entities.firstOrNull { it.kind == "PERSON" }
        assertNotNull("the declared name should be an entity", person)
        assertEquals("jane doe", person!!.value)
        assertEquals(3, person.sources.size)

        val org = entities.firstOrNull { it.kind == "ORG" }
        assertNotNull("the declared employer should be an entity", org)
        assertTrue(org!!.value.contains("example gmbh"))
    }

    @Test
    fun aDeclinedFieldIsNotAnAssertion() {
        val rows = listOf(
            row("chess", "chess.com — jdoe\nDeclared location: N/A\nDeclared name: unknown")
        )
        val entities = Corroboration.merge(rows, seed = "jdoe")
        assertTrue(
            "nothing was declared, so nothing should be asserted: $entities",
            entities.none { it.kind in setOf("PERSON", "PLACE", "ORG") }
        )
    }

    @Test
    fun aBareNameLabelFromANonPersonToolIsNotAPerson() {
        // bgpview's input type is "ip", so a Name: line there is an
        // autonomous system's operator, not what this person is called.
        val rows = listOf(row("bgpview", "IP: 1.2.3.4\nName: Example Telecom BV"))
        val entities = Corroboration.merge(rows, seed = "1.2.3.4")
        assertTrue(
            "an ASN operator is not the target's name: $entities",
            entities.none { it.kind == "PERSON" }
        )
    }

    @Test
    fun theSeedItselfIsNotReportedBackAsAFinding() {
        val rows = listOf(row("chess", "chess.com — jdoe\nDeclared name: jdoe"))
        val entities = Corroboration.merge(rows, seed = "jdoe")
        assertTrue(entities.none { it.value == "jdoe" })
    }

    @Test
    fun anAddressOrLinkInALabelledLineIsLeftToTheIdentifierPasses() {
        val rows = listOf(
            row("chess", "chess.com — jdoe\nLocation: https://example.com/where\n" +
                "Declared name: a@example.com")
        )
        val pivots = EntityExtractor.extract(rows, "jdoe")
        assertTrue(
            "a URL is not a place and an address is not a name: $pivots",
            pivots.none { it.kind == "PLACE" || it.kind == "PERSON" }
        )
    }
    // ---- Disagreement, and distance from the seed -----------------

    private fun rowFor(tool: String, input: String, output: String) = ResultRow(
        id = 0,
        investigationId = 1,
        toolId = tool,
        toolName = tool,
        input = input,
        status = "success",
        rawOutput = output,
        startedAt = 1,
        finishedAt = 2
    )

    /**
     * The verdict that was unreachable in the shipped app: two sources
     * answering a question that has one answer, differently.
     */
    @Test
    fun twoCountriesForOneSubjectContradictEachOther() {
        val rows = listOf(
            row("ipinfo", "1.2.3.4\nCountry: Germany"),
            row("rdap", "1.2.3.4\nCountry: Netherlands")
        )
        val entities = Corroboration.merge(rows, seed = "1.2.3.4")
        val germany = entities.first { it.value == "germany" }
        val netherlands = entities.first { it.value == "netherlands" }
        assertTrue("Germany should know it is disputed", germany.contradicted >= 1)
        assertTrue(germany.competing.contains("netherlands"))
        assertTrue(netherlands.competing.contains("germany"))
    }

    /** A city and the country it sits in are not a contradiction. */
    @Test
    fun aCityDoesNotContradictItsCountry() {
        val rows = listOf(
            row("chess", "chess.com\nDeclared location: Vienna"),
            row("lichess", "lichess\nDeclared country: Austria")
        )
        val entities = Corroboration.merge(rows, seed = "jdoe")
        assertTrue(
            "nothing here disagrees: $entities",
            entities.none { it.competing.isNotEmpty() }
        )
    }

    /** Many hosts for one domain is not a disagreement either. */
    @Test
    fun manyValuedKindsNeverContradict() {
        val rows = listOf(
            row("crtsh", "a.example.net\nb.example.net"),
            row("rapiddns", "c.example.net")
        )
        val entities = Corroboration.merge(rows, seed = "example.net")
        assertTrue(entities.none { it.competing.isNotEmpty() })
    }

    @Test
    fun aFindingFromAPivotIsMarkedAsOneStepAway() {
        val rows = listOf(
            rowFor("crtsh", "example.net", "mail.example.net"),
            rowFor("otx", "1.2.3.4", "other.example.org")
        )
        val entities = Corroboration.merge(rows, seed = "example.net")
        assertEquals(
            "found on the target itself",
            0,
            entities.first { it.value == "mail.example.net" }.hops
        )
        assertEquals(
            "found only in a pivot's results",
            1,
            entities.first { it.value == "other.example.org" }.hops
        )
    }

    /**
     * The nearest source wins: one tool asked about the target itself
     * outweighs three asked about something it named.
     */
    @Test
    fun theNearestSourceDecidesTheDistance() {
        val rows = listOf(
            rowFor("otx", "1.2.3.4", "shared.example.org"),
            rowFor("crtsh", "example.net", "shared.example.org")
        )
        val entities = Corroboration.merge(rows, seed = "example.net")
        assertEquals(0, entities.first { it.value == "shared.example.org" }.hops)
    }

}
