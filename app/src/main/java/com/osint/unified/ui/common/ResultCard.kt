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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
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
    var showRaw by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> LinkOpener.open(context, url) }
    val accent = remember(row.toolId) {
        ToolRegistry.byId(row.toolId)?.category?.let { categoryColor(it) } ?: Intel.Cyan
    }
    // A source that looked and found nothing, and one that could not be
    // reached, are both finished: they have output, and it is the output
    // that says which of the two happened. Treating them as unfinished
    // left a card with no body, no COPY, no RAW and the same amber dot as
    // a source still running — so the carefully worded "no OFAC entry
    // matched this name" was never shown to anybody.
    val finished = row.status in FINISHED_WITH_OUTPUT
    val summary = if (finished) {
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
                if (finished && lines.isNotEmpty()) {
                    CountBadge(lines.size, accent)
                }
            }
            // A finding you cannot get out of the app is a finding you
            // retype by hand into whatever you are actually writing. The
            // parsed lines are not selectable — every one of them is a tap
            // target for a link or a pivot — so copying is a button, and
            // the verbatim output is one tap away and selectable.
            if (finished || row.status == "error") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        if (copied) "COPIED" else "COPY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) Intel.Success else Intel.TextLow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(plainText(row, summary)))
                                copied = true
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                    Text(
                        if (showRaw) "HIDE RAW" else "RAW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { showRaw = !showRaw }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
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

            when {
                finished -> {
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
                // A refresh that failed kept the previous answer and
                // wrote why on the row. Nothing showed it: every
                // renderer and every export format printed
                // errorMessage only when the status was "error", and
                // this row's status stays "success" because the data
                // in it is still the data that was found. So the card
                // was a green "found" with a timestamp, and the fact
                // that the latest attempt had failed was recorded and
                // then dropped.
                row.status == "success" && !row.errorMessage.isNullOrBlank() -> Text(
                    "⚠ ${row.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Amber,
                    modifier = Modifier.padding(top = 8.dp)
                )
                row.status == "error" -> Text(
                    row.errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.Danger,
                    modifier = Modifier.padding(top = 8.dp)
                )
                row.status == "cancelled" || row.status == "interrupted" -> Text(
                    if (row.status == "cancelled") "Stopped before it finished."
                    else "Interrupted — the app was closed while this was running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextLow,
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

            if (showRaw) {
                SelectionContainer {
                    Text(
                        // Capped, because some of these answers are a
                        // megabyte of JSON and a Text that long does not
                        // lay out.
                        row.rawOutput.take(RAW_PREVIEW_CHARS) +
                            if (row.rawOutput.length > RAW_PREVIEW_CHARS)
                                "\n… ${row.rawOutput.length - RAW_PREVIEW_CHARS} more characters; " +
                                    "COPY takes the whole thing."
                            else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextMid,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Intel.Elevated)
                            .padding(8.dp)
                    )
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

/** Enough to read; short enough that a Text can lay it out. */
private const val RAW_PREVIEW_CHARS = 4_000

/** Statuses that mean the source answered, whatever the answer was. */
private val FINISHED_WITH_OUTPUT = setOf(
    "success", Findings.STATUS_ABSENT, Findings.STATUS_UNREACHABLE
)

/**
 * What the clipboard gets: the source, what it was asked, and its answer.
 *
 * A pasted finding with no provenance is worth very little an hour later,
 * so the heading travels with it.
 */
private fun plainText(row: ResultRow, summary: ResultFormatter.Summary?): String = buildString {
    append(row.toolName).append(" — ").append(row.input).append('\n')
    summary?.headline?.let { append(it).append('\n') }
    if (row.status == "error") {
        append(row.errorMessage ?: "Unknown error").append('\n')
    } else {
        append(row.rawOutput)
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
                    // A floor rather than a fixed width: at a large
                    // system font size a 96dp column holds about five
                    // characters, and every key in the card was
                    // wrapping or being cut off.
                    modifier = Modifier.widthIn(min = 96.dp)
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
        // Three finished states that are not success, and none of them is
        // "still working". They shared the running colour, so a dossier of
        // absences looked like a dossier still loading.
        Findings.STATUS_ABSENT -> Intel.TextLow
        Findings.STATUS_UNREACHABLE -> Intel.Amber
        "cancelled", "interrupted" -> Intel.TextLow
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
