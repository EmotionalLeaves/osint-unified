package com.osint.unified

import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObservableRouterTest {

    private lateinit var router: ObservableRouter

    @Before
    fun setup() {
        router = ObservableRouter()
    }

    /**
     * Ten addresses people actually type, and the things nearest to them.
     *
     * The previous test used 8.8.8.8, whose four digits slipped under the
     * phone-number threshold, so it passed while 192.168.1.1 — eight
     * digits, dots for separators — was being investigated as a telephone
     * number, tool set and all.
     */
    @Test
    fun realAddressesAreAddressesAndNotTelephones() {
        listOf(
            "192.168.1.1", "10.0.0.138", "172.16.254.1", "203.0.113.45",
            "198.51.100.14", "8.8.4.4", "1.1.1.1", "140.82.121.4",
            "93.184.216.34", "127.0.0.1"
        ).forEach { address ->
            val classified = router.classify(address)
            assertTrue(
                "$address came back as ${classified::class.simpleName}",
                classified is Observable.IpAddress
            )
        }
    }

    /** Things with digits and dots that are not addresses either. */
    @Test
    fun digitsAndDotsAreNotAutomaticallyATelephone() {
        listOf("3.14159265", "1.2.3.4", "999.999.999.999", "2026-10-22").forEach {
            assertTrue(
                "$it was read as a phone number",
                router.classify(it) !is Observable.PhoneNumber
            )
        }
    }

    /** And the numbers that are telephones still are. */
    @Test
    fun telephonesSurviveTheAddressCheck() {
        listOf(
            "+15551234567", "+44 20 7946 0958", "(415) 555-0123",
            "415-555-0123", "5551234567"
        ).forEach {
            assertTrue(
                "$it stopped being a phone number",
                router.classify(it) is Observable.PhoneNumber
            )
        }
    }

    @Test
    fun classifiesEmail() {
        assertTrue(router.classify("jane.doe@example.com") is Observable.Email)
    }

    @Test
    fun classifiesUsername() {
        assertTrue(router.classify("john_doe_2024") is Observable.Username)
    }

    @Test
    fun classifiesDomain() {
        assertTrue(router.classify("example.com") is Observable.Domain)
        assertTrue(router.classify("sub.example.co.uk") is Observable.Domain)
    }

    @Test
    fun classifiesIpv4AndCidr() {
        assertTrue(router.classify("8.8.8.8") is Observable.IpAddress)
        assertTrue(router.classify("10.0.0.0/8") is Observable.IpAddress)
    }

    @Test
    fun rejectsInvalidIpAsUnknownOrOther() {
        // 999.999.999.999 is not a valid IPv4; must not classify as IP.
        assertTrue(router.classify("999.999.999.999") !is Observable.IpAddress)
    }

    @Test
    fun classifiesPhone() {
        assertTrue(router.classify("+14155551234") is Observable.PhoneNumber)
    }

    @Test
    fun classifiesCoordinate() {
        val result = router.classify("40.7128, -74.0060")
        assertTrue(result is Observable.Coordinate)
        result as Observable.Coordinate
        assertEquals(40.7128, result.lat, 0.0001)
        assertEquals(-74.0060, result.lon, 0.0001)
    }

    @Test
    fun outOfRangeCoordinateIsNotCoordinate() {
        // Latitude 999 is invalid.
        assertTrue(router.classify("999.0, 10.0") !is Observable.Coordinate)
    }

    @Test
    fun emailSuggestsKeylessFirst() {
        val tools = router.suggestTools(Observable.Email("a@b.com"))
        // Keyless tools lead so email works with no backend.
        assertEquals("breaches", tools.first().id)
        assertTrue(tools.any { it.id == "gravatar" })
        assertTrue(tools.any { it.id == "emailaccounts" })
        // Nothing that needs the Python backend may come before a keyless tool.
        val firstCloud = tools.indexOfFirst { it.executionMode == ExecutionMode.CLOUD }
        assertTrue(firstCloud == -1 || firstCloud > 3)
    }

    @Test
    fun classifiesCryptoWallets() {
        assertTrue(router.classify("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa") is Observable.Wallet)
        assertTrue(router.classify("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045") is Observable.Wallet)
        assertEquals("wallet", router.suggestTools(Observable.Wallet("0x0")).first().id)
    }

    @Test
    fun classifiesTailNumbers() {
        assertTrue(router.classify("N628TS") is Observable.Aircraft)
        assertEquals("aircraftreg", router.suggestTools(Observable.Aircraft("N628TS")).first().id)
    }

    @Test
    fun everySuggestedToolIsRegistered() {
        // A typo in a route would silently drop a tool; catch it here instead.
        val observables = listOf(
            Observable.Email("a@b.com"), Observable.Username("u"), Observable.Domain("b.com"),
            Observable.IpAddress("8.8.8.8"), Observable.PhoneNumber("+15551234567"),
            Observable.Coordinate("0,0", 0.0, 0.0), Observable.Url("https://b.com"),
            Observable.ImageUrl("https://b.com/a.jpg"), Observable.Name("jane"),
            Observable.Wallet("0x0"), Observable.Aircraft("N1")
        )
        observables.forEach { o ->
            assertTrue("no tools for ${o.typeLabel}", router.suggestTools(o).isNotEmpty())
        }
    }

    @Test
    fun registryHasNoDuplicateIds() {
        val ids = ToolRegistry.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun deepSourcesRegistered() {
        listOf("keybase", "wikidata", "breaches", "subsweep", "gleif", "edgar",
            "sanctions", "fbi", "wallet", "aircraftreg", "sunterrain", "cve")
            .forEach { assertNotNull(it, ToolRegistry.byId(it)) }
    }

    @Test
    fun usernameIncludesLiveFetchers() {
        val ids = router.suggestTools(Observable.Username("octocat")).map { it.id }
        assertTrue(ids.contains("github"))
        assertTrue(ids.contains("websearch"))
    }

    @Test
    fun classifyAsForcesType() {
        assertTrue(router.classifyAs("anything", "email") is Observable.Email)
        assertTrue(router.classifyAs("anything", "domain") is Observable.Domain)
        assertTrue(router.classifyAs("anything", "name") is Observable.Name)
    }

    @Test
    fun newToolsRegistered() {
        assertNotNull(ToolRegistry.byId("websearch"))
        assertNotNull(ToolRegistry.byId("github"))
        assertNotNull(ToolRegistry.byId("emailaccounts"))
    }

    @Test
    fun domainSuggestsSubfinderFirst() {
        val tools = router.suggestTools(Observable.Domain("example.com"))
        assertEquals("subfinder", tools.first().id)
    }

    @Test
    fun unknownSuggestsNothing() {
        assertTrue(router.suggestTools(Observable.Unknown("???")).isEmpty())
    }

    /**
     * Six hex characters written the way a transponder address is
     * written. The pattern for this existed and was never consulted,
     * so "4CA853" ran the whole username fan-out — three sweeps
     * across thousands of third-party sites — and the aircraft
     * lookup's Mode-S branch could not be reached by typing one.
     */
    @Test
    fun aModeSAddressIsAnAircraft() {
        assertTrue(router.classify("4CA853") is Observable.Aircraft)
        assertTrue(router.classify("A1B2C3") is Observable.Aircraft)
        // And a handle that happens to be hex is still a handle.
        assertTrue(router.classify("abc123") is Observable.Username)
        assertTrue(router.classify("cafe01") is Observable.Username)
        assertTrue(router.classify("123456") is Observable.Username)
    }

    /**
     * A single word is a word, not somebody's handle.
     *
     * The username pattern matched any run of three to thirty
     * characters and was tested before the name fallback, so every
     * bare word anybody typed went to the account sweep: Keybase,
     * Reddit, Hacker News and Telegram were asked whether anyone is
     * called "london", all four said no, and the dossier read
     * NOTHING FOUND. The sixteen sources that would have had
     * something to say about a place, a person or a company were
     * never asked at all. It fires on the most natural thing to
     * type into a box labelled "query any target".
     */
    @Test
    fun aBareWordIsANameAndNotAHandle() {
        listOf("london", "tesla", "gazprom", "smith", "putin", "nestle").forEach {
            assertTrue(it, router.classify(it) is Observable.Name)
        }
        // What a name does not carry is still a handle.
        listOf("mrbeast_", "john.doe9", "x_ae_a12", "a-b-c", "averyveryverylonghandle").forEach {
            assertTrue(it, router.classify(it) is Observable.Username)
        }
    }

    /**
     * Forcing a type says which question to ask, not that the input
     * is a valid subject for it. Only the coordinate branch checked;
     * picking DOMAIN and typing a person's name fired fifteen domain
     * tools at a two-word phrase.
     */
    @Test
    fun aForcedTypeTheInputCannotSatisfyFallsBackToAName() {
        assertTrue(router.classifyAs("john smith", "domain") is Observable.Name)
        assertTrue(router.classifyAs("bob", "email") is Observable.Name)
        assertTrue(router.classifyAs("not an address", "ip") is Observable.Name)
        assertTrue(router.classifyAs("hello", "phone") is Observable.Name)
        assertTrue(router.classifyAs("example.com", "image") is Observable.Name)
        // And the ones it can satisfy still go where they were sent.
        assertTrue(router.classifyAs("example.com", "domain") is Observable.Domain)
        assertTrue(router.classifyAs("a@b.com", "email") is Observable.Email)
        assertTrue(router.classifyAs("8.8.8.8", "ip") is Observable.IpAddress)
        assertTrue(router.classifyAs("+14155550123", "phone") is Observable.PhoneNumber)
        assertTrue(router.classifyAs("london", "username") is Observable.Username)
        assertTrue(router.classifyAs("https://x/y.jpg", "image") is Observable.ImageUrl)
    }

    /**
     * A MAC address is not an IP address. The IPv6 pattern took
     * anything with colons in it, so hardware addresses and clock
     * times were sent to ipwho.is, Shodan, RDAP and BGPView.
     */
    @Test
    fun hardwareAddressesAreNotIpAddresses() {
        assertFalse(router.classify("ab:cd:ef:12:34:56") is Observable.IpAddress)
        assertFalse(router.classify("00:1A:2B:3C:4D:5E") is Observable.IpAddress)
        assertFalse(router.classify("12:30:45") is Observable.IpAddress)
        // Real ones still are.
        assertTrue(router.classify("2001:db8::1") is Observable.IpAddress)
        assertTrue(router.classify("2001:0db8:85a3:0000:0000:8a2e:0370:7334") is Observable.IpAddress)
        assertTrue(router.classify("::1") is Observable.IpAddress)
    }

    /** Forcing the type skipped the bounds check, so a point off the
     *  Earth was sent to three geocoders as a place. */
    @Test
    fun aForcedCoordinateStillHasToBeOnEarth() {
        assertTrue(router.classifyAs("500, 900", "coordinate") is Observable.Name)
        assertTrue(router.classifyAs("51.5, -0.1", "coordinate") is Observable.Coordinate)
    }
}
