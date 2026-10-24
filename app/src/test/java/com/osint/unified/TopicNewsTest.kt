package com.osint.unified

import com.osint.unified.domain.StoryClusters
import com.osint.unified.domain.TopicNews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The topic watch: what counts as new coverage, and how it is said.
 */
class TopicNewsTest {

    private val now = 1_792_800_000_000L // 2026-10-24
    private val today = TopicNews.stamp(now - 3_600_000)
    private val lastWeek = TopicNews.stamp(now - 7 * 24 * 3_600_000L)

    private fun article(title: String, domain: String = "example.com", seen: String? = today) =
        StoryClusters.Article(title, domain, "https://$domain/x", seen, null)

    @Test
    fun gdeltsArticlesAreRead() {
        val body = """{"articles":[
            {"url":"https://a.example/1","title":"Ceasefire talks resume in Doha","seendate":"20261024T101500Z","domain":"a.example","sourcecountry":"Qatar"},
            {"url":"https://b.example/2","title":"Ceasefire talks resume","seendate":"20261024T111500Z","domain":"b.example"}
        ]}"""
        val read = TopicNews.parseGdelt(body)
        assertEquals(2, read.size)
        assertEquals("a.example", read[0].domain)
        assertEquals("20261024T101500Z", read[0].seen)
    }

    /** GDELT's "no coverage" is an answer; its rate-limit sentence is not. */
    @Test
    fun anEmptyAnswerAndARefusalAreDifferent() {
        assertEquals(0, TopicNews.parseGdelt("{}").size)
        var refused = false
        try {
            TopicNews.parseGdelt("Please limit requests to one every 5 seconds")
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun googleNewsTitlesLoseTheirPublisher() {
        val body = """<?xml version="1.0"?><rss version="2.0"><channel><title>x</title>
            <item><title>Ceasefire talks resume in Doha - Reuters</title>
            <link>https://news.google.com/rss/articles/abc</link>
            <pubDate>Sat, 24 Oct 2026 09:00:00 GMT</pubDate>
            <source url="https://www.reuters.com">Reuters</source></item>
            <item><title>Markets &amp; the war - BBC</title>
            <link>https://news.google.com/rss/articles/def</link>
            <pubDate>Sat, 24 Oct 2026 08:00:00 GMT</pubDate>
            <source url="https://www.bbc.co.uk">BBC</source></item>
            </channel></rss>"""
        val read = TopicNews.parseGoogleRss(body)
        assertEquals(listOf("Ceasefire talks resume in Doha", "Markets & the war"), read.map { it.title })
        assertEquals("reuters.com", read[0].domain)
        assertEquals("20261024T090000Z", read[0].seen)
    }

    @Test
    fun aPageThatIsNotAFeedIsNotAnswered() {
        var refused = false
        try {
            TopicNews.parseGoogleRss("<html><body>Our systems have detected unusual traffic</body></html>")
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    /** The same headline, however it is punctuated or cased, is one headline. */
    @Test
    fun aHeadlineIsTheSameHeadlineAcrossSources() {
        assertEquals(
            TopicNews.key("Ceasefire talks resume in Doha"),
            TopicNews.key("CEASEFIRE talks resume — in Doha!")
        )
        assertTrue(TopicNews.key("Ceasefire talks resume") != TopicNews.key("Ceasefire talks collapse"))
    }

    @Test
    fun onlyWhatHasNotBeenSeenIsNew() {
        val old = article("Talks resume in Doha")
        val fresh = article("Talks collapse in Doha", "b.example")
        val seen = setOf(TopicNews.key(old.title))
        val unseen = TopicNews.unseen(listOf(old, fresh, fresh.copy(domain = "c.example")), seen, now)
        assertEquals(listOf("Talks collapse in Doha"), unseen.values.map { it.title })
    }

    /** An item a feed resurfaces from last week is not news today. */
    @Test
    fun anOldItemIsNotNewJustBecauseItWasForgotten() {
        val stale = article("Something from last week", seen = lastWeek)
        val undated = article("No date on this one", seen = null)
        val unseen = TopicNews.unseen(listOf(stale, undated), emptySet(), now)
        assertEquals(listOf("No date on this one"), unseen.values.map { it.title })
    }

    @Test
    fun theMemoryKeepsTheNewestAndIsBounded() {
        val previous = (1..TopicNews.MEMORY).map { "old$it" }
        val kept = TopicNews.remember(previous, listOf("new1", "old1"))
        assertEquals(TopicNews.MEMORY, kept.size)
        assertEquals("new1", kept[0])
        assertEquals("old1", kept[1])
    }

    /** Stories first, with how many outlets carry each. */
    @Test
    fun theSummaryCountsStoriesAndOutlets() {
        val line = TopicNews.describe(
            "Doha",
            listOf(
                article("Ceasefire talks resume in Doha", "a.example"),
                article("Ceasefire talks resume in Doha today", "b.example"),
                article("Airport closed after storm", "c.example")
            )
        )
        assertNotNull(line)
        assertTrue(line!!, line.startsWith("3 new articles on “Doha” in 2 stories: "))
        assertTrue(line, line.contains("(2 outlets)"))
        assertNull(TopicNews.describe("Doha", emptyList()))
    }

    @Test
    fun theFirstCheckSaysWhatItSawWithoutCallingItNew() {
        val line = TopicNews.baseline("Doha", listOf(article("One"), article("Two other words")))
        assertTrue(line, line.startsWith("Watching “Doha” from now: 2 articles"))
    }
}
