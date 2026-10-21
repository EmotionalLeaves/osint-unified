package com.osint.unified.ui.common

import android.annotation.SuppressLint
import android.webkit.WebView
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-screen dialog rendering a D3 force-directed graph of the results. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GraphDialog(graphJson: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Link graph", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(0.85f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            loadDataWithBaseURL("https://localhost/", graphHtml(graphJson), "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun graphHtml(graphJson: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<script src="https://cdnjs.cloudflare.com/ajax/libs/d3/7.8.5/d3.min.js"></script>
<style>
html,body{margin:0;height:100%;background:#0b0e11;overflow:hidden;}
svg{width:100vw;height:100vh;}
text{font:10px sans-serif;fill:#dddddd;}
line{stroke:#555555;}
</style></head><body><svg id="g"></svg><script>
const data = $graphJson;
const palette = ["#4FC3F7","#FFB74D","#81C784"];
const color = d => palette[d.group] || "#aaaaaa";
const svg = d3.select("#g");
const w = window.innerWidth, h = window.innerHeight;
const sim = d3.forceSimulation(data.nodes)
  .force("link", d3.forceLink(data.links).id(d=>d.id).distance(60))
  .force("charge", d3.forceManyBody().strength(-120))
  .force("center", d3.forceCenter(w/2, h/2));
const link = svg.append("g").selectAll("line").data(data.links).enter().append("line").attr("stroke-width",1);
const node = svg.append("g").selectAll("circle").data(data.nodes).enter().append("circle")
  .attr("r", d=> d.group==0?9:(d.group==1?6:4)).attr("fill", color)
  .call(d3.drag().on("start",ds).on("drag",dm).on("end",de));
const label = svg.append("g").selectAll("text").data(data.nodes).enter().append("text").text(d=>d.label).attr("dx",8).attr("dy",3);
sim.on("tick",()=>{
  link.attr("x1",d=>d.source.x).attr("y1",d=>d.source.y).attr("x2",d=>d.target.x).attr("y2",d=>d.target.y);
  node.attr("cx",d=>d.x).attr("cy",d=>d.y);
  label.attr("x",d=>d.x).attr("y",d=>d.y);
});
function ds(ev,d){if(!ev.active)sim.alphaTarget(0.3).restart();d.fx=d.x;d.fy=d.y;}
function dm(ev,d){d.fx=ev.x;d.fy=ev.y;}
function de(ev,d){if(!ev.active)sim.alphaTarget(0);d.fx=null;d.fy=null;}
</script></body></html>
""".trimIndent()
