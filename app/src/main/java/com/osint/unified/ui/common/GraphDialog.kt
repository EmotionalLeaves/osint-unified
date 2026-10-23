package com.osint.unified.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.produceState
import com.osint.unified.domain.ForceLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.osint.unified.domain.GraphBuilder
import com.osint.unified.ui.theme.Intel

/**
 * The link graph, drawn natively.
 *
 * This was a WebView running D3 from a CDN, with node labels — text taken
 * verbatim from third-party tool output — interpolated into a `<script>`
 * block. A label containing `</script>` could run arbitrary code with the
 * whole case graph in scope. There is no WebView here, no JavaScript and no
 * outbound request, so that is not a thing that can happen.
 */
@Composable
fun GraphDialog(graph: GraphBuilder.Graph, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Intel.Base) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LINK GRAPH",
                            style = MaterialTheme.typography.titleSmall,
                            color = Intel.TextHi
                        )
                        Text(
                            "${graph.nodes.size} nodes · ${graph.links.size} links · " +
                                "pinch to zoom, drag to pan",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextMid
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Intel.Cyan)
                    }
                }
                GraphCanvas(graph, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun GraphCanvas(graph: GraphBuilder.Graph, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // The layout is deterministic and computed once per graph, so panning
    // and zooming never reshuffle the picture under the finger.
    var canvasSize by remember { mutableStateOf(Offset(1080f, 1600f)) }
    // Computed off the main thread. It is a force-directed layout —
    // three hundred iterations over every pair of nodes — so on a case
    // with a few hundred identifiers it froze the interface for as long
    // as it took, during composition, where nothing can interrupt it.
    val layout by produceState(
        initialValue = ForceLayout.Layout(emptyList(), emptyList()),
        graph, canvasSize
    ) {
        value = withContext(Dispatchers.Default) {
            ForceLayout.compute(graph, canvasSize.x, canvasSize.y)
        }
    }

    val labelStyle = TextStyle(fontSize = 11.sp, color = Intel.TextHi)

    Box(
        modifier
            .background(Intel.Base)
            .pointerInput(Unit) {
                detectTransformGestures { _, drag, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.3f, 4f)
                    pan += drag
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (size.width > 0 && kotlin.math.abs(canvasSize.x - size.width) > 1f) {
                canvasSize = Offset(size.width, size.height)
            }
            fun place(x: Float, y: Float) = Offset(
                (x - size.width / 2) * scale + size.width / 2 + pan.x,
                (y - size.height / 2) * scale + size.height / 2 + pan.y
            )

            layout.edges.forEach { e ->
                drawLine(
                    color = Intel.Hairline,
                    start = place(e.fromX, e.fromY),
                    end = place(e.toX, e.toY),
                    strokeWidth = 1f
                )
            }
            layout.nodes.forEach { node ->
                val centre = place(node.x, node.y)
                val radius = when (node.group) {
                    0 -> 11f
                    1 -> 7f
                    else -> 4.5f
                } * scale
                val colour = when (node.group) {
                    0 -> Intel.Amber
                    1 -> Intel.Cyan
                    else -> Intel.Success
                }
                drawCircle(colour, radius, centre)
                drawCircle(colour.copy(alpha = 0.35f), radius + 3f, centre, style = Stroke(1f))

                // Labels only where they can be read: at a distance every
                // graph becomes a grey smear of overlapping text.
                if (scale >= 0.8f || node.group < 2) {
                    drawLabel(measurer, node.label, centre, radius, labelStyle, density.density)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    label: String,
    centre: Offset,
    radius: Float,
    style: TextStyle,
    densityScale: Float
) {
    val text = label.take(36)
    val measured = measurer.measure(text, style)
    val x = centre.x + radius + 4f * densityScale
    val y = centre.y - measured.size.height / 2f
    if (x > size.width || y < -40f || y > size.height + 40f) return
    drawText(measured, topLeft = Offset(x, y))
}
