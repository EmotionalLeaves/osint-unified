package com.osint.unified

import com.osint.unified.domain.Identify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyTest {

    private fun top(s: String) = Identify.of(s).first().label

    @Test
    fun recognisesTheCommonShapes() {
        assertEquals("IPv4 address", top("8.8.4.4"))
        assertEquals("Email address", top("a.b+c@example.co.uk"))
        assertEquals("UUID", top("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals("MAC address", top("00:1b:44:11:3a:b7"))
        assertEquals("Ethereum address", top("0xde0b295669a9fd93d5f28d9ec85e40f4cb697bae"))
        assertEquals("Bitcoin address", top("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
    }

    /** 255 is in range; 256 is not, and must not pass as an address. */
    @Test
    fun rangeIsCheckedNotJustShape() {
        assertEquals("IPv4 address", top("255.255.255.0"))
        assertTrue(Identify.of("256.1.1.1").none { it.label == "IPv4 address" })
        assertTrue(Identify.of("91.5, -181.2").none { it.label == "Coordinate" })
        assertEquals("Coordinate", top("48.8584, 2.2945"))
    }

    @Test
    fun hashesAreToldApartByLength() {
        assertEquals("MD5 or NTLM hash", top("d41d8cd98f00b204e9800998ecf8427e"))
        assertEquals("SHA-256 hash", top("e".repeat(64)))
        assertEquals("SHA-1 hash", top("a".repeat(40)))
    }

    /** A string can honestly be two things; both readings are offered. */
    @Test
    fun ambiguityIsReportedRatherThanResolved() {
        val labels = Identify.of("a1b2c3").map { it.label }
        assertTrue(labels.contains("ICAO 24-bit address"))
        assertTrue(labels.contains("Hex-encoded bytes"))
    }

    @Test
    fun aJwtIsSeenAsAJwtAndCarriesItsDecoder() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val g = Identify.of(jwt).first()
        assertEquals("JWT", g.label)
        assertEquals("jwt", g.decoderId)
    }

    @Test
    fun timestampsAreRecognisedInBothUnits() {
        assertEquals("Unix timestamp", top("1792683655"))
        assertEquals("Unix timestamp", top("1792683655000"))
        // A small number is just a number.
        assertTrue(Identify.of("42").none { it.label == "Unix timestamp" })
    }

    @Test
    fun percentEncodingAndBase64CarryTheRightDecoder() {
        assertEquals("urld", Identify.of("hello%20world%21").first().decoderId)
        assertEquals("b64d", Identify.of("aGVsbG8gd29ybGQh").first().decoderId)
    }

    @Test
    fun nothingStructuralIsSaidPlainly() {
        assertEquals("Plain text", top("the quick brown fox"))
        assertTrue(Identify.of("   ").isEmpty())
    }
}
