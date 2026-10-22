package com.osint.unified.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.domain.executor.ExecutorFactory
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class Check(val toolId: String, val input: String)

private val CHECKS = listOf(
    Check("ipinfo", "1.1.1.1"),
    Check("shodan", "1.1.1.1"),
    Check("dns", "github.com"),
    Check("crtsh", "github.com"),
    Check("websearch", "osint tools"),
    Check("github", "torvalds"),
    Check("emailaccounts", "octocat"),
    Check("gravatar", "beau@dentedreality.com.au"),
    Check("geocode", "Paris, France"),
    Check("revgeo", "48.8584, 2.2945"),
    Check("wikinearby", "48.8584, 2.2945"),
    Check("gdelt", "protest"),
    Check("wayback", "github.com"),
    Check("webcrawl", "example.com"),
    Check("dorks", "John Smith")
)

private data class DiagResult(val status: String, val ms: Long, val note: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(executors: ExecutorFactory, onBack: () -> Unit) {
    val results = remember { mutableStateMapOf<String, DiagResult>() }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runAll() {
        if (running) return
        running = true
        CHECKS.forEach { results[it.toolId] = DiagResult("running", 0, "") }
        scope.launch {
            coroutineScope {
                CHECKS.forEach { c ->
                    launch {
                        val tool = ToolRegistry.byId(c.toolId) ?: return@launch
                        val t0 = System.currentTimeMillis()
                        val r = runCatching {
                            withTimeoutOrNull(30_000) { executors.forTool(tool).execute(tool, c.input) }
                        }.getOrNull()
                        val ms = System.currentTimeMillis() - t0
                        results[c.toolId] = when {
                            r == null -> DiagResult("timeout", ms, "no response")
                            r.isSuccess -> {
                                val out = r.getOrNull().orEmpty()
                                val line = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                if (out.isBlank()) DiagResult("empty", ms, "no data")
                                else DiagResult("ok", ms, line.take(90))
                            }
                            else -> DiagResult("fail", ms, (r.exceptionOrNull()?.message ?: "error").take(90))
                        }
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
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            val ok = results.values.count { it.status == "ok" }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "$ok / ${CHECKS.size} sources returning data",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.TextMid,
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
            Text(
                "Runs each source against a known target. Share a screenshot if something's consistently red.",
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            CHECKS.forEach { c ->
                val tool = ToolRegistry.byId(c.toolId)
                val res = results[c.toolId]
                DiagRow(tool?.name ?: c.toolId, res)
                Spacer(Modifier.size(8.dp))
            }
        }
    }
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
                res?.let { if (it.status == "running") "…" else "${it.status.uppercase()} · ${it.ms}ms" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        res?.note?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
