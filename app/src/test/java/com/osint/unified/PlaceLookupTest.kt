package com.osint.unified

import com.osint.unified.ui.godeye.PlaceLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceLookupTest {

    @Test
    fun aPlaceIsNamedOnceAndItsKindIsKept() {
        val p = PlaceLookup.read(
            """{"name":"Union Station","display_name":"Union Station, 225 South Canal Street, Chicago, Illinois, United States","category":"railway","type":"station"}"""
        )
        assertEquals("Union Station", p.name)
        assertEquals("225 South Canal Street, Chicago, Illinois, United States", p.address)
        assertEquals("station", p.kind)
    }

    /** Nominatim's "nothing here" is an answer, not a failure. */
    @Test
    fun openWaterIsAnEmptyPlace() {
        assertTrue(PlaceLookup.read("""{"error":"Unable to geocode"}""").isEmpty)
    }

    @Test
    fun anUnnamedBuildingIsItsAddress() {
        val p = PlaceLookup.read("""{"name":"","display_name":"12, High Street, Oxford","category":"building","type":"yes"}""")
        assertEquals(null, p.name)
        assertEquals("12, High Street, Oxford", p.address)
        assertEquals("building", p.kind)
    }
}
