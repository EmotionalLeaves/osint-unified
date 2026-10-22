package com.osint.unified.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Tool
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.domain.executor.ExecutorFactory
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A single testable source: a name and a suspend producing a one-line result. */
class Probe(val name: String, val group: String, val run: suspend () -> DiagResult)

data class DiagResult(val status: String, val ms: Long, val note: String)

/** A live globe feed to include in the self-test. */
class FeedProbe(val name: String, val fetch: suspend () -> Int)

private fun sampleFor(tool: Tool): String = when (tool.inputType) {
    "email" -> "test@gmail.com"
    "domain" -> "github.com"
    "ip" -> "1.1.1.1"
    "username" -> "octocat"
    "phone" -> "+14155550123"
    "coordinate" -> "48.8584, 2.2945"
    "place" -> "Paris, France"
    "image", "media" -> "https://upload.wikimedia.org/wikipedia/commons/3/3f/Fronalpstock_big.jpg"
    else -> "osint"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(executors: ExecutorFactory, feeds: List<FeedProbe>, onBack: () -> Unit) {
    val probes = remember(feeds) {
        val toolProbes = ToolRegistry.all
            .filter { it.executionMode == ExecutionMode.DIRECT }
            .map { tool ->
                Probe(tool.name, "TOOLS") {
                    val input = sampleFor(tool)
                    val r = runCatching { executors.forTool(tool).execute(tool, input) }.getOrNull()
                    toResult(r)
                }
            }
        val feedProbes = feeds.map { f ->
            Probe(f.name, "FEEDS") {
                val t0 = System.currentTimeMillis()
                val n = runCatching { f.fetch() }.getOrNull()
                val ms = System.currentTimeMillis() - t0
                when {
                    n == null -> DiagResult("fail", ms, "error")
                    n == 0 -> DiagResult("empty", ms, "0 results")
                    else -> DiagResult("ok", ms, "$n results")
                }
            }
        }
        toolProbes + feedProbes
    }

    val results = remember { mutableStateMapOf<String, DiagResult>() }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runAll() {
        if (running) return
        running = true
        probes.forEach { results[it.name] = DiagResult("running", 0, "") }
        scope.launch {
            coroutineScope {
                probes.forEach { p ->
                    launch {
                        val t0 = System.currentTimeMillis()
                        val res = withTimeoutOrNull(35_000) { p.run() }
                            ?: DiagResult("timeout", System.currentTimeMillis() - t0, "no response")
                        results[p.name] = res
                    }
                }
            }
            running = false
        }
    }

    LaunchedEffect(Unit) { runAll() }

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = { Text("SELF-TEST", style = MaterialTheme.typography.titleSmall, color = Intel.TextHi) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Intel.Base, navigationIconContentColor = Intel.Cyan
                )
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            val ok = results.values.count { it.status == "ok" }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "$ok / ${probes.size} sources returning data",
                    style = MaterialTheme.typography.labelMedium, color = Intel.TextMid,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (running) Intel.Elevated else Intel.Cyan)
                        .clickable(enabled = !running) { runAll() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (running) "RUNNING…" else "RE-RUN",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (running) Intel.TextLow else Intel.Base
                    )
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(probes) { index, p ->
                    if (index == 0 || probes[index - 1].group != p.group) {
                        Text(
                            p.group,
                            style = MaterialTheme.typography.labelSmall, color = Intel.Cyan,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                        )
                    }
                    DiagRow(p.name, results[p.name])
                    Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}

private fun toResult(r: Result<String>?): DiagResult = when {
    r == null -> DiagResult("fail", 0, "error")
    r.isSuccess -> {
        val out = r.getOrNull().orEmpty()
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        if (out.isBlank()) DiagResult("empty", 0, "no data") else DiagResult("ok", 0, line.take(80))
    }
    else -> DiagResult("fail", 0, (r.exceptionOrNull()?.message ?: "error").take(80))
}

@Composable
private fun DiagRow(name: String, res: DiagResult?) {
    val color = when (res?.status) {
        "ok" -> Intel.Success
        "empty" -> Intel.Amber
        "timeout", "fail" -> Intel.Danger
        else -> Intel.TextLow
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, color = Intel.TextHi, modifier = Modifier.weight(1f))
            Text(
                res?.let { if (it.status == "running") "…" else it.status.uppercase() } ?: "",
                style = MaterialTheme.typography.labelSmall, color = color
            )
        }
        res?.note?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = Intel.TextMid,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
