package com.osint.unified.ui.framework

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.ArfNode
import com.osint.unified.data.OsintFramework
import com.osint.unified.ui.theme.Intel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val root = remember { OsintFramework.load(context) }
    val allLeaves = remember(root) { root?.let { OsintFramework.leaves(it) } ?: emptyList() }
    val stack = remember { mutableStateListOf<ArfNode>().apply { root?.let { add(it) } } }
    var query by remember { mutableStateOf("") }

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stack.lastOrNull()?.name?.takeIf { stack.size > 1 } ?: "OSINT FRAMEWORK",
                        style = MaterialTheme.typography.titleSmall, color = Intel.TextHi, maxLines = 1
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
        if (root == null) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Framework directory not bundled in this build.", color = Intel.TextMid)
            }
            return@Scaffold
        }

        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search ${allLeaves.size} resources…", style = MaterialTheme.typography.bodySmall) },
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

            if (query.isNotBlank()) {
                val q = query.trim()
                val matches = allLeaves.filter { it.first.contains(q, true) || it.third.contains(q, true) }.take(300)
                Text(
                    "${matches.size} matches",
                    style = MaterialTheme.typography.labelSmall, color = Intel.TextLow,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(matches) { (name, url, path) ->
                        LeafRow(name, path) { open(url) }
                    }
                }
            } else {
                val node = stack.last()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(node.children) { child ->
                        if (child.isFolder) FolderRow(child) { stack.add(child) }
                        else LeafRow(child.name, null) { child.url?.let(::open) }
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
private fun LeafRow(name: String, path: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp)).background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Intel.CyanBright)
        if (!path.isNullOrBlank()) {
            Text(path, style = MaterialTheme.typography.labelSmall, color = Intel.TextLow, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun countLeaves(node: ArfNode): Int =
    if (node.children.isEmpty()) 1 else node.children.sumOf { countLeaves(it) }
