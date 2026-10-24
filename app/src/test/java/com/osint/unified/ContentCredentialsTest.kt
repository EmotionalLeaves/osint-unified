package com.osint.unified

import com.osint.unified.domain.ContentCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strongest question left to ask about an image offline, and the
 * one mistake it must not make: treating an unverified claim as proof.
 */
class ContentCredentialsTest {

    /** A JPEG-ish block with a C2PA manifest's own strings in it. */
    private fun withManifest(extra: String = ""): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xEB.toByte())
        val body = ("JP\u0000\u0000jumbc2pa" +
            "urn:uuid:1234-5678" +
            "c2pa.actions" + extra).toByteArray(Charsets.US_ASCII)
        return head + body
    }

    @Test
    fun aPlainPhotographSaysNothingAndIsSaidToSayNothing() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val read = ContentCredentials.read(bytes)
        assertFalse(read.hasManifest)
        assertFalse(read.saysGenerated)
        val text = ContentCredentials.describe(read)
        assertTrue(text, text.contains("Nothing found"))
        // And the absence is described as a fact about the file, not
        // about the picture.
        assertTrue(text, text.contains("screenshot"))
    }

    @Test
    fun aManifestIsFoundAndIsNotCalledProof() {
        val read = ContentCredentials.read(withManifest())
        assertTrue(read.hasManifest)
        assertFalse("nothing here says a model made it", read.saysGenerated)
        val text = ContentCredentials.describe(read)
        assertTrue(text, text.contains("Content Credentials"))
        assertTrue("the claim must not read as verified", text.contains("not checked here"))
        assertTrue(text, text.contains("CLAIMS"))
    }

    @Test
    fun aGenerativeAssertionIsReadAsOne() {
        val read = ContentCredentials.read(withManifest("trainedAlgorithmicMedia"))
        assertTrue(read.saysGenerated)
        val text = ContentCredentials.describe(read)
        assertTrue(text, text.contains("MACHINE-GENERATED"))
        assertTrue("still not proof", text.contains("not checked here"))
    }

    @Test
    fun aGeneratorThatWritesItsOwnNameIsNamed() {
        val bytes = ("\u0089PNG\r\n\u001a\n" +
            "tEXtparameters\u0000a prompt, Steps: 20, Model: Stable Diffusion XL")
            .toByteArray(Charsets.ISO_8859_1)
        val read = ContentCredentials.read(bytes)
        val text = ContentCredentials.describe(read)
        assertTrue(text, text.contains("Stable Diffusion"))
        assertTrue(text, text.contains("parameters"))
    }

    /**
     * A marker must not be assembled out of two unrelated fragments
     * separated by binary.
     */
    @Test
    fun aMarkerSplitByBinaryIsNotAMarker() {
        val bytes = "trained".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x01, 0x02, 0x03) +
            "AlgorithmicMedia".toByteArray(Charsets.US_ASCII)
        assertFalse(ContentCredentials.read(bytes).saysGenerated)
    }

    /**
     * A caption is text, and these names are ordinary words or
     * fragments of them. "Dallas" printed "names DALL·E" under a
     * heading saying it was metadata the tool had written.
     */
    @Test
    fun anOrdinaryWordIsNotAGeneratorName() {
        listOf(
            "Photo taken in Dallas, Texas",
            "Portrait of Kendall Fisher",
            "Randall Park at sunset",
            "una imagen del puerto de Valencia",
            "Lens profile: flux correction v2",
            "Shot on the firefly trail"
        ).forEach { caption ->
            val read = ContentCredentials.read(("\u0000\u0000" + caption).toByteArray())
            assertTrue(
                caption + " -> " + read.findings.map { it.what },
                read.findings.none { it.what.startsWith("names ") }
            )
        }
    }

    /** And a real one still is. */
    @Test
    fun arealGeneratorNameIsStillFound() {
        listOf(
            "Software: DALL-E 3" to "DALL",
            "parameters: Stable Diffusion v1.5" to "Stable Diffusion",
            "Created with Midjourney" to "Midjourney",
            "Adobe Firefly" to "Firefly"
        ).forEach { (text, _) ->
            val read = ContentCredentials.read(("\u0000" + text).toByteArray())
            assertTrue(
                text + " -> " + read.findings.map { it.what },
                read.findings.any { it.what.startsWith("names ") }
            )
        }
    }

    /** One name, once, however many times it appears. */
    @Test
    fun aNameIsNotReportedTwice() {
        val read = ContentCredentials.read(
            "\u0000Adobe Firefly\u0000Firefly\u0000".toByteArray()
        )
        assertEquals(
            1,
            read.findings.count { it.what == "names Adobe Firefly" }
        )
    }
}
