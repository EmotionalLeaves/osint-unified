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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.data.ExportFormat
import com.osint.unified.domain.GraphBuilder
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ToolCategory
import com.osint.unified.ui.common.ResultCard
import com.osint.unified.ui.common.GraphDialog
import com.osint.unified.ui.godeye.GodEyeViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
                title = { Text("Investigation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to globe")
                    }
                }
            )
        }
    ) { pad ->
        if (observable == null || observable is Observable.Unknown) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Enter a name, email, username, domain, IP, phone (+1…), place, coordinate, or image URL.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }

        val anyRunning = state.runningToolIds.isNotEmpty() || state.workflowRunning
        val done = state.results.count { it.status == "success" || it.status == "error" }
        val found = state.results.count { it.status == "success" }

        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            // ---- Header ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        observable.typeLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    SelectionContainer {
                        Text(
                            observable.raw,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        when {
                            anyRunning -> "Investigating… $done of ${state.suggestedTools.size} tools · $found with findings"
                            done > 0 -> "$done tools run · $found returned findings"
                            else -> "Tap Run all, or a tool below"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (anyRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }

            // ---- Actions ----
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Button(onClick = viewModel::onRunAll, enabled = !anyRunning) {
                    Text("Run all ${state.suggestedTools.size}")
                }
                state.workflowName?.let { wf ->
                    OutlinedButton(onClick = viewModel::onRunWorkflow, enabled = !anyRunning) {
                        Text(if (state.workflowRunning) "$wf…" else "Workflow: $wf")
                    }
                }
                if (state.results.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        graphJson = Json.encodeToString(GraphBuilder.build(observable.raw, state.results))
                        showGraph = true
                    }) { Text("Graph") }
                    ExportFormat.values().forEach { fmt ->
                        OutlinedButton(onClick = {
                            scope.launch { viewModel.buildExport(fmt)?.let { shareFile(context, it, fmt) } }
                        }) { Text(fmt.name) }
                    }
                }
            }

            // ---- Tools grouped by category ----
            val grouped = state.suggestedTools.groupBy { it.category }
            grouped.forEach { (category, tools) ->
                Text(
                    category.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = categoryColor(category),
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tools.forEach { tool ->
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
            }

            // ---- Results ----
            Text(
                "Findings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
            )
            if (state.results.isEmpty()) {
                Text(
                    if (anyRunning) "Gathering…" else "No results yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.results.forEach { row ->
                    ResultCard(row, onPivot = { value ->
                        viewModel.onQueryChange(value)
                        viewModel.onSearch()
                    })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    if (showGraph) {
        GraphDialog(graphJson = graphJson, onDismiss = { showGraph = false })
    }
}

private fun categoryColor(category: ToolCategory): Color = when (category) {
    ToolCategory.SOCMINT -> Color(0xFF7F77DD)
    ToolCategory.NETINT -> Color(0xFF5CC8FF)
    ToolCategory.GEOINT -> Color(0xFF8BD0B0)
    ToolCategory.FININT -> Color(0xFFE6C76A)
    ToolCategory.FORENSICS -> Color(0xFFF0997B)
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
