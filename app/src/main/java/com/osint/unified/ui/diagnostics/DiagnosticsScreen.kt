package com.osint.unified.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
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
    "entity" -> "Tesla"
    "name" -> "Smith"
    "wallet" -> "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    "aircraft" -> "A835AF"
    "repo" -> "https://github.com/octocat/Hello-World"
    else -> when (tool.id) {
        // "any" covers several shapes, and a source given input of the
        // wrong shape reports "nothing found" — which in a self-test reads
        // as the source being broken. Each gets something it can use.
        "cve" -> "CVE-2021-44228"
        "wikidata" -> "Tesla"
        "breachcat" -> "adobe.com"
        "emailperm" -> "Jane Smith"
        "outages" -> "US"
        "gdelt" -> "earthquake"
        "toolrun" -> "github.com"
        else -> "osint"
    }
}

/**
 * What this build actually carries: the dataset inventory written at build
 * time, plus the username ruleset size the app merged on this device. Both
 * are measured rather than asserted, so a dataset whose mirror died shows up
 * here instead of silently reducing coverage.
 */
@Composable
private fun BundleManifest() {
    val context = LocalContext.current
    // Merging the rulesets parses several megabytes of JSON and can touch
    // the network, so it never runs on the main thread — the panel fills in
    // when the work finishes.
    val lines by produceState(initialValue = emptyList<String>(), context) {
        value = withContext(Dispatchers.IO) {
            val manifest = runCatching {
                context.assets.open("bundle-manifest.txt").bufferedReader().use { it.readText() }
            }.getOrNull()?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val live = runCatching {
                com.osint.unified.domain.executor.UsernameChecker(context).rulesetSummary()
            }.getOrNull()
            // The checksums CI recorded for every file it bundled, so a
            // dataset can be verified against its upstream rather than
            // taken on trust. Collapsed to a count here; the file ships
            // whole inside the APK for anyone who wants to check one.
            val checksums = runCatching {
                context.assets.open("dataset-checksums.txt").bufferedReader()
                    .use { it.readText() }
            }.getOrNull()?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            manifest +
                listOfNotNull(live?.let { "On this device: $it" }) +
                checksums.map { line ->
                    // "<sha256>  <bytes>  <name>" -> "<name>  sha256 <first 12>"
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        "${parts.drop(2).joinToString(" ")}  sha256 ${parts[0].take(12)}…"
                    } else line
                }
        }
    }
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(Intel.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            "BUNDLED DATASETS",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.Cyan
        )
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextMid,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(executors: ExecutorFactory, feeds: List<FeedProbe>, onBack: () -> Unit) {
    val probes = remember(feeds) {
        val toolProbes = ToolRegistry.all
            .filter { it.executionMode == ExecutionMode.DIRECT }
            .map { tool ->
                Probe(tool.name, "TOOLS") {
                    val input = sampleFor(tool)
                    // Timed here. The column used to be hard-coded to zero,
                    // so a self-test that exists to find slow sources
                    // reported every one of them as instant.
                    val t0 = System.currentTimeMillis()
                    val r = runCatching { executors.forTool(tool).execute(tool, input) }.getOrNull()
                    toResult(r, System.currentTimeMillis() - t0)
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

    // It used to run on open, and again on every rotation: about a hundred
    // requests, including lookups of real third-party accounts, fired
    // because someone looked at a screen. It waits to be asked.

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
        // The manifest and the run button were siblings of a
        // fillMaxSize LazyColumn. The manifest is one line per
        // bundled dataset plus one per checksum and has no ceiling,
        // so on a device with the full set it took the screen and
        // the self-test results — the entire point of this page —
        // were laid out at no height at all.
        LazyColumn(modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
          item {
            BundleManifest()
            val ok = results.values.count { it.status == "ok" }
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    if (results.isEmpty()) "${probes.size} sources — nothing run yet"
                    else "$ok / ${probes.size} sources returning data",
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
                        if (running) "RUNNING…" else if (results.isEmpty()) "RUN" else "RE-RUN",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (running) Intel.TextLow else Intel.Base
                    )
                }
            }
          }

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

private fun toResult(r: Result<String>?, ms: Long): DiagResult = when {
    r == null -> DiagResult("fail", ms, "error")
    r.isSuccess -> {
        val out = r.getOrNull().orEmpty()
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        when {
            out.isBlank() -> DiagResult("empty", ms, "no data")
            // The same judgement the dossier uses, so the self-test and the
            // dossier cannot disagree about whether a source found anything.
            com.osint.unified.domain.Findings.saysUnreachable(out) ->
                DiagResult("fail", ms, line.take(80))
            com.osint.unified.domain.Findings.saysNothing(out) ->
                DiagResult("empty", ms, line.take(80))
            else -> DiagResult("ok", ms, line.take(80))
        }
    }
    else -> DiagResult("fail", ms, (r.exceptionOrNull()?.message ?: "error").take(80))
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
                res?.let {
                    when {
                        it.status == "running" -> "…"
                        it.ms > 0 -> "${it.status.uppercase()} ${it.ms}ms"
                        else -> it.status.uppercase()
                    }
                } ?: "",
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
