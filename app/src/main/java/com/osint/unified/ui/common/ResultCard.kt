package com.osint.unified.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.ResultFormatter

/** Renders a single tool result: tool name, status, parsed summary. Shared by
 *  the live results panel and the history detail view. */
@Composable
fun ResultCard(row: ResultRow) {
    val statusColor = when (row.status) {
        "success" -> Color(0xFF2E7D32)
        "error" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val summary = if (row.status == "success") {
        remember(row.id, row.rawOutput) { ResultFormatter.format(row.toolId, row.rawOutput) }
    } else null

    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.toolName, style = MaterialTheme.typography.labelLarge)
                Text("  ·  ${row.status}", style = MaterialTheme.typography.labelMedium, color = statusColor)
                summary?.let {
                    Text("  ·  ${it.headline}", style = MaterialTheme.typography.labelMedium)
                }
            }
            val body = when (row.status) {
                "success" -> summary?.lines?.take(15)?.joinToString("\n")?.ifBlank { "(no output)" } ?: "(no output)"
                "error" -> row.errorMessage ?: "Unknown error"
                else -> "Running…"
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
