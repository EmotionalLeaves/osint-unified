package com.osint.unified

import com.osint.unified.domain.StoryClusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping the news.
 *
 * A flat list of seventy-five headlines hides the one thing that
 * matters in it: that three of them are the same story carried by
 * thirty outlets and one of them is a story carried by nobody
 * else.
 */
class StoryClustersTest {

    private fun a(title: String, domain: String, seen: String = "20261020T000000Z") =
        StoryClusters.Article(title, domain, "https://$domain/x", seen, "US")

    @Test
    fun theSameStoryFromManyOutletsIsOneStory() {
        val clusters = StoryClusters.cluster(
            listOf(
                a("Acme Corp announces factory closure in Dresden", "reuters.com"),
                a("Acme Corp announces factory closure in Dresden", "apnews.com"),
                a("Acme announces Dresden factory closure", "bbc.co.uk"),
                a("Regulator fines Beta Industries over safety breach", "ft.com")
            )
        )
        assertEquals(2, clusters.size)
        // And the widely-carried one is first.
        assertEquals(3, clusters.first().publishers)
        assertTrue(clusters.first().headline, clusters.first().headline.contains("Dresden"))
    }

    @Test
    fun oneOutletRunningAStoryFourTimesIsOnePublisher() {
        val clusters = StoryClusters.cluster(
            (1..4).map { a("Acme Corp announces factory closure in Dresden", "reuters.com") }
        )
        assertEquals(1, clusters.size)
        assertEquals(1, clusters.first().publishers)
        assertEquals(4, clusters.first().articles.size)
    }

    /**
     * Chaining is how naive clustering ends up with one group
     * holding everything: A is near B, B is near C, and C has
     * nothing to do with A.
     */
    @Test
    fun unrelatedStoriesDoNotChainIntoOne() {
        val clusters = StoryClusters.cluster(
            listOf(
                a("Flood warning issued for Rhine valley", "dw.com"),
                a("Rhine valley flood warning lifted after rainfall eases", "faz.net"),
                a("Rainfall records broken across southern Spain", "elpais.com"),
                a("Southern Spain braces for further storms", "abc.es")
            )
        )
        assertTrue("got ${clusters.size}", clusters.size >= 2)
        clusters.forEach { c ->
            val rhine = c.articles.count { it.title.contains("Rhine") }
            val spain = c.articles.count { it.title.contains("Spain") }
            assertTrue("a cluster mixed Rhine and Spain", rhine == 0 || spain == 0)
        }
    }

    @Test
    fun theDatesAreTheRangeTheStoryRanOver() {
        val c = StoryClusters.cluster(
            listOf(
                a("Acme Corp announces factory closure in Dresden", "a.com", "20261018T100000Z"),
                a("Acme Corp announces factory closure in Dresden", "b.com", "20261020T100000Z")
            )
        ).first()
        assertEquals("2026-10-18", StoryClusters.day(c.first))
        assertEquals("2026-10-20", StoryClusters.day(c.last))
    }

    @Test
    fun aShortHeadlineAndALongOneAboutTheSameEventStillMeet() {
        // Containment rather than Jaccard: these share every word
        // the short one has, which Jaccard would score at 0.33.
        val short = StoryClusters.terms("Dresden factory closes")
        val long = StoryClusters.terms(
            "Dresden factory closes as Acme Corp confirms nine hundred job losses"
        )
        assertTrue(StoryClusters.overlap(short, long) >= 0.9)
    }
}
