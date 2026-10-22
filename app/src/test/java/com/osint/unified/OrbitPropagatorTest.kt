package com.osint.unified

import com.osint.unified.domain.OrbitPropagator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pins the orbital maths against a position that was actually observed.
 *
 * The elements below are the ISS's, as published by Celestrak, and the
 * reference point is where wheretheiss.at reported the ISS to be at that
 * instant. Both were captured in the same run, so they describe the same
 * moment and the comparison is meaningful.
 */
class OrbitPropagatorTest {

    // Celestrak GROUP=stations, epoch 2026-10-21T01:08:59Z
    private val iss = OrbitPropagator.Elements(
        meanMotion = 15.48967427,
        eccentricity = 0.00067597,
        inclination = 51.6327,
        raan = 37.2623,
        argPerigee = 289.5321,
        meanAnomaly = 70.4938,
        epochMillis = 1792544939000L
    )

    private val observedAt = 1792683655000L
    private val observedLat = -31.292000210869
    private val observedLon = 94.413442113335
    private val observedAltKm = 424.91654059063

    private fun greatCircleKm(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val a1 = Math.toRadians(lat1)
        val a2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a1) * cos(a2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(h))
    }

    @Test
    fun matchesAnObservedIssPosition() {
        val p = OrbitPropagator.propagate(iss, observedAt)
        assertNotNull(p)
        p!!
        val error = greatCircleKm(p.lat, p.lon, observedLat, observedLon)
        // The elements were 38 hours old at this point. A simplified
        // propagator drifts; the bar is that it stays far inside a pixel at
        // the zoom a globe is viewed at, not that it rivals SGP4.
        assertTrue("ground error ${error.toInt()} km", error < 60.0)
        assertTrue(
            "altitude ${p.altitudeKm.toInt()} km",
            kotlin.math.abs(p.altitudeKm - observedAltKm) < 40.0
        )
    }

    @Test
    fun staysOnTheGlobe() {
        // Any instant across a week must produce a plottable point; a NaN or
        // an out-of-range longitude would put markers in the sea off Africa.
        var t = observedAt - 3 * 86_400_000L
        while (t < observedAt + 4 * 86_400_000L) {
            val p = OrbitPropagator.propagate(iss, t)
            assertNotNull("no position at $t", p)
            p!!
            assertTrue("lat ${p.lat}", p.lat in -90.0..90.0)
            assertTrue("lon ${p.lon}", p.lon in -180.0..180.0)
            assertTrue("alt ${p.altitudeKm}", p.altitudeKm in 300.0..500.0)
            t += 3_600_000L
        }
    }

    @Test
    fun theSatelliteActuallyMoves() {
        val a = OrbitPropagator.propagate(iss, observedAt)!!
        val b = OrbitPropagator.propagate(iss, observedAt + 600_000L)!!
        // The ISS covers roughly 7.7 km/s, so ten minutes is thousands of km.
        val moved = greatCircleKm(a.lat, a.lon, b.lat, b.lon)
        assertTrue("moved ${moved.toInt()} km in 10 min", moved > 1000.0)
    }

    @Test
    fun rejectsJunkElements() {
        assertNull(OrbitPropagator.propagate(iss.copy(meanMotion = 0.0), observedAt))
        assertNull(OrbitPropagator.propagate(iss.copy(eccentricity = 1.4), observedAt))
    }

    @Test
    fun siderealTimeAdvancesABitMoreThanADayPerDay() {
        // GMST gains about four minutes a day on solar time; if this drifts
        // the wrong way every longitude is wrong.
        val t0 = 1792683655000L
        val a = OrbitPropagator.gmst(t0)
        val b = OrbitPropagator.gmst(t0 + 86_400_000L)
        val gained = Math.toDegrees(((b - a) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI))
        assertEquals(0.9856, gained, 0.05)
    }
}
