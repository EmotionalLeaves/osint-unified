package com.osint.unified.ui.godeye

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The default "god view": the 3D globe plus the search entry point. Running a
 * search (or tapping the globe) routes to the Investigation screen.
 */
@SuppressLint("SetJavaScriptEnabled")
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
            loadUrl("file:///android_asset/cesium.html")
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

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::onSearch,
            onOpenForensics = onOpenForensics,
            onOpenHistory = onOpenHistory,
            onOpenSettings = { showSettings = true }
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
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
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenForensics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenForensics) {
                Icon(Icons.Filled.Build, contentDescription = "Forensics & decoders")
            }
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "History")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
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
