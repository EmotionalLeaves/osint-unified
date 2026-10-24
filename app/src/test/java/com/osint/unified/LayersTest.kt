package com.osint.unified

import com.osint.unified.ui.godeye.Layers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table that describes every layer, against the layers that
 * actually exist.
 *
 * Thirty-four layers used to be written out by hand in four places,
 * and the places disagreed: the area report's wording table was
 * missing `spaceWeather`, so that caveat printed the raw key at the
 * person, and the brief's health list covered eleven keys of
 * thirty-four, so twenty-three layers could not be reported as down
 * at all. Two of those four places read the table now; this test is
 * what stops the table itself from going stale.
 *
 * The expected set is written out here on purpose rather than
 * derived from the same source — a test that reads the thing it is
 * testing asserts nothing.
 */
class LayersTest {

    /** Every key the view model polls or fetches on demand. */
    private val polled = setOf(
        "aircraft", "vessels", "satellites", "quakes", "naturalEvents",
        "overlayPoints", "fires", "volcanoes", "worldWarnings", "seaLevel",
        "outages", "ports", "launches", "gdacs", "weather", "aurora",
        "news", "buoys", "metar", "milAircraft", "airQuality", "stations",
        "conflicts", "airports", "powerPlants", "spaceWeather", "cables",
        "cyclones", "tsunamis", "airportStatus",
        "cameras", "sites", "airspace", "radiation"
    )

    @Test
    fun theTableCoversEveryLayerAndInventsNone() {
        val described = Layers.ALL.map { it.key }.toSet()
        assertEquals(
            "layers polled but not described",
            emptySet<String>(),
            polled - described
        )
        assertEquals(
            "layers described but not polled",
            emptySet<String>(),
            described - polled
        )
    }

    @Test
    fun noKeyIsListedTwice() {
        val keys = Layers.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyLayerHasWordsForTheAreaReport() {
        Layers.ALL.forEach {
            assertTrue(it.key, it.area.isNotBlank())
            assertTrue(it.key, it.area != it.key)
        }
    }

    /**
     * An inventory can be down but never quiet. "No ports anywhere
     * in the world" is not a sentence about today, and the brief
     * would have printed it.
     */
    @Test
    fun theInventoriesCannotGoQuiet() {
        listOf(
            "ports", "airports", "powerPlants", "cables", "stations",
            "satellites", "aircraft", "vessels", "cameras", "sites",
            "airspace", "radiation", "buoys", "metar", "seaLevel",
            "airQuality", "overlayPoints", "spaceWeather"
        ).forEach {
            assertEquals(it, null, Layers.of(it)?.brief)
        }
    }

    /** And an event feed must have the words to say it is quiet. */
    @Test
    fun theEventFeedsCanSayTheyAreQuiet() {
        listOf(
            "quakes", "fires", "volcanoes", "gdacs", "cyclones", "tsunamis",
            "weather", "worldWarnings", "outages", "conflicts", "news",
            "launches", "aurora", "naturalEvents", "milAircraft", "airportStatus"
        ).forEach {
            assertTrue(it, !Layers.of(it)?.brief.isNullOrBlank())
        }
    }

    /** The three fetched in a circle, and nothing else, carry a reach. */
    @Test
    fun onlyTheCircleFetchedLayersCarryAReach() {
        assertEquals(setOf("cameras", "sites", "airspace"), Layers.REACH.keys)
        Layers.REACH.values.forEach { assertTrue(it > 0.0) }
    }

    @Test
    fun anUnknownKeyComesBackAsItself() {
        assertEquals("somethingNew", Layers.area("somethingNew"))
    }
}
