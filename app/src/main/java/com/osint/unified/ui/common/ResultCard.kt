package com.osint.unified.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.ObservableExtractor
import com.osint.unified.domain.ResultFormatter

/** A single tool result: tool name, status pill, parsed + scannable findings,
 *  clickable links, expand/collapse, and pivot chips. Shared across screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultCard(row: ResultRow, onPivot: ((String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val summary = if (row.status == "success") {
        remember(row.id, row.rawOutput) { ResultFormatter.format(row.toolId, row.rawOutput) }
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(row.status)
            }
            summary?.headline?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            when (row.status) {
                "success" -> {
                    val lines = summary?.lines ?: emptyList()
                    if (lines.isEmpty()) {
                        Text(
                            "(no findings)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        val visible = if (expanded) lines else lines.take(6)
                        Column(modifier = Modifier.padding(top = 6.dp)) {
                            visible.forEach { line -> LineItem(line) { url -> runCatching { uriHandler.openUri(url) } } }
                        }
                        if (lines.size > 6) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Show less" else "Show ${lines.size - 6} more")
                            }
                        }
                    }
                }
                "error" -> Text(
                    row.errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Running…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (onPivot != null && row.status == "success") {
                val pivots = remember(row.id, row.rawOutput) {
                    ObservableExtractor.extract(row.rawOutput, row.input)
                }
                if (pivots.isNotEmpty()) {
                    Text(
                        "Pivot into:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        pivots.forEach { value ->
                            SuggestionChip(onClick = { onPivot(value) }, label = { Text(value, maxLines = 1) })
                        }
                    }
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://\S+""")

@Composable
private fun LineItem(line: String, onOpen: (String) -> Unit) {
    val url = remember(line) { urlRegex.find(line)?.value }
    if (url != null) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 2.dp)
        )
    } else {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 1.dp)
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val container: Color
    val content: Color
    val label: String
    when (status) {
        "success" -> { container = Color(0xFF123D23); content = Color(0xFF7BE0A3); label = "done" }
        "error" -> { container = Color(0xFF4A1512); content = Color(0xFFF2A6A0); label = "error" }
        else -> { container = Color(0xFF3D3414); content = Color(0xFFE6C76A); label = "running" }
    }
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
