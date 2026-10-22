package com.osint.unified.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.Freshness
import com.osint.unified.data.WatchRepository
import com.osint.unified.data.db.Watch
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    repository: WatchRepository,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    var list by remember { mutableStateOf<List<Watch>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        repository.observeAll().collect { list = it }
    }

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "WATCHED TARGETS",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.TextHi
                    )
                },
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
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "An investigation says what is true now. A watch says what " +
                    "changed. Each target is re-checked every few hours, and " +
                    "only a difference is reported.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${list.size} watched",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.TextMid,
                    modifier = Modifier.weight(1f)
                )
                val canCheck = !checking && list.isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (canCheck) Intel.Cyan else Intel.Elevated)
                        .clickable(enabled = canCheck) {
                            checking = true
                            scope.launch {
                                runCatching { repository.checkAll() }
                                checking = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (checking) "CHECKING…" else "CHECK NOW",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canCheck) Intel.Base else Intel.TextLow
                    )
                }
            }

            if (list.isEmpty()) {
                Text(
                    "Nothing watched yet. Open a dossier and tap WATCH to have " +
                        "the app keep checking that target on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 20.dp)
                )
                return@Scaffold
            }

            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { watch ->
                    WatchRow(
                        watch,
                        onOpen = { onOpen(watch.observable) },
                        onRemove = { scope.launch { repository.remove(watch.id) } }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun WatchRow(watch: Watch, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(
                1.dp,
                if (watch.lastChangeAt != null) Intel.Amber else Intel.Hairline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                watch.observableType.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Intel.Cyan,
                modifier = Modifier.weight(1f)
            )
            Text(
                "REMOVE",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Text(
            watch.observable,
            style = MaterialTheme.typography.titleSmall,
            color = Intel.TextHi,
            fontFamily = FontFamily.Monospace
        )
        // Three different states, and conflating them would be the whole
        // point of the feature lost: never checked, checked and unchanged,
        // and changed.
        val checked = watch.lastCheckedAt
        Text(
            when {
                checked == null -> "not checked yet"
                watch.lastChangeAt == null ->
                    "no change in ${watch.checks} check${if (watch.checks == 1) "" else "s"}" +
                        (ago(checked)?.let { " · last $it" } ?: "")
                else -> watch.lastChange
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (watch.lastChangeAt != null) Intel.Amber else Intel.TextMid,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (watch.lastChangeAt != null) {
            ago(watch.lastChangeAt)?.let {
                Text(
                    "changed $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun ago(millis: Long): String? = Freshness.ago(millis)
