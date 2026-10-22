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
import com.osint.unified.ui.forensics.ForensicsScreen
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.godeye.GodEyeViewScreen
import com.osint.unified.ui.history.HistoryScreen
import com.osint.unified.ui.history.HistoryViewModel
import com.osint.unified.ui.investigation.InvestigationScreen
import com.osint.unified.ui.theme.OsintTheme

class MainActivity : ComponentActivity() {

    private val godEyeViewModel: GodEyeViewModel by viewModels {
        val app = application as OsintApplication
        GodEyeViewModel.Factory(app.router, app.repository, app.aircraftDataSource, app.aisDataSource, app.earthquakeDataSource, app.naturalEventsDataSource, app.overlayDataSource, app.weatherDataSource, app.firesDataSource, app.satellitesDataSource, app.newsDataSource, app.availableLocalTools, app.settings, app.exporter)
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
                        else -> GodEyeViewScreen(
                            godEyeViewModel,
                            onOpenHistory = { screen = "history" },
                            onOpenForensics = { screen = "forensics" }
                        )
                    }
                }
            }
        }
    }
}
