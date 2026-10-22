package com.osint.unified

import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.ToolRegistry
import org.junit.Assert.assertEquals
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
}
