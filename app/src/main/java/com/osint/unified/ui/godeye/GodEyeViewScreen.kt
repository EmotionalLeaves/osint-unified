package com.osint.unified.ui.godeye

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.R
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Tool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GodEyeViewScreen(viewModel: GodEyeViewModel) {
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
            }, "AndroidBridge")
            loadUrl("file:///android_asset/cesium.html")
        }
    }

    LaunchedEffectAircraft(webView, state.aircraft)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::onSearch,
            onOpenSettings = { showSettings = true }
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }

        state.observable?.let { observable ->
            ResultPanel(
                typeLabel = observable.typeLabel,
                tools = state.suggestedTools,
                results = state.results,
                runningToolIds = state.runningToolIds,
                onToolClick = viewModel::onToolClick
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            backendUrl = state.backendUrl,
            tar1090Url = state.tar1090Url,
            onSave = { backend, tar ->
                viewModel.saveSettings(backend, tar)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun LaunchedEffectAircraft(webView: WebView, aircraft: List<Aircraft>) {
    androidx.compose.runtime.LaunchedEffect(aircraft) {
        if (aircraft.isNotEmpty()) {
            val json = Json.encodeToString(aircraft)
            webView.evaluateJavascript("window.updateAircraft($json);", null)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.search_hint)) },
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
}

@Composable
private fun ResultPanel(
    typeLabel: String,
    tools: List<Tool>,
    results: List<ResultRow>,
    runningToolIds: Set<String>,
    onToolClick: (Tool) -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .padding(12.dp)
        ) {
            Text("Detected: $typeLabel", style = MaterialTheme.typography.titleSmall)

            if (tools.isEmpty()) {
                Text(
                    "No tools matched. Try an email, username, domain, IP, phone, or coordinate.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tools.forEach { tool ->
                    val running = tool.id in runningToolIds
                    AssistChip(
                        onClick = { if (!running) onToolClick(tool) },
                        label = { Text(tool.name) },
                        leadingIcon = if (running) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else null
                    )
                }
            }

            if (results.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    results.forEach { row -> ResultCard(row) }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(row: ResultRow) {
    val statusColor = when (row.status) {
        "success" -> Color(0xFF2E7D32)
        "error" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.toolName, style = MaterialTheme.typography.labelLarge)
                Text(
                    "  ·  ${row.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor
                )
            }
            val body = when (row.status) {
                "success" -> row.rawOutput.ifBlank { "(empty output)" }
                "error" -> row.errorMessage ?: "Unknown error"
                else -> "Running…"
            }
            Text(
                text = body.take(600),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    backendUrl: String,
    tar1090Url: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var backend by remember { mutableStateOf(backendUrl) }
    var tar by remember { mutableStateOf(tar1090Url) }

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
                Text(
                    "Leave tar1090 blank to use demo aircraft.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(backend, tar) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
