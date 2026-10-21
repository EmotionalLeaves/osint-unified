package com.osint.unified.ui.godeye

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.R
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.GraphBuilder
import com.osint.unified.domain.Tool
import com.osint.unified.ui.common.GraphDialog
import com.osint.unified.ui.common.ResultCard
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GodEyeViewScreen(viewModel: GodEyeViewModel, onOpenHistory: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showGraph by remember { mutableStateOf(false) }
    var graphJson by remember { mutableStateOf("") }

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
            onOpenHistory = onOpenHistory,
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
                workflowName = state.workflowName,
                workflowRunning = state.workflowRunning,
                onRunWorkflow = viewModel::onRunWorkflow,
                onToolClick = viewModel::onToolClick,
                onExport = { format ->
                    scope.launch {
                        val file = viewModel.buildExport(format)
                        if (file != null) shareFile(context, file, format)
                    }
                },
                onShowGraph = {
                    graphJson = Json.encodeToString(GraphBuilder.build(observable.raw, state.results))
                    showGraph = true
                }
            )
        }
    }

    if (showGraph) {
        GraphDialog(graphJson = graphJson, onDismiss = { showGraph = false })
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

private fun shareFile(context: Context, file: File, format: ExportFormat) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export results"))
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenHistory: () -> Unit,
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
private fun ResultPanel(
    typeLabel: String,
    tools: List<Tool>,
    results: List<ResultRow>,
    runningToolIds: Set<String>,
    workflowName: String?,
    workflowRunning: Boolean,
    onRunWorkflow: () -> Unit,
    onToolClick: (Tool) -> Unit,
    onExport: (ExportFormat) -> Unit,
    onShowGraph: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(12.dp)
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
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
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

            if (workflowName != null) {
                FilledTonalButton(
                    onClick = onRunWorkflow,
                    enabled = !workflowRunning,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    if (workflowRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (workflowRunning) "Running $workflowName…" else "Run $workflowName")
                }
            }

            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Export:", style = MaterialTheme.typography.labelMedium)
                    ExportFormat.values().forEach { format ->
                        TextButton(onClick = { onExport(format) }) { Text(format.name) }
                    }
                    TextButton(onClick = onShowGraph) { Text("Graph") }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    results.forEach { row -> ResultCard(row) }
                }
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
