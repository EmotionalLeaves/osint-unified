package com.osint.unified

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.godeye.GodEyeViewScreen
import com.osint.unified.ui.theme.OsintTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GodEyeViewModel by viewModels {
        GodEyeViewModel.Factory((application as OsintApplication).router)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsintTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GodEyeViewScreen(viewModel)
                }
            }
        }
    }
}
