package com.osint.unified.ui.watch

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.Freshness
import com.osint.unified.data.WatchRepository
import com.osint.unified.data.db.Watch
import com.osint.unified.data.db.WatchEvent
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.launch

/**
 * The targets the app re-checks on its own, and what it found.
 *
 * Each row now opens into the target's history rather than showing only
 * the most recent line. A watch exists to accumulate a record; a record
 * that keeps overwriting itself is a status, and a status does not answer
 * "when did this start".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatchScreen(
    repository: WatchRepository,
    focusWatchId: Long? = null,
    /** Said once the focus has been applied; see MainActivity. */
    onFocusHandled: () -> Unit = {},
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    var list by remember { mutableStateOf<List<Watch>>(emptyList()) }
    var unseen by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var events by remember { mutableStateOf<List<WatchEvent>>(emptyList()) }
    var expanded by remember { mutableStateOf(focusWatchId) }
    // Held by the repository, not by this screen: the sweep outlives
    // the tab it was started from. See checkAllDetached.
    val checking by repository.running.collectAsStateWithLifecycle()
    val trouble by repository.trouble.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.osint.unified.data.AppSettings(context) }
    var wifiOnly by remember { mutableStateOf(settings.watchOnWifiOnly) }

    LaunchedEffect(Unit) { repository.observeAll().collect { list = it } }
    LaunchedEffect(Unit) {
        repository.observeUnseen().collect { rows ->
            unseen = rows.associate { it.watchId to it.unseen }
        }
    }
    LaunchedEffect(Unit) { repository.observeRecentEvents().collect { events = it } }

    // Arriving from a notification: scroll to the target it was about.
    LaunchedEffect(focusWatchId, list.size) {
        val id = focusWatchId ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            expanded = id
            runCatching { listState.animateScrollToItem(index) }
            // Only now. It was cleared in an effect of its own that
            // ran on the first composition, while `list` was still
            // empty because Room fills it asynchronously — so this
            // effect found nothing, the id went null, and every
            // restart returned at the line above. The row expanded,
            // off screen, and the scroll the notification exists for
            // never happened.
            onFocusHandled()
        }
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
        // Everything above the list used to be siblings of a
        // fillMaxSize LazyColumn, which is a Column measuring its
        // unbounded children first and handing the list whatever is
        // left. At a 2x font size the three-line intro is eight
        // lines, and in landscape the watch list itself was measured
        // at nothing. It is all one scroll now.
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            state = listState
        ) {
          item {
            Text(
                "An investigation says what is true now. A watch says what " +
                    "changed. Each target is re-checked every few hours, and " +
                    "only a difference is reported. Tap a target for its history.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // A Row measures its unweighted children first, so at a
            // large font size MARK ALL SEEN and CHECK NOW took the
            // whole width and the weighted "N watched · M unseen" —
            // the only thing on the screen that says how many there
            // are — was measured at zero and disappeared.
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val totalUnseen = unseen.values.sum()
                Text(
                    "${list.size} watched" + if (totalUnseen > 0) " · $totalUnseen unseen" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (totalUnseen > 0) Intel.Amber else Intel.TextMid,
                    modifier = Modifier.weight(1f)
                )
                if (totalUnseen > 0) {
                    Text(
                        "MARK ALL SEEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextMid,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { scope.launch { repository.acknowledgeAll() } }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val canCheck = !checking && list.isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (canCheck) Intel.Cyan else Intel.Elevated)
                        .clickable(enabled = canCheck) { repository.checkAllDetached() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (checking) "CHECKING…" else "CHECK NOW",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canCheck) Intel.Base else Intel.TextLow
                    )
                }
            }

            // Ten passive tools per watched target, four times a
            // day, and until now no way anywhere in the app to say
            // "not on mobile data". Here rather than in the feed
            // settings, because this is the screen that spends it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .toggleable(value = wifiOnly, role = Role.Switch) { on ->
                        wifiOnly = on
                        settings.watchOnWifiOnly = on
                        com.osint.unified.work.WatchWorker.reschedule(context)
                    }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    if (wifiOnly) {
                        "Background checks: Wi-Fi only. They will wait for an " +
                            "unmetered network."
                    } else {
                        "Background checks: any network, including mobile data."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wifiOnly) Intel.Cyan else Intel.TextMid,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (wifiOnly) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wifiOnly) Intel.Cyan else Intel.TextLow
                )
            }

            trouble?.let { why ->
                Text(
                    "The last check did not finish: $why",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Amber,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { repository.clearTrouble() }
                )
            }

            if (list.isEmpty()) {
                Text(
                    "Nothing watched yet. Open a dossier and tap WATCH to have " +
                        "the app keep checking that target on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 20.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
          }

                items(list, key = { it.id }) { watch ->
                    WatchRow(
                        watch = watch,
                        unseen = unseen[watch.id] ?: 0,
                        expanded = expanded == watch.id,
                        history = events.filter { it.watchId == watch.id },
                        onToggle = {
                            val opening = expanded != watch.id
                            expanded = if (opening) watch.id else null
                            // Opening the history is seeing it.
                            if (opening) scope.launch { repository.acknowledge(watch.id) }
                        },
                        onInvestigate = { onOpen(watch.observable) },
                        onRemove = { scope.launch { repository.remove(watch.id) } }
                    )
                    Spacer(Modifier.height(8.dp))
                }
        }
    }
}

@Composable
private fun WatchRow(
    watch: Watch,
    unseen: Int,
    expanded: Boolean,
    history: List<WatchEvent>,
    onToggle: () -> Unit,
    onInvestigate: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(
                1.dp,
                if (unseen > 0) Intel.Amber else Intel.Hairline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                watch.observableType.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Intel.Cyan,
                modifier = Modifier.weight(1f)
            )
            if (unseen > 0) {
                Text(
                    "$unseen NEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Base,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Intel.Amber)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "REMOVE",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                // 23dp, and it removes a watch.
                modifier = Modifier
                    .minimumInteractiveComponentSize()
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
                        (Freshness.ago(checked)?.let { " · last $it" } ?: "")
                else -> watch.lastChange
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (watch.lastChangeAt != null) Intel.Amber else Intel.TextMid,
            modifier = Modifier.padding(top = 4.dp)
        )

        // What the last check managed to ask.
        //
        // A check where nothing answered raises no alarm, correctly —
        // and the row then read "no change in 12 checks · last 3m
        // ago", which is the same sentence as a check where all ten
        // sources answered and agreed. On a captive portal that
        // happened silently, four times a day.
        if (checked != null && watch.lastAsked > 0 && watch.lastAnswered < watch.lastAsked) {
            Text(
                if (watch.lastAnswered == 0) {
                    "last check: none of ${watch.lastAsked} sources answered, " +
                        "so nothing was compared"
                } else {
                    "last check: ${watch.lastAnswered} of ${watch.lastAsked} " +
                        "sources answered"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (watch.lastAnswered == 0) Intel.Danger else Intel.Amber,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Text(
                if (history.isEmpty()) "no changes recorded"
                else "${history.size} change${if (history.size == 1) "" else "s"}" +
                    (if (expanded) " ▴" else " ▾"),
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.weight(1f)
            )
            Text(
                "INVESTIGATE",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.Cyan,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onInvestigate)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (expanded && history.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp)) {
                history.forEach { event -> EventLine(event) }
            }
        }
    }
}

/**
 * One recorded change, with the sources it involved named rather than
 * summarised. "now found by 2 sources" is a number; "now found by
 * crt.sh, Wayback" is something to act on.
 */
@Composable
private fun EventLine(event: WatchEvent) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Intel.Elevated)
            .padding(8.dp)
    ) {
        Text(
            Freshness.agoOrUndated(event.at),
            style = MaterialTheme.typography.labelSmall,
            color = Intel.TextLow
        )
        listOf(
            Triple("appeared at", event.appeared, Intel.Success),
            Triple("gone from", event.vanished, Intel.Danger),
            Triple("changed at", event.altered, Intel.Amber)
        ).forEach { (label, ids, colour) ->
            val names = ids.split(",").filter { it.isNotBlank() }
                .joinToString(", ") { ToolRegistry.byId(it)?.name ?: it }
            if (names.isNotBlank()) {
                Text(
                    "$label $names",
                    style = MaterialTheme.typography.bodySmall,
                    color = colour,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        // Rows carried over from before the log existed have no source
        // breakdown, only the sentence that was stored at the time.
        if (event.appeared.isBlank() && event.vanished.isBlank() && event.altered.isBlank()) {
            Text(
                event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
