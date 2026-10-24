package com.osint.unified.ui.framework

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osint.unified.data.ArfNode
import com.osint.unified.data.OsintFramework
import com.osint.unified.domain.ToolTemplates
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameworkScreen(initialTarget: String = "", onBack: () -> Unit) {
    val context = LocalContext.current
    // Parsing the bundled directory and several thousand username rules
    // takes long enough to drop frames, so it happens off the main thread
    // and the screen fills in when it is ready rather than opening frozen.
    val root by produceState<ArfNode?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { OsintFramework.load(context) }
    }
    val allLeaves = remember(root) { root?.let { OsintFramework.leaves(it) } ?: emptyList() }
    // The directory tree is catalogue links — mostly homepages, which cannot
    // be pointed at anything. The runnable tools are the bundled username
    // rulesets: thousands of exact profile URLs, each one a site that will
    // show you an account if there is one. They are searched alongside the
    // tree rather than hidden behind the username sweep.
    val templates by produceState(initialValue = emptyList<ToolTemplates.Template>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { ToolTemplates.derived(context) }.getOrDefault(emptyList())
        }
    }
    val stack = remember { mutableStateListOf<ArfNode>() }
    LaunchedEffect(root) { if (stack.isEmpty()) root?.let { stack.add(it) } }
    var query by remember { mutableStateOf("") }
    // Carried over from whatever the dossier is open on, so arriving here
    // mid-investigation does not mean retyping the target.
    var target by remember(initialTarget) { mutableStateOf(initialTarget) }

    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(root, templates) {
        if (root != null || templates.isNotEmpty()) loading = false
    }

    // Back goes up one folder; only at the root does it leave the screen.
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    fun open(url: String) = com.osint.unified.ui.common.LinkOpener.open(context, url)

    // A directory entry is runnable when its URL carries a search parameter
    // the app can fill in. With a target typed, those entries launch the tool
    // already pointed at it instead of at an empty search box.
    fun runnable(url: String): String? {
        if (target.isBlank()) return null
        val t = ToolTemplates.templateFor(url) ?: return null
        return t.resolve(target.trim())
    }

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stack.lastOrNull()?.name?.takeIf { stack.size > 1 } ?: "OSINT FRAMEWORK",
                        style = MaterialTheme.typography.titleSmall, color = Intel.TextHi,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Intel.Base, navigationIconContentColor = Intel.Cyan
                )
            )
        }
    ) { pad ->
        if (stack.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Loading the directory…"
                    else "Framework directory not bundled in this build.",
                    color = Intel.TextMid
                )
            }
            return@Scaffold
        }

        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text(
                        "search ${allLeaves.size + templates.size} tools…",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Intel.Cyan, unfocusedBorderColor = Intel.Hairline,
                    cursorColor = Intel.Cyan, focusedContainerColor = Intel.Surface,
                    unfocusedContainerColor = Intel.Surface, focusedTextColor = Intel.TextHi,
                    unfocusedTextColor = Intel.TextHi, focusedPlaceholderColor = Intel.TextLow,
                    unfocusedPlaceholderColor = Intel.TextLow
                ),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )

            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                singleLine = true,
                placeholder = {
                    Text(
                        "target to run tools against — name · email · domain · ip",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = { Text("▸", style = MaterialTheme.typography.titleMedium, color = Intel.Amber) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Intel.Amber, unfocusedBorderColor = Intel.Hairline,
                    cursorColor = Intel.Amber, focusedContainerColor = Intel.Surface,
                    unfocusedContainerColor = Intel.Surface, focusedTextColor = Intel.TextHi,
                    unfocusedTextColor = Intel.TextHi, focusedPlaceholderColor = Intel.TextLow,
                    unfocusedPlaceholderColor = Intel.TextLow
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            if (target.isNotBlank()) {
                Text(
                    "Entries marked RUN open already filled in with \"${target.trim()}\".",
                    style = MaterialTheme.typography.labelSmall, color = Intel.Amber,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }

            if (query.isNotBlank()) {
                val q = query.trim()
                val matches = allLeaves.filter {
                    it.first.contains(q, true) || it.third.contains(q, true) ||
                        it.second.contains(q, true)
                }.take(300)
                val tmplMatches = templates.filter {
                    it.name.contains(q, true) || it.url.contains(q, true)
                }.take(300)
                Text(
                    "${matches.size} directory · ${tmplMatches.size} runnable",
                    style = MaterialTheme.typography.labelSmall, color = Intel.TextLow,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(tmplMatches) { t ->
                        val resolved = if (target.isBlank()) null
                        else ToolTemplates.resolve(t, target.trim())
                        LeafRow(
                            t.name,
                            t.source.ifBlank { "runnable" },
                            resolved?.let { r -> { open(r) } }
                        ) { open(resolved ?: t.url.replace("{}", "")) }
                    }
                    items(matches) { (name, url, path) ->
                        LeafRow(name, path, runnable(url)?.let { r -> { open(r) } }) { open(url) }
                    }
                }
            } else {
                val node = stack.last()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(node.children) { child ->
                        if (child.isFolder) FolderRow(child) { stack.add(child) }
                        else LeafRow(
                            child.name,
                            null,
                            child.url?.let { u -> runnable(u) }?.let { r -> { open(r) } }
                        ) { child.url?.let(::open) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(node: ArfNode, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Intel.Cyan))
        Spacer(Modifier.width(10.dp))
        Text(node.name, style = MaterialTheme.typography.titleSmall, color = Intel.TextHi, modifier = Modifier.weight(1f))
        Text("${countLeaves(node)} ›", style = MaterialTheme.typography.labelSmall, color = Intel.TextLow)
    }
}

@Composable
private fun LeafRow(
    name: String,
    path: String?,
    onRun: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp)).background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = Intel.CyanBright)
            if (!path.isNullOrBlank()) {
                Text(
                    path, style = MaterialTheme.typography.labelSmall, color = Intel.TextLow,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (onRun != null) {
            Text(
                "RUN ▸",
                style = MaterialTheme.typography.labelMedium,
                color = Intel.Amber,
                fontFamily = FontFamily.Monospace,
                // The only way to run anything from this screen,
                // at about 28dp tall.
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Intel.Elevated)
                    .clickable(onClick = onRun)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

private fun countLeaves(node: ArfNode): Int =
    if (node.children.isEmpty()) 1 else node.children.sumOf { countLeaves(it) }
