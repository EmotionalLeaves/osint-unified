package com.osint.unified

import com.osint.unified.domain.ToolTemplates
import com.osint.unified.ui.common.LinkOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the link handling that broke in the field — a URL carrying a raw
 * space was silently unopenable — and the template engine that turns a
 * catalogue entry into something runnable.
 */
class LinkAndTemplateTest {

    @Test
    fun spacesInLinksAreEncodedNotDropped() {
        val fixed = LinkOpener.sanitize("https://example.com/user/John Smith")
        assertEquals("https://example.com/user/John%20Smith", fixed)
    }

    @Test
    fun existingEscapesAreLeftAlone() {
        // Re-encoding %20 into %2520 would break a link that already worked.
        val url = "https://example.com/a%20b?q=x%26y"
        assertEquals(url, LinkOpener.sanitize(url))
    }

    @Test
    fun otherIllegalCharactersAreEncoded() {
        val fixed = LinkOpener.sanitize("https://example.com/a|b{c}")
        assertTrue(fixed, fixed.contains("%7C"))
        assertTrue(fixed, fixed.contains("%7B"))
        assertTrue(fixed, fixed.contains("%7D"))
    }

    @Test
    fun trailingProsePunctuationIsTrimmed() {
        assertEquals("https://example.com/x", LinkOpener.sanitize("https://example.com/x)."))
    }

    @Test
    fun emptyOrSchemeOnlyInputYieldsNothingToOpen() {
        assertEquals("", LinkOpener.sanitize("   "))
        assertEquals("", LinkOpener.sanitize("https://"))
    }

    @Test
    fun searchUrlsBecomeRunnableTemplates() {
        val t = ToolTemplates.templateFor("https://example.com/search?q=placeholder&lang=en")
        assertNotNull(t)
        assertEquals("https://example.com/search?q={}&lang=en", t!!.url)
        assertEquals("https://example.com/search?q=jane%20doe&lang=en", t.resolve("jane doe"))
    }

    @Test
    fun plainHomepagesAreNotPretendedToBeRunnable() {
        // A link with nothing to fill in must stay an ordinary link rather
        // than growing a RUN button that does nothing useful.
        assertNull(ToolTemplates.templateFor("https://example.com/about"))
        assertNull(ToolTemplates.templateFor("https://example.com/?ref=nav"))
    }

    @Test
    fun coordinateTemplatesTakeBothHalves() {
        val t = ToolTemplates.curated("coordinate").first { it.url.contains("{LAT}") }
        val resolved = ToolTemplates.resolve(t, "48.8584, 2.2945")
        assertTrue(resolved, resolved.contains("48.8584"))
        assertTrue(resolved, resolved.contains("2.2945"))
        assertTrue(resolved, !resolved.contains("{LAT}") && !resolved.contains("{LON}"))
    }

    @Test
    fun everyCuratedTemplateHasAPlaceholder() {
        val types = listOf("domain", "ip", "email", "name", "coordinate", "phone", "wallet", "image", "aircraft")
        types.forEach { type ->
            val templates = ToolTemplates.curated(type)
            assertTrue("no templates for $type", templates.isNotEmpty())
            templates.forEach { t ->
                assertTrue(
                    "${t.name} (${t.type}) has no placeholder: ${t.url}",
                    t.url.contains("{}") || t.url.contains("{LAT}")
                )
                assertTrue("${t.name} is not https: ${t.url}", t.url.startsWith("https://"))
            }
        }
    }
}
