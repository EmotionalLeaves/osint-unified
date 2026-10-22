package com.osint.unified

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.ui.diagnostics.DiagnosticsScreen
import com.osint.unified.ui.diagnostics.FeedProbe
import com.osint.unified.ui.forensics.ForensicsScreen
import com.osint.unified.ui.framework.FrameworkScreen
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.godeye.GodEyeViewScreen
import com.osint.unified.ui.history.HistoryScreen
import com.osint.unified.ui.history.HistoryViewModel
import com.osint.unified.ui.investigation.InvestigationScreen
import com.osint.unified.ui.theme.OsintTheme

class MainActivity : ComponentActivity() {

    private val godEyeViewModel: GodEyeViewModel by viewModels {
        val app = application as OsintApplication
        GodEyeViewModel.Factory(app.router, app.repository, app.aircraftDataSource, app.aisDataSource, app.earthquakeDataSource, app.naturalEventsDataSource, app.overlayDataSource, app.weatherDataSource, app.firesDataSource, app.satellitesDataSource, app.newsDataSource, app.gdacsDataSource, app.volcanoDataSource, app.auroraDataSource, app.buoyDataSource, app.metarDataSource, app.milAircraftDataSource, app.airQualityDataSource, app.seismicStationDataSource, app.conflictDataSource, app.airportDataSource, app.powerPlantDataSource, app.cctvDataSource, app.airspaceDataSource, app.spaceWeatherDataSource, app.laddAircraftDataSource, app.cableLandingDataSource, app.availableLocalTools, app.settings, app.exporter)
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory((application as OsintApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsintTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf("godeye") }
                    val godState by godEyeViewModel.state.collectAsStateWithLifecycle()

                    // A new search (from the bar or a globe tap) opens the investigation.
                    LaunchedEffect(godState.navSignal) {
                        if (godState.navSignal > 0L) screen = "investigation"
                    }

                    // System back returns to the globe from any sub-screen.
                    BackHandler(enabled = screen != "godeye") { screen = "godeye" }

                    when (screen) {
                        "investigation" -> InvestigationScreen(godEyeViewModel, onBack = { screen = "godeye" })
                        "history" -> HistoryScreen(historyViewModel, onBack = { screen = "godeye" })
                        "forensics" -> ForensicsScreen(
                            (application as OsintApplication).exifReader,
                            onBack = { screen = "godeye" }
                        )
                        "diagnostics" -> {
                            val app = application as OsintApplication
                            DiagnosticsScreen(
                                app.executorFactory,
                                feeds = listOf(
                                    FeedProbe("Aircraft (OpenSky/adsb.fi)") { app.aircraftDataSource.fetch().size },
                                    FeedProbe("Military ADS-B") { app.milAircraftDataSource.fetch().size },
                                    FeedProbe("Vessels (AIS)") { app.aisDataSource.fetch().size },
                                    FeedProbe("Earthquakes (USGS)") { app.earthquakeDataSource.fetch().size },
                                    FeedProbe("Natural events (EONET)") { app.naturalEventsDataSource.fetch().size },
                                    FeedProbe("Wildfires (EONET)") { app.firesDataSource.fetch().size },
                                    FeedProbe("Volcanoes (EONET)") { app.volcanoDataSource.fetch().size },
                                    FeedProbe("Disasters (GDACS)") { app.gdacsDataSource.fetch().size },
                                    FeedProbe("Weather alerts (NWS)") { app.weatherDataSource.fetch().size },
                                    FeedProbe("Aurora (SWPC)") { app.auroraDataSource.fetch().size },
                                    FeedProbe("News (GDELT)") { app.newsDataSource.fetch().size },
                                    FeedProbe("Ocean buoys (NDBC)") { app.buoyDataSource.fetch().size },
                                    FeedProbe("METAR (AWC)") { app.metarDataSource.fetch().size },
                                    FeedProbe("Satellites (wheretheiss)") { app.satellitesDataSource.fetch().size },
                                    FeedProbe("Air quality (Open-Meteo)") { app.airQualityDataSource.fetch().size },
                                    FeedProbe("Seismic stations (IRIS)") { app.seismicStationDataSource.fetch().size },
                                    FeedProbe("Conflicts (Wikidata)") { app.conflictDataSource.fetch().size },
                                    FeedProbe("Airports (bundled)") { app.airportDataSource.fetch().size },
                                    FeedProbe("Power plants (bundled)") { app.powerPlantDataSource.fetch().size },
                                    FeedProbe("Traffic cameras (Caltrans)") { app.cctvDataSource.fetch().size },
                                    FeedProbe("Restricted airspace (FAA)") { app.airspaceDataSource.fetch().size },
                                    FeedProbe("Space weather (SWPC)") { app.spaceWeatherDataSource.fetch().alerts.size + 1 },
                                    FeedProbe("Blocked aircraft (LADD)") { app.laddAircraftDataSource.fetch().size },
                                    FeedProbe("Cable landings") { app.cableLandingDataSource.fetch().size },
                                    FeedProbe("Custom overlay") { app.overlayDataSource.fetch().size }
                                ),
                                onBack = { screen = "godeye" }
                            )
                        }
                        "framework" -> FrameworkScreen(
                            initialTarget = godState.observable?.raw.orEmpty(),
                            onBack = { screen = "godeye" }
                        )
                        else -> GodEyeViewScreen(
                            godEyeViewModel,
                            onOpenHistory = { screen = "history" },
                            onOpenForensics = { screen = "forensics" },
                            onOpenDiagnostics = { screen = "diagnostics" },
                            onOpenFramework = { screen = "framework" }
                        )
                    }
                }
            }
        }
    }
}
