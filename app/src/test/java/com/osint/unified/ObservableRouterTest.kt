package com.osint.unified

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
        assertEquals("gravatar", tools.first().id)
        assertTrue(tools.any { it.id == "emailaccounts" })
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
