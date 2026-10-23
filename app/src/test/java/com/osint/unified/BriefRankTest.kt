package com.osint.unified

import com.osint.unified.ui.godeye.GodEyeUiState
import com.osint.unified.ui.godeye.GeoBlip
import com.osint.unified.ui.godeye.WorldBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ticker chooses to shout about.
 *
 * The severity words are read out of free-text labels, and two of
 * them — red and orange — are also counties, rivers and towns. A
 * flood warning on the Red River was being promoted over a genuine
 * orange alert for the top of the headline.
 */
class BriefRankTest {

    private fun headlineOf(vararg gdacs: String): String {
        val state = GodEyeUiState(
            gdacs = gdacs.map { GeoBlip(0.0, 0.0, it) },
            loaded = setOf("gdacs")
        )
        return WorldBrief.headline(WorldBrief.build(state))
    }

    @Test
    fun aPlaceNamedRedDoesNotOutrankARealAlert() {
        val headline = headlineOf(
            "Flooding on the Red River near Fargo",
            "Orange alert for flood in Bangladesh"
        )
        assertTrue(headline, headline.contains("Bangladesh"))
    }

    @Test
    fun aRealRedAlertStillWins() {
        val headline = headlineOf(
            "Orange alert for flood in Bangladesh",
            "Red alert for Tropical Cyclone Freddy"
        )
        assertTrue(headline, headline.contains("Freddy"))
    }

    /**
     * Feeds that answered and found nothing are an answer. The ticker
     * used to call that "nothing loaded yet", which is the opposite.
     */
    @Test
    fun aQuietWorldIsNotAnEmptyOne() {
        val state = GodEyeUiState(loaded = setOf("gdacs", "cyclones", "tsunamis"))
        val brief = WorldBrief.build(state)
        assertTrue(brief.quiet.isNotEmpty())
        val headline = WorldBrief.headline(brief)
        assertTrue(headline, headline.startsWith("all quiet"))
    }

    /**
     * And a section built from a feed that has since failed says so,
     * rather than sitting beside the not-reporting list contradicting
     * it.
     */
    @Test
    fun aSectionFromABrokenFeedIsMarked() {
        val state = GodEyeUiState(
            fires = listOf(GeoBlip(1.0, 2.0, "a fire")),
            loaded = setOf("fires"),
            feedTrouble = mapOf("fires" to "timeout")
        )
        val titles = WorldBrief.build(state).sections.map { it.title }
        assertTrue(titles.toString(), titles.any { it.contains("LAST KNOWN, FEED DOWN") })
    }

    /** A healthy feed's section carries no such mark. */
    @Test
    fun aHealthySectionIsNotMarked() {
        val state = GodEyeUiState(
            fires = listOf(GeoBlip(1.0, 2.0, "a fire")),
            loaded = setOf("fires")
        )
        val titles = WorldBrief.build(state).sections.map { it.title }
        assertEquals(1, titles.size)
        assertTrue(titles[0], !titles[0].contains("FEED DOWN"))
    }
}
