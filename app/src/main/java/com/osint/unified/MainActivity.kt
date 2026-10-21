package com.osint.unified

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.godeye.GodEyeViewScreen
import com.osint.unified.ui.history.HistoryScreen
import com.osint.unified.ui.history.HistoryViewModel
import com.osint.unified.ui.theme.OsintTheme

class MainActivity : ComponentActivity() {

    private val godEyeViewModel: GodEyeViewModel by viewModels {
        val app = application as OsintApplication
        GodEyeViewModel.Factory(app.router, app.repository, app.aircraftDataSource, app.aisDataSource, app.settings, app.exporter)
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory((application as OsintApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsintTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showHistory by rememberSaveable { mutableStateOf(false) }
                    if (showHistory) {
                        HistoryScreen(historyViewModel, onBack = { showHistory = false })
                    } else {
                        GodEyeViewScreen(godEyeViewModel, onOpenHistory = { showHistory = true })
                    }
                }
            }
        }
    }
}
