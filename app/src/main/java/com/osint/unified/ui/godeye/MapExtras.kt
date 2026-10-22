package com.osint.unified.ui.godeye

/** A global natural event (wildfire, storm, volcano, flood…) from NASA EONET. */
data class NaturalEvent(val lat: Double, val lon: Double, val title: String, val category: String)

/** A coordinate pulled out of an investigation result, plotted on the globe. */
data class FindingPin(val lat: Double, val lon: Double, val label: String)
