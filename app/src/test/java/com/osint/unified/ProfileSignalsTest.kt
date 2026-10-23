package com.osint.unified

import com.osint.unified.domain.ProfileSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning "forty sites have an account with this name" into "six of them
 * agree with each other", which is the only part of a username sweep an
 * investigator can act on.
 */
class ProfileSignalsTest {

    private fun page(
        title: String,
        ogTitle: String? = null,
        description: String? = null,
        image: String? = null,
        body: String = ""
    ) = buildString {
        append("<html><head><title>").append(title).append("</title>")
        ogTitle?.let { append("""<meta property="og:title" content="$it">""") }
        description?.let { append("""<meta name="og:description" content="$it">""") }
        image?.let { append("""<meta content="$it" property="og:image">""") }
        append("</head><body>").append(body).append("</body></html>")
    }

    @Test
    fun readsTheFourThingsAProfilePageSaysAboutItsOwner() {
        val s = ProfileSignals.read(
            page(
                title = "jdoe (@jdoe) · Example",
                ogTitle = "Jane Doe",
                description = "Hydrologist. Opinions my own.",
                image = "https://cdn.example.net/a/1234.jpg",
                body = """<a href="https://janedoe.co.uk/">site</a>
                          <a href="https://fonts.googleapis.com/x">font</a>
                          <a href="https://example.com/help">help</a>"""
            ),
            pageHost = "example.com"
        )
        assertEquals("Jane Doe", s.displayName)
        assertEquals("Hydrologist. Opinions my own.", s.bio)
        assertEquals("https://cdn.example.net/a/1234.jpg", s.avatar)
        // The personal site survives; the font provider and the page's own
        // platform do not.
        assertTrue("links were ${s.links}", s.links.any { it.startsWith("janedoe.co.uk") })
        assertTrue("links were ${s.links}", s.links.none { it.contains("googleapis") })
        assertTrue("links were ${s.links}", s.links.none { it.contains("example.com") })
    }

    @Test
    fun fallsBackToTheTitleAndReportsNothingItDoesNotHave() {
        val s = ProfileSignals.read(page(title = "Jane D"), pageHost = "example.com")
        assertEquals("Jane D", s.displayName)
        assertNull(s.bio)
        assertNull(s.avatar)
        assertTrue(s.links.isEmpty())
        assertTrue(ProfileSignals.read("", "example.com").isEmpty)
    }

    /** The same address written two ways is one address. */
    @Test
    fun linksAreComparedAfterNormalising() {
        assertEquals(
            ProfileSignals.normaliseLink("https://WWW.Janedoe.co.uk/about/"),
            ProfileSignals.normaliseLink("http://janedoe.co.uk/about")
        )
    }

    /** Two profiles pointing at the same personal site is a deliberate act. */
    @Test
    fun accountsSharingALinkAreGroupedAheadOfAccountsSharingAName() {
        val accounts = listOf(
            ProfileSignals.Account(
                "Alpha", "https://alpha.test/jdoe",
                ProfileSignals.Signals(
                    displayName = "jdoe", links = listOf("janedoe.co.uk")
                )
            ),
            ProfileSignals.Account(
                "Beta", "https://beta.test/jdoe",
                ProfileSignals.Signals(
                    displayName = "jdoe", links = listOf("janedoe.co.uk")
                )
            ),
            ProfileSignals.Account(
                "Gamma", "https://gamma.test/jdoe",
                ProfileSignals.Signals(displayName = "jdoe")
            )
        )
        val clusters = ProfileSignals.cluster(accounts)
        assertTrue("no clusters", clusters.isNotEmpty())
        assertTrue(
            "first cluster was ${clusters.first().reason}",
            clusters.first().reason.startsWith("same link out")
        )
        assertEquals(2, clusters.first().accounts.size)
    }

    /** One account agreeing with itself is not agreement. */
    @Test
    fun aLoneAccountIsNeverACluster() {
        val clusters = ProfileSignals.cluster(
            listOf(
                ProfileSignals.Account(
                    "Alpha", "https://alpha.test/jdoe",
                    ProfileSignals.Signals(displayName = "Jane Doe", bio = "Hydrologist here")
                )
            )
        )
        assertTrue("clusters were $clusters", clusters.isEmpty())
    }

    /** And the same pair is not reported four times over. */
    @Test
    fun aPairThatAgreesOnEverythingIsReportedOnce() {
        val signals = ProfileSignals.Signals(
            displayName = "Jane Doe",
            bio = "Hydrologist. Opinions my own.",
            avatar = "https://cdn.example.net/a/1234.jpg",
            links = listOf("janedoe.co.uk")
        )
        val clusters = ProfileSignals.cluster(
            listOf(
                ProfileSignals.Account("Alpha", "https://alpha.test/jdoe", signals),
                ProfileSignals.Account("Beta", "https://beta.test/jdoe", signals)
            )
        )
        assertEquals(1, clusters.size)
    }
}
