package com.osint.unified.ui.investigation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.Findings
import com.osint.unified.domain.IntelSummary
import com.osint.unified.domain.Observable
import com.osint.unified.domain.Tool
import com.osint.unified.domain.ToolCategory
import com.osint.unified.ui.common.GraphDialog
import com.osint.unified.ui.common.ResultCard
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.theme.Intel
import com.osint.unified.ui.theme.categoryColor
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
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = { Text("DOSSIER", style = MaterialTheme.typography.titleSmall, color = Intel.TextHi) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to globe")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Intel.Base,
                    navigationIconContentColor = Intel.Cyan
                )
            )
        }
    ) { pad ->
        if (observable == null || observable is Observable.Unknown) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Enter a name, email, username, domain, IP, phone (+1…), place, coordinate, or image URL to open a dossier.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Intel.TextMid
                )
            }
            return@Scaffold
        }

        val anyRunning = state.runningToolIds.isNotEmpty() || state.workflowRunning ||
            state.enrichmentRunning
        val done = state.results.count { it.status == "success" || it.status == "error" }
        val found = state.results.count { it.status == "success" }
        val facts = remember(state.results) { IntelSummary.synthesize(state.results) }
        val pivots = remember(state.results) {
            EntityExtractor.extract(state.results, observable.raw)
        }
        // Identifiers the second pass has already run tools against.
        val alreadyChased = remember(state.results) {
            val seed = observable.raw.trim().lowercase()
            state.results.map { it.input }
                .filter { it.trim().lowercase() != seed }
                .toSet()
        }

        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            // ---- Dossier header ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Intel.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, Intel.Hairline, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Intel.Cyan, RoundedCornerShape(50)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        observable.typeLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Cyan
                    )
                }
                SelectionContainer {
                    Text(
                        observable.raw,
                        style = MaterialTheme.typography.titleLarge,
                        color = Intel.TextHi,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    when {
                        state.enrichmentRunning ->
                            "second pass · chasing ${state.enriching.size} discovered " +
                                "identifier${if (state.enriching.size == 1) "" else "s"} · $found hits"
                        anyRunning -> "scanning · $done/${state.suggestedTools.size} sources · $found hits"
                        done > 0 -> "$done sources queried · $found returned intel"
                        else -> "ready — run all, or tap a source below"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (state.enriching.isNotEmpty()) {
                    Text(
                        state.enriching.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Cyan,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (anyRunning) {
                    LinearProgressIndicator(
                        color = Intel.Cyan,
                        trackColor = Intel.Hairline,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
                if (facts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        facts.forEach { FactChip(it) }
                    }
                }
            }

            // ---- Actions ----
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                ConsoleButton("▶ RUN ALL ${state.suggestedTools.size}", primary = true, enabled = !anyRunning) {
                    viewModel.onRunAll()
                }
                state.workflowName?.let { wf ->
                    ConsoleButton(if (state.workflowRunning) "$wf…" else "CHAIN: $wf", enabled = !anyRunning) {
                        viewModel.onRunWorkflow()
                    }
                }
                if (state.results.isNotEmpty()) {
                    ConsoleButton("GRAPH") {
                        graphJson = Json.encodeToString(GraphBuilder.build(observable.raw, state.results))
                        showGraph = true
                    }
                    ExportFormat.values().forEach { fmt ->
                        ConsoleButton(fmt.name) {
                            scope.launch { viewModel.buildExport(fmt)?.let { shareFile(context, it, fmt) } }
                        }
                    }
                }
            }

            // ---- Pivots: identifiers found in the results, one tap to chase ----
            if (pivots.isNotEmpty()) {
                SectionHeader("PIVOTS — ${pivots.size} identifiers found", Intel.CyanBright)
                Text(
                    if (alreadyChased.isEmpty())
                        "Tap any to start a fresh investigation on it."
                    else
                        "✓ marks the ones the second pass already looked up — " +
                            "their results are under FOLLOWED UP below.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                pivots.groupBy { it.kind }.forEach { (kind, items) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(62.dp).padding(top = 10.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items.forEach { p ->
                                PivotChip(p, chased = p.value in alreadyChased) {
                                    viewModel.onQueryChange(p.value)
                                    viewModel.onSearch()
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // ---- Sources by category ----
            state.suggestedTools.groupBy { it.category }.forEach { (category, tools) ->
                SectionHeader(category.name, categoryColor(category))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tools.forEach { tool -> SourceChip(tool, tool.id in state.runningToolIds, viewModel) }
                }
            }

            // ---- Findings grouped by category ----
            Text(
                "INTEL",
                style = MaterialTheme.typography.labelMedium,
                color = Intel.TextHi,
                modifier = Modifier.padding(top = 20.dp, bottom = 2.dp)
            )
            if (state.results.isEmpty()) {
                Text(
                    if (anyRunning) "gathering…" else "no intel yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                // A source that found nothing is worth knowing about, but
                // it should not sit between you and the ones that did. Most
                // of a dossier is misses — they collapse to a single line.
                val (hits, misses) = state.results.partition { Findings.isHit(it) }

                // What you asked about, and what the app went on to look up
                // because the first pass named it, are two different kinds of
                // claim; the second says where it came from.
                val seed = observable.raw.trim().lowercase()
                val (direct, derived) = hits.partition { it.input.trim().lowercase() == seed }

                if (direct.isNotEmpty()) {
                    val byCategory = direct.groupBy { row ->
                        com.osint.unified.domain.ToolRegistry.byId(row.toolId)?.category
                            ?: ToolCategory.NETINT
                    }
                    byCategory.forEach { (category, rows) ->
                        SectionHeader(category.name, categoryColor(category))
                        rows.forEach { row ->
                            ResultCard(row, onPivot = { value ->
                                viewModel.onQueryChange(value)
                                viewModel.onSearch()
                            })
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                if (derived.isNotEmpty()) {
                    SectionHeader(
                        "FOLLOWED UP — ${derived.size} results on identifiers the first pass found",
                        Intel.CyanBright
                    )
                    derived.groupBy { it.input }.forEach { (via, rows) ->
                        Text(
                            "◂ $via",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.Cyan,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        rows.forEach { row ->
                            ResultCard(row, onPivot = { value ->
                                viewModel.onQueryChange(value)
                                viewModel.onSearch()
                            })
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                if (hits.isEmpty() && !anyRunning) {
                    Text(
                        "No source returned anything on this target.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Intel.TextMid,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (misses.isNotEmpty()) {
                    var showMisses by remember { mutableStateOf(false) }
                    val failed = misses.count { it.status != "success" }
                    SectionHeader(
                        "NOTHING FOUND — ${misses.size} sources" +
                            if (failed > 0) " ($failed errored)" else "",
                        Intel.TextLow
                    )
                    Text(
                        misses.joinToString(", ") { row ->
                            com.osint.unified.domain.ToolRegistry.byId(row.toolId)?.name ?: row.toolId
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clickable { showMisses = !showMisses }
                    )
                    Text(
                        if (showMisses) "hide details" else "show details",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Cyan,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clickable { showMisses = !showMisses }
                    )
                    if (showMisses) {
                        misses.forEach { row ->
                            ResultCard(row, onPivot = null)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGraph) {
        GraphDialog(graphJson = graphJson, onDismiss = { showGraph = false })
    }
}

@Composable
private fun PivotChip(
    pivot: EntityExtractor.Pivot,
    chased: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Intel.Elevated, RoundedCornerShape(7.dp))
            .border(
                1.dp,
                if (chased) Intel.Cyan else Intel.Hairline,
                RoundedCornerShape(7.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        // The second pass has already looked this one up, and its results
        // are below under FOLLOWED UP. Tapping still works — it opens a
        // dossier of its own — but it is no longer unexplored ground, and
        // the chip should not invite the same work twice.
        if (chased) {
            Text(
                "✓ ",
                style = MaterialTheme.typography.labelMedium,
                color = Intel.Cyan
            )
        }
        Text(
            pivot.value,
            style = MaterialTheme.typography.labelMedium,
            color = if (chased) Intel.Cyan else Intel.CyanBright,
            fontFamily = FontFamily.Monospace
        )
        if (pivot.count > 1) {
            Spacer(Modifier.width(5.dp))
            Text(
                "×${pivot.count}",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow
            )
        }
    }
}

@Composable
private fun FactChip(fact: IntelSummary.Fact) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Intel.Elevated, RoundedCornerShape(6.dp))
            .border(1.dp, Intel.Hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(fact.label, style = MaterialTheme.typography.labelSmall, color = Intel.TextLow)
        Spacer(Modifier.width(6.dp))
        Text(fact.value, style = MaterialTheme.typography.labelMedium, color = Intel.CyanBright, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(name: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    ) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SourceChip(tool: Tool, running: Boolean, viewModel: GodEyeViewModel) {
    AssistChip(
        onClick = { if (!running) viewModel.onToolClick(tool) },
        label = { Text(tool.name, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = if (running) {
            {
                CircularProgressIndicator(
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    strokeWidth = 2.dp,
                    color = Intel.Cyan
                )
            }
        } else null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Intel.Surface,
            labelColor = Intel.TextMid
        )
    )
}

@Composable
private fun ConsoleButton(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val border = if (primary) Intel.Cyan else Intel.Hairline
    val bg = if (primary) Intel.Cyan else Color.Transparent
    val fg = if (primary) Intel.Base else Intel.CyanBright
    Box(
        modifier = Modifier
            .background(if (enabled) bg else Intel.Elevated, RoundedCornerShape(7.dp))
            .border(1.dp, if (enabled) border else Intel.Hairline, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) fg else Intel.TextLow
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
    context.startActivity(Intent.createChooser(intent, "Export dossier"))
}
