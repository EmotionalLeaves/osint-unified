package com.osint.unified.ui.godeye

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.BuildConfig
import com.osint.unified.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val EXAMPLES = listOf("example.com", "8.8.8.8", "40.7128,-74.0060", "+14155550123", "John Smith")

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GodEyeViewScreen(
    viewModel: GodEyeViewModel,
    onOpenHistory: () -> Unit,
    onOpenForensics: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF0B0E11.toInt())
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onAircraftClick(callsign: String) {
                    viewModel.onQueryChange(callsign)
                    viewModel.onSearch()
                }

                @JavascriptInterface
                fun onMapClick(lat: Double, lon: Double) {
                    viewModel.onMapClick(lat, lon)
                }
            }, "AndroidBridge")
            loadUrl("file:///android_asset/map.html")
        }
    }

    LaunchedEffect(state.aircraft) {
        if (state.aircraft.isNotEmpty()) {
            webView.evaluateJavascript("window.updateAircraft(${Json.encodeToString(state.aircraft)});", null)
        }
    }
    LaunchedEffect(state.vessels) {
        if (state.vessels.isNotEmpty()) {
            webView.evaluateJavascript("window.updateVessels(${Json.encodeToString(state.vessels)});", null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OSINT Unified") },
                actions = {
                    IconButton(onClick = onOpenForensics) {
                        Icon(Icons.Filled.Build, contentDescription = "Forensics & decoders")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    IconButton(onClick = viewModel::onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardActions = KeyboardActions(onSearch = { viewModel.onSearch() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                EXAMPLES.forEach { example ->
                    SuggestionChip(
                        onClick = {
                            viewModel.onQueryChange(example)
                            viewModel.onSearch()
                        },
                        label = { Text(example) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                ) {
                    Text(
                        "build ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            backendUrl = state.backendUrl,
            tar1090Url = state.tar1090Url,
            aisUrl = state.aisUrl,
            onSave = { backend, tar, ais ->
                viewModel.saveSettings(backend, tar, ais)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsDialog(
    backendUrl: String,
    tar1090Url: String,
    aisUrl: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var backend by remember { mutableStateOf(backendUrl) }
    var tar by remember { mutableStateOf(tar1090Url) }
    var ais by remember { mutableStateOf(aisUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = backend,
                    onValueChange = { backend = it },
                    singleLine = true,
                    label = { Text("Cloud backend URL") },
                    placeholder = { Text("https://your-api.onrender.com") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tar,
                    onValueChange = { tar = it },
                    singleLine = true,
                    label = { Text("tar1090 feed URL") },
                    placeholder = { Text("http://host:8080/data/aircraft.json") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = ais,
                    onValueChange = { ais = it },
                    singleLine = true,
                    label = { Text("AIS vessel feed URL") },
                    placeholder = { Text("https://host/vessels.json") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Leave feeds blank to use demo aircraft and vessels.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(backend, tar, ais) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
