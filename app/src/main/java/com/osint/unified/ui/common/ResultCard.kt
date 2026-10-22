package com.osint.unified.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.ObservableExtractor
import com.osint.unified.domain.ResultFormatter
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.ui.theme.Intel
import com.osint.unified.ui.theme.categoryColor

/** A single tool result rendered as a scannable intel card: category accent
 *  rail, status dot, tool name, count badge, parsed label/value + bullet
 *  lines with live links, expand/collapse, and pivot chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultCard(row: ResultRow, onPivot: ((String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val open: (String) -> Unit = { url ->
        val clean = url.trim().trimEnd('.', ',', ')', ']', '}', '"', '\'', ';', '>')
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val accent = remember(row.toolId) {
        ToolRegistry.byId(row.toolId)?.category?.let { categoryColor(it) } ?: Intel.Cyan
    }
    val summary = if (row.status == "success") {
        remember(row.id, row.rawOutput) { ResultFormatter.format(row.toolId, row.rawOutput) }
    } else null
    val lines = summary?.lines ?: emptyList()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(modifier = Modifier.weight(1f).padding(12.dp)) {
            // Header: status dot · tool name · count badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(row.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    row.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Intel.TextHi,
                    modifier = Modifier.weight(1f)
                )
                if (row.status == "success" && lines.isNotEmpty()) {
                    CountBadge(lines.size, accent)
                }
            }
            summary?.headline?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            when (row.status) {
                "success" -> {
                    if (lines.isEmpty()) {
                        Text(
                            "no findings",
                            style = MaterialTheme.typography.bodySmall,
                            color = Intel.TextLow,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        val visible = if (expanded) lines else lines.take(6)
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            visible.forEach { line -> LineItem(line, open) }
                        }
                        if (lines.size > 6) {
                            Text(
                                if (expanded) "— show less" else "+ ${lines.size - 6} more",
                                style = MaterialTheme.typography.labelMedium,
                                color = Intel.Cyan,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable { expanded = !expanded }
                            )
                        }
                    }
                }
                "error" -> Text(
                    row.errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.Danger,
                    modifier = Modifier.padding(top = 8.dp)
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Intel.Cyan
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("querying…", style = MaterialTheme.typography.bodySmall, color = Intel.TextMid)
                }
            }

            if (onPivot != null && row.status == "success") {
                val pivots = remember(row.id, row.rawOutput) {
                    ObservableExtractor.extract(row.rawOutput, row.input)
                }
                if (pivots.isNotEmpty()) {
                    Text(
                        "PIVOT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pivots.forEach { value -> PivotChip(value) { onPivot(value) } }
                    }
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://\S+""")
private val labelRegex = Regex("""^([A-Za-z][\w ./+-]{0,22}):\s*(.+)$""")

@Composable
private fun LineItem(raw: String, onOpen: (String) -> Unit) {
    val line = raw.trimEnd()
    val url = remember(line) { urlRegex.find(line)?.value }
    val bullet = line.startsWith("•") || line.startsWith("- ")
    val label = remember(line) { if (!bullet) labelRegex.find(line.trim()) else null }

    when {
        // A bare URL (or line ending in one): make it a live link.
        url != null && (line.startsWith("http") || label == null && !bullet) -> {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = Intel.Cyan,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 2.dp)
            )
        }
        // "Label: value" pair.
        label != null -> {
            val key = label.groupValues[1]
            val value = label.groupValues[2]
            val valUrl = urlRegex.find(value)?.value
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    key.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.width(96.dp)
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valUrl != null) Intel.Cyan else Intel.TextHi,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (valUrl != null) Modifier.clickable { onOpen(valUrl) } else Modifier)
                )
            }
        }
        // Bullet item.
        bullet -> {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("›", color = Intel.Cyan, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    line.removePrefix("•").removePrefix("- ").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextMid,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        else -> Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = Intel.TextMid,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 1.dp)
        )
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "success" -> Intel.Success
        "error" -> Intel.Danger
        else -> Intel.Amber
    }
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
}

@Composable
private fun CountBadge(count: Int, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, accent, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("$count", style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun PivotChip(value: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Intel.Elevated)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = Intel.CyanBright,
            maxLines = 1
        )
    }
}
