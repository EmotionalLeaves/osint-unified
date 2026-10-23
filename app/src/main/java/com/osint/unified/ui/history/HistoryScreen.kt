package com.osint.unified.ui.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.Freshness
import com.osint.unified.data.db.Investigation
import com.osint.unified.ui.common.ResultCard
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cases already run, and what can be done with one.
 *
 * Opening a case used to produce a list of result cards and nothing else:
 * no way to export it, no way to pick the enquiry back up, nowhere to see
 * what had been written about it, and a delete button that destroyed a
 * case on a single tap with no confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onReopen: (String) -> Unit,
    onBack: () -> Unit
) {
    val investigations by viewModel.investigations.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedId == null) "CASES" else "CASE",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.TextHi
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (selectedId == null) onBack() else viewModel.select(null) }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Intel.Base, navigationIconContentColor = Intel.Cyan
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selectedId == null) {
                if (investigations.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No cases yet. Search a target and run a source.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Intel.TextMid
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(investigations, key = { it.id }) { investigation ->
                            InvestigationRow(
                                investigation = investigation,
                                onClick = { viewModel.select(investigation.id) },
                                onDelete = { viewModel.delete(investigation.id) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        CaseHeader(
                            investigation = selected,
                            resultCount = results.size,
                            viewModel = viewModel,
                            onReopen = onReopen
                        )
                    }
                    if (results.isEmpty()) {
                        item {
                            Text(
                                "Nothing was stored for this case.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Intel.TextMid
                            )
                        }
                    } else {
                        items(results, key = { it.id }) { row -> ResultCard(row) }
                    }
                }
            }
        }
    }
}

/**
 * The heading of a stored case: what it was about, what was written, and
 * the three things worth doing with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseHeader(
    investigation: Investigation?,
    resultCount: Int,
    viewModel: HistoryViewModel,
    onReopen: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text(
            investigation?.observableType?.uppercase() ?: "CASE",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.Cyan
        )
        Text(
            investigation?.seedObservable.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = Intel.TextHi,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "$resultCount stored result${if (resultCount == 1) "" else "s"}" +
                (investigation?.createdAt?.let { " · ${Freshness.agoOrUndated(it)}" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = Intel.TextLow,
            modifier = Modifier.padding(top = 4.dp)
        )

        val notes = investigation?.notes.orEmpty()
        if (notes.isNotBlank()) {
            Text(
                "NOTES",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.CyanBright,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            investigation?.seedObservable?.takeIf { it.isNotBlank() }?.let { target ->
                Action("RE-RUN", Intel.Cyan) { onReopen(target) }
            }
            listOf(
                "PDF" to ExportFormat.PDF,
                "TEXT" to ExportFormat.TEXT,
                "JSON" to ExportFormat.JSON,
                "CSV" to ExportFormat.CSV
            ).forEach { (label, format) ->
                Action(label, if (busy) Intel.TextLow else Intel.TextMid) {
                    if (busy) return@Action
                    busy = true
                    scope.launch {
                        val file = viewModel.export(format)
                        busy = false
                        if (file != null) share(context, file, format)
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Intel.Elevated)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Hands the file to whatever the person wants to put it in. */
private fun share(context: android.content.Context, file: java.io.File, format: ExportFormat) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share case").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun InvestigationRow(
    investigation: Investigation,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // One tap used to destroy a case. Deleting an investigation someone
    // spent an evening on should take two.
    var confirming by remember(investigation.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                investigation.seedObservable,
                style = MaterialTheme.typography.titleSmall,
                color = Intel.TextHi,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "${investigation.observableType}  ·  " +
                    dateFormat.format(Date(investigation.createdAt)) +
                    if (investigation.viaPivot != null) "  ·  from ${investigation.viaPivot}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow
            )
            if (investigation.notes.isNotBlank()) {
                Text(
                    investigation.notes.lineSequence().first().take(80),
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.CyanBright,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            if (confirming) "CONFIRM" else "DELETE",
            style = MaterialTheme.typography.labelSmall,
            color = if (confirming) Intel.Danger else Intel.TextLow,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { if (confirming) onDelete() else confirming = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
