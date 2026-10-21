package com.osint.unified.ui.investigation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.data.ExportFormat
import com.osint.unified.domain.GraphBuilder
import com.osint.unified.domain.Observable
import com.osint.unified.ui.common.GraphDialog
import com.osint.unified.ui.common.ResultCard
import com.osint.unified.ui.godeye.GodEyeViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Full-screen investigation surface: the detected observable, a prominent
 * "Run all", the matched tools, results as cards with pivots, and
 * graph/export actions. Opened when a search runs or the globe is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InvestigationScreen(viewModel: GodEyeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showGraph by remember { mutableStateOf(false) }
    var graphJson by remember { mutableStateOf("") }
    val observable = state.observable

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(observable?.typeLabel ?: "Investigation", style = MaterialTheme.typography.titleMedium)
                        observable?.let {
                            Text(it.raw, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to globe")
                    }
                }
            )
        }
    ) { pad ->
        if (observable == null || observable is Observable.Unknown) {
            Box(
                modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Couldn't recognize that input. Try an email, username, domain, IP, phone (+1…), or coordinate (lat, lon).",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }

        val anyRunning = state.runningToolIds.isNotEmpty() || state.workflowRunning

        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            Button(
                onClick = viewModel::onRunAll,
                enabled = !anyRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (anyRunning) "Running…" else "Run all ${state.suggestedTools.size} tools")
            }

            state.workflowName?.let { wf ->
                FilledTonalButton(
                    onClick = viewModel::onRunWorkflow,
                    enabled = !anyRunning,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(if (state.workflowRunning) "Running $wf…" else "Workflow: $wf")
                }
            }

            Text(
                "Tools",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestedTools.forEach { tool ->
                    val running = tool.id in state.runningToolIds
                    AssistChip(
                        onClick = { if (!running) viewModel.onToolClick(tool) },
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

            if (state.results.isNotEmpty()) {
                Text(
                    "Export & analyze",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        graphJson = Json.encodeToString(GraphBuilder.build(observable.raw, state.results))
                        showGraph = true
                    }) { Text("Link graph") }
                    ExportFormat.values().forEach { fmt ->
                        OutlinedButton(onClick = {
                            scope.launch { viewModel.buildExport(fmt)?.let { shareFile(context, it, fmt) } }
                        }) { Text(fmt.name) }
                    }
                }

                Text(
                    "Results",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
                state.results.forEach { row ->
                    ResultCard(row, onPivot = { value ->
                        viewModel.onQueryChange(value)
                        viewModel.onSearch()
                    })
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                Text(
                    "No results yet — tap a tool above, or Run all.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }

    if (showGraph) {
        GraphDialog(graphJson = graphJson, onDismiss = { showGraph = false })
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
