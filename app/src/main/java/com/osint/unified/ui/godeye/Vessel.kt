package com.osint.unified.ui.godeye

import kotlinx.serialization.Serializable

/** A single maritime vessel position for the God's Eye View. */
@Serializable
data class Vessel(
    val mmsi: String,
    val name: String = "",
    val lat: Double,
    val lon: Double,
    val course: Double = 0.0,
    val speed: Double = 0.0
)
