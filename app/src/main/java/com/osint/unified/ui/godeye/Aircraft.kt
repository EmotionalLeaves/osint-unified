package com.osint.unified.ui.godeye

import kotlinx.serialization.Serializable

/** A single aircraft position, as consumed by the CesiumJS globe. */
@Serializable
data class Aircraft(
    val icao: String,
    val callsign: String = "",
    val lat: Double,
    val lon: Double,
    val altitude: Double = 0.0,
    val track: Double = 0.0,
    val speed: Double = 0.0
)
