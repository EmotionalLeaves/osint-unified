package com.osint.unified

import com.osint.unified.domain.ProfileSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // ---- Furniture, matched on the host rather than the string ----

    @Test
    fun aPersonalSiteEndingInTComIsNotTwitterFurniture() {
        // "t.co" was matched as a substring of the whole URL, so every
        // host ending in t.com went with it — and a shared personal
        // site is called the strongest tie this class has.
        assertFalse(ProfileSignals.isFurniture("https://benwyatt.com/"))
        assertFalse(ProfileSignals.isFurniture("https://janetstewart.com/about"))
        assertTrue(ProfileSignals.isFurniture("https://t.co/abc123"))
    }

    @Test
    fun anAboutPageIsNotFurniture() {
        assertFalse(ProfileSignals.isFurniture("https://janedoe.com/about.html"))
        // about.me is somebody's profile, not infrastructure.
        assertFalse(ProfileSignals.isFurniture("https://about.me/janedoe"))
    }

    @Test
    fun aLinktreeIsEvidenceNotFurniture() {
        assertFalse(ProfileSignals.isFurniture("https://linktr.ee/sarah"))
        assertFalse(ProfileSignals.isFurniture("https://linktr.ee/janedoe"))
    }

    @Test
    fun platformWidgetsAreStillFurniture() {
        assertTrue(ProfileSignals.isFurniture("https://twitter.com/intent/tweet?text=hi"))
        assertTrue(ProfileSignals.isFurniture("https://github.com/sponsors/someone"))
        assertTrue(ProfileSignals.isFurniture("https://cdn.example.com/app.js"))
        assertTrue(ProfileSignals.isFurniture("https://fonts.googleapis.com/css"))
    }

    @Test
    fun theSameSuffixIsNotTheSamePlatform() {
        // "netflix.com".endsWith("x.com") is true, which dropped a
        // link to Netflix from every x.com profile.
        assertFalse(ProfileSignals.samePlatform("netflix.com", "x.com"))
        assertFalse(ProfileSignals.samePlatform("notflickr.com", "flickr.com"))
        assertTrue(ProfileSignals.samePlatform("x.com", "x.com"))
        assertTrue(ProfileSignals.samePlatform("mobile.x.com", "x.com"))
    }

    // ---- A bot-check page is not a person ----

    @Test
    fun twoChallengePagesAreNotTheSamePerson() {
        fun acct(site: String, title: String) = ProfileSignals.Account(
            site, "https://$site/u", ProfileSignals.Signals(displayName = title)
        )
        val clusters = ProfileSignals.cluster(
            listOf(acct("one.test", "Just a moment..."), acct("two.test", "Just a moment...")),
            searchedFor = "jdoe"
        )
        assertTrue("grouped on boilerplate: $clusters", clusters.isEmpty())
    }

    @Test
    fun arealSharedNameStillClusters() {
        fun acct(site: String, title: String) = ProfileSignals.Account(
            site, "https://$site/u", ProfileSignals.Signals(displayName = title)
        )
        val clusters = ProfileSignals.cluster(
            listOf(acct("one.test", "Jane Q Doe"), acct("two.test", "Jane Q Doe")),
            searchedFor = "jdoe"
        )
        assertEquals(1, clusters.size)
    }
}

/**
 * A page that refused the sweep is not a profile.
 *
 * Taken from a real dossier. Two unrelated sites served Anubis
 * challenge pages, and because both of them carry a link to the
 * software's own repository they were grouped under "same link
 * out: github.com/techarohq/anubis" and presented as "these
 * profiles also carry the same details, which is a fact about a
 * person". The vendor's handle and contact address were then
 * extracted as observables and investigated — Crossref and
 * CourtListener were run against a bot blocker.
 *
 * The boilerplate list existed and did not catch it: it was
 * applied to the display name and the biography, and the match
 * was on a link.
 */
class ChallengePageTest {

    private fun anubis(host: String) = """
        <html><head><title>Making sure you're not a bot!</title>
        <meta property="og:title" content="Making sure you&#39;re not a bot!">
        </head><body>
        <p>Protected by <a href="https://github.com/techarohq/anubis">Anubis</a>
        from <a href="https://techaro.lol">Techaro</a>.</p>
        </body></html>
    """.trimIndent().let { it to host }

    @Test
    fun anAntiBotChallengeIsRecognised() {
        assertTrue(ProfileSignals.isChallenge(anubis("example.org").first))
        assertTrue(ProfileSignals.isChallenge("<title>Just a moment...</title>"))
        assertTrue(ProfileSignals.isChallenge("<p>Verifying you are human</p>"))
        assertTrue(ProfileSignals.isChallenge("<div>Checking your browser before accessing</div>"))
        // And an ordinary profile is not.
        assertFalse(
            ProfileSignals.isChallenge(
                """<html><head><title>Jane Doe (@jdoe)</title></head>
                   <body><p>Photographer. https://janedoe.example</p></body></html>"""
            )
        )
    }

    /**
     * A single-page app's profile says "JavaScript is required" in its
     * noscript block, and has its profile in the metadata. That page is
     * a profile, and it was being thrown away as a challenge.
     */
    @Test
    fun anAppsNoscriptLineIsNotAChallenge() {
        val spa = """<html><head><title>jdoe</title>
            <meta property="og:title" content="Jane Doe (@jdoe)">
            </head><body><noscript>JavaScript is required to use this site.</noscript>
            <div id="root"></div></body></html>"""
        assertFalse(ProfileSignals.isChallenge(spa))
        // The same words shown, on a page with nothing else, still are.
        assertTrue(ProfileSignals.isChallenge("<body><p>JavaScript is required.</p></body>"))
        // And a profile's metadata does not excuse a definite marker.
        assertTrue(
            ProfileSignals.isChallenge(
                """<meta property="og:title" content="x"><div class="cf-browser-verification">"""
            )
        )
    }

    @Test
    fun nothingIsReadOffOne() {
        val (html, host) = anubis("en.brickimedia.org")
        val signals = ProfileSignals.read(html, host)
        assertTrue(signals.isEmpty)
        assertNull(signals.displayName)
        assertTrue(signals.links.isEmpty())
    }

    @Test
    fun twoRefusalsAreNotOnePerson() {
        // The exact pairing from the dossier.
        val accounts = listOf(
            ProfileSignals.Account(
                "en.brickimedia.org",
                "https://en.brickimedia.org/wiki/User:gabebenischek",
                ProfileSignals.read(anubis("en.brickimedia.org").first, "en.brickimedia.org")
            ),
            ProfileSignals.Account(
                "Fotki",
                "https://members.fotki.com/gabebenischek/about/",
                ProfileSignals.read(anubis("members.fotki.com").first, "members.fotki.com")
            )
        )
        assertTrue(ProfileSignals.cluster(accounts, "gabebenischek").isEmpty())
    }

    /**
     * `ogp.me` is in the opening html tag of every page carrying an
     * OpenGraph card, so it grouped a Russian smallholding portal
     * with a DJ tracklist site and called it a person.
     */
    @Test
    fun aNamespaceIsNotALinkSomebodyChose() {
        listOf(
            "https://ogp.me/ns#",
            "https://purl.org/dc/elements/1.1/",
            "https://gmpg.org/xfn/11",
            "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js"
        ).forEach { assertTrue(it, ProfileSignals.isFurniture(it)) }
        // A personal site still is one.
        assertFalse(ProfileSignals.isFurniture("https://janedoe.example/about"))
    }

    @Test
    fun aRealProfileStillClusters() {
        val page = { name: String ->
            """<html><head><title>$name</title>
               <meta property="og:title" content="Gabe B">
               </head><body><a href="https://gabe.example/">site</a></body></html>"""
        }
        val accounts = listOf(
            ProfileSignals.Account("A", "https://a.example/u/g", ProfileSignals.read(page("A"), "a.example")),
            ProfileSignals.Account("B", "https://b.example/u/g", ProfileSignals.read(page("B"), "b.example"))
        )
        val clusters = ProfileSignals.cluster(accounts, "g")
        assertTrue(clusters.any { it.reason.startsWith("same link out") })
    }
}
