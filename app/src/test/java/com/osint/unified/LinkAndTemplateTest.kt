package com.osint.unified

import com.osint.unified.data.OsintFramework
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
        // Note the trailing character: a closing brace at the very end is
        // treated as prose punctuation and trimmed, so the URL under test
        // keeps one inside it.
        val fixed = LinkOpener.sanitize("https://example.com/a|b{c}d")
        assertTrue(fixed, fixed.contains("%7C"))
        assertTrue(fixed, fixed.contains("%7B"))
        assertTrue(fixed, fixed.contains("%7D"))
        assertTrue(fixed, fixed.endsWith("d"))
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

    /**
     * A parenthesis that belongs to the address, not to the sentence
     * around it. Every Wikipedia disambiguator has one.
     */
    @Test
    fun aBalancedParenthesisSurvives() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Mercury_(planet)",
            LinkOpener.sanitize("https://en.wikipedia.org/wiki/Mercury_(planet)")
        )
        // And one that closes nothing is still prose.
        assertEquals(
            "https://en.wikipedia.org/wiki/Mercury",
            LinkOpener.sanitize("https://en.wikipedia.org/wiki/Mercury)")
        )
    }

    /** Short is not the same as empty. */
    @Test
    fun aShortButRealUrlIsNotDiscarded() {
        assertEquals("http://x.io", LinkOpener.sanitize("http://x.io"))
        assertEquals("https://t.co", LinkOpener.sanitize("https://t.co"))
    }

    /**
     * The private browser's own guard is not consulted for the first
     * load, and the first load is the one carrying a string from a
     * remote feed.
     */
    @Test
    fun onlyHttpAndHttpsOpen() {
        assertEquals("", LinkOpener.sanitize("data:text/html,<script>alert(1)</script>"))
        assertEquals("", LinkOpener.sanitize("javascript:alert(1)"))
        assertEquals("", LinkOpener.sanitize("file:///data/data/com.osint.unified/databases/x"))
        assertEquals("", LinkOpener.sanitize("content://com.other.app/secret"))
        assertEquals("", LinkOpener.sanitize("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("", LinkOpener.sanitize("JaVaScRiPt:alert(1)"))
        assertEquals("https://example.com/a", LinkOpener.sanitize("https://example.com/a"))
        assertEquals("HTTP://example.com/a", LinkOpener.sanitize("HTTP://example.com/a"))
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

    /**
     * A coordinate is two numbers, or the template is left alone.
     *
     * Both halves used to go into the URL as typed, and fourteen of
     * these templates put the pair into a query string — so an
     * ampersand or a hash in the input built a different request
     * from the one the label named.
     */
    @Test
    fun aCoordinateTemplateRefusesWhatIsNotACoordinate() {
        val t = ToolTemplates.curated("coordinate").first { it.url.contains("{LAT}") }
        listOf(
            "48.8584&zoom=1, 2.2945",
            "48.8584, 2.2945#x",
            "here, there",
            "999, 2.2945",
            "48.8584, 999",
            "48.8584"
        ).forEach { bad ->
            assertEquals(bad, t.url, ToolTemplates.resolve(t, bad))
        }
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

    // ---- OSINT Framework placeholder substitution ----
    //
    // The catalogue URLs carry the author's own example data. Filling
    // them in is the whole point of running the directory against a
    // target; filling in the wrong part of the URL produces a link that
    // looks right and goes nowhere, which is the failure worth guarding.

    @Test
    fun theExampleValueIsReplacedWithTheTarget() {
        val filled = OsintFramework.fillTarget(
            "https://tools.example.org/lookup?q=email@gmail.com", "ada@lovelace.net"
        )
        assertEquals(
            "https://tools.example.org/lookup?q=ada%40lovelace.net", filled
        )
    }

    @Test
    fun theHostIsNeverTreatedAsAPlaceholder() {
        // "example.com" is in the example list because catalogue authors
        // use it as a sample domain. It is also a real host, and
        // rewriting the host is how a filled link stops resolving.
        val url = "https://www.example.com/whois"
        assertNull(OsintFramework.fillTarget(url, "acme.co"))
    }

    @Test
    fun aParameterNameIsNotItsOwnExampleValue() {
        // ?username= is the name of the field, not a sample of it.
        val url = "https://site.test/search?username=&page=1"
        val filled = OsintFramework.fillTarget(url, "ada")
        assertTrue(
            "the parameter name was overwritten: $filled",
            filled == null || filled.contains("username=")
        )
    }

    @Test
    fun aLongerPlaceholderWinsOverAShorterOneInsideIt() {
        val filled = OsintFramework.fillTarget(
            "https://site.test/u/USERNAME-HERE", "ada"
        )
        assertEquals("https://site.test/u/ada", filled)
    }

    @Test
    fun anExampleGluedIntoALongerWordIsLeftAlone() {
        // "johndoe" inside "johndoerstudio" is a coincidence, in the
        // path where the rule has to do the work rather than in the
        // host where it is excluded anyway.
        assertNull(OsintFramework.fillTarget("https://site.test/u/johndoerstudio", "ada"))
        assertNull(OsintFramework.fillTarget("https://johndoerstudio.test/about", "ada"))
    }
}
