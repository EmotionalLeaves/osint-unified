package com.osint.unified.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.osint.unified.data.Freshness
import com.osint.unified.data.db.Case
import com.osint.unified.data.db.Investigation
import com.osint.unified.domain.CaseLinks
import com.osint.unified.ui.theme.Intel

/**
 * Cases: several targets investigated as one enquiry.
 *
 * Every target used to be an island. A handle leading to an address
 * leading to a host was held together by its pivot chain, but two people
 * you believe are working together, reached separately, had nothing
 * recording the belief — so the app could never say the one thing that
 * makes a case rather than a folder: these two share an address.
 */
@Composable
fun CaseList(
    cases: List<Case>,
    counts: Map<Long, Int>,
    onOpen: (Long) -> Unit,
    onNew: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        NewCaseField("case-list", onNew)
        if (cases.isEmpty()) {
            Text(
                "No cases yet. A case is two or more targets you think belong " +
                    "to the same enquiry; open a target below and file it under " +
                    "one. With two filed, the app can start telling you what " +
                    "they have in common.",
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(top = 12.dp)
            )
            return
        }
        Spacer(Modifier.height(10.dp))
        cases.forEach { case ->
            val n = counts[case.id] ?: 0
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Intel.Surface)
                    .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
                    .clickable { onOpen(case.id) }
                    .padding(12.dp)
            ) {
                Text(
                    case.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Intel.TextHi
                )
                Text(
                    "$n target${if (n == 1) "" else "s"} · " +
                        Freshness.agoOrUndated(case.updatedAt) +
                        if (n < 2) " · needs a second target before anything can link"
                        else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (case.summary.isNotBlank()) {
                    Text(
                        case.summary.lineSequence().first().take(90),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.CyanBright,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NewCaseField(key: Any?, onNew: (String) -> Unit) {
    // Keyed, like the sibling tags field a few rows down. Without a key
    // a half-typed case name carried from one target's filing controls
    // to the next, because the composable is reused.
    var name by rememberSaveable(key) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("New case", style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (name.isNotBlank()) { onNew(name); name = "" }
            }),
            colors = fieldColours(),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        CaseAction("CREATE", if (name.isBlank()) Intel.TextLow else Intel.Cyan) {
            if (name.isNotBlank()) { onNew(name); name = "" }
        }
    }
}

/**
 * What the case amounts to, what joins its targets, and the targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaseDetail(
    case: Case,
    targets: List<Investigation>,
    /** Null while the corroboration pass for this case is still running. */
    links: List<CaseLinks.Link>?,
    /** Distinct targets and how many of them have any findings, or
     *  null while that is still being worked out. Both come from the
     *  same map, so they cannot be counted differently. */
    counts: CaseLinks.Counts?,
    onOpenTarget: (Long) -> Unit,
    onUnfile: (Long) -> Unit,
    onSummary: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    exporting: Boolean,
    onExport: (com.osint.unified.data.ExportFormat) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // key =, not just the input.
        //
        // rememberSaveable's first vararg decides when to RESET the
        // value; the registry key stays the positional composite
        // hash. These are one-per-case values inside a screen that
        // draws one case at a time today and could draw several
        // tomorrow, and the cost of getting it wrong is a half-typed
        // summary or a rename box opening on the wrong case.
        var renaming by rememberSaveable(case.id, key = "renaming-${case.id}") {
            mutableStateOf(false)
        }
        if (renaming) {
            var name by rememberSaveable(case.id, key = "rename-${case.id}") {
                mutableStateOf(case.name)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Case name", style = MaterialTheme.typography.labelSmall) },
                colors = fieldColours(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.padding(top = 6.dp)) {
                CaseAction("SAVE", Intel.Cyan) { onRename(name); renaming = false }
                Spacer(Modifier.padding(horizontal = 3.dp))
                CaseAction("CANCEL", Intel.TextLow) { renaming = false }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    case.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Intel.TextHi,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                CaseAction("RENAME", Intel.TextLow) { renaming = true }
            }
        }

        Text(
            if (links == null) {
                "${targets.size} targets \u00b7 working out what they share\u2026"
            } else {
                CaseLinks.summary(
                    links,
                    counts?.distinct ?: targets.size,
                    counts?.compared ?: targets.size
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Intel.CyanBright,
            modifier = Modifier.padding(top = 8.dp)
        )

        // ---- What holds the case together ----
        if (links != null && links.isNotEmpty()) {
            Text(
                "SHARED BETWEEN TARGETS",
                style = MaterialTheme.typography.labelMedium,
                color = Intel.TextHi,
                modifier = Modifier.padding(top = 14.dp).semantics { heading() }
            )
            Text(
                "Content delivery hosts, role mailboxes and anything you ruled " +
                    "out are excluded. Nearly every site sits behind the same " +
                    "few CDNs, and counting those would bury the real " +
                    "connection under twenty false ones.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            links.take(40).forEach { link -> LinkRow(link) }
        } else if (links != null && targets.size >= 2) {
            // "That is a result, not a gap" is only true of targets
            // that were compared. The header above was taught to say
            // so and this paragraph was not, so the card stated both
            // sentences at once.
            val ran = counts?.compared ?: targets.size
            val of = counts?.distinct ?: targets.size
            Text(
                if (ran < 2) {
                    "There is nothing to compare yet: only $ran of " +
                        "$of targets has any findings."
                } else {
                    "Nothing was found under more than one of these targets. " +
                        "That is a result, not a gap — they may simply not be " +
                        "connected by anything either of them published."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // ---- The analyst's own conclusion about the case ----
        var summary by rememberSaveable(case.id, key = "summary-${case.id}") {
            mutableStateOf(case.summary)
        }
        Text(
            "CASE SUMMARY",
            style = MaterialTheme.typography.labelMedium,
            color = Intel.TextHi,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() }
        )
        OutlinedTextField(
            value = summary,
            onValueChange = { summary = it },
            placeholder = {
                Text(
                    "What you concluded about the case as a whole.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            colors = fieldColours(),
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Row(Modifier.padding(top = 6.dp)) {
            CaseAction(
                if (summary == case.summary) "SAVED" else "SAVE SUMMARY",
                if (summary == case.summary) Intel.TextLow else Intel.Cyan
            ) { onSummary(summary) }
        }

        // ---- The targets ----
        Text(
            "TARGETS · ${targets.size}",
            style = MaterialTheme.typography.labelMedium,
            color = Intel.TextHi,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() }
        )
        if (targets.isEmpty()) {
            Text(
                "Nothing filed here yet. Open a target under TARGETS and file " +
                    "it under this case.",
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        targets.forEach { t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Intel.Surface)
                    .border(1.dp, Intel.Hairline, RoundedCornerShape(8.dp))
                    .clickable { onOpenTarget(t.id) }
                    .padding(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.seedObservable,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Intel.TextHi,
                        fontFamily = FontFamily.Monospace
                    )
                    val labels = CaseLinks.split(t.tags)
                    Text(
                        t.observableType +
                            if (labels.isNotEmpty()) " · " + labels.joinToString(", ") else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow
                    )
                }
                // Also two taps, and for a reason beyond undoing it:
                // this sits inside the row that opens the target, so a
                // tap that misses the row by a few pixels used to
                // unfile it instead.
                val unfile = com.osint.unified.ui.common.rememberTwoTap(t.id) { onUnfile(t.id) }
                CaseAction(
                    if (unfile.armed) "CONFIRM" else "REMOVE",
                    if (unfile.armed) Intel.Danger else Intel.TextLow
                ) { unfile.tap() }
            }
        }

        // ---- Out of the app ----
        Spacer(Modifier.height(14.dp))
        Text(
            "The case report puts what the targets share first, then each " +
                "target's findings. Each dossier still exports on its own, " +
                "with its raw source output.",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.TextLow
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            listOf(
                "PDF" to com.osint.unified.data.ExportFormat.PDF,
                "TEXT" to com.osint.unified.data.ExportFormat.TEXT,
                "JSON" to com.osint.unified.data.ExportFormat.JSON,
                "CSV" to com.osint.unified.data.ExportFormat.CSV
            ).forEach { (label, format) ->
                CaseAction(label, if (exporting) Intel.TextLow else Intel.TextMid) {
                    if (!exporting) onExport(format)
                }
            }
        }

        val deleteCase = com.osint.unified.ui.common.rememberTwoTap(case.id, onDelete)
        Spacer(Modifier.height(14.dp))
        CaseAction(
            if (deleteCase.armed) "CONFIRM — DELETE CASE" else "DELETE CASE",
            if (deleteCase.armed) Intel.Danger else Intel.TextLow
        ) { deleteCase.tap() }
        Text(
            "Deleting a case removes the folder. Every target in it keeps " +
                "its results and stays under TARGETS.",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.TextLow,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LinkRow(link: CaseLinks.Link) {
    val tint = when (link.credibility.code) {
        1 -> Intel.Success
        2 -> Intel.CyanBright
        3 -> Intel.TextHi
        else -> Intel.TextLow
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Text(
            link.grade,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Intel.Elevated)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                link.value,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                fontFamily = FontFamily.Monospace
            )
            Text(
                buildString {
                    append(link.kind.lowercase())
                    append(" · shared by ")
                    append(link.targets.joinToString(", "))
                    if (link.isTarget) append(" · and is itself a target here")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextMid
            )
            Text(
                link.sourceNames.joinToString(", ") +
                    if (link.independent > 1) " (${link.independent} independent)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow
            )
        }
    }
}

/**
 * Filing one target, and labelling it.
 *
 * Tags are free text rather than a fixed vocabulary: the words that
 * matter in an enquiry are the enquiry's own, and a list someone else
 * wrote would be wrong for every case but theirs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilingControls(
    investigation: Investigation,
    cases: List<Case>,
    onFile: (Long?) -> Unit,
    onNewCase: (String) -> Unit,
    onTags: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            "FILED UNDER",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.TextHi
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            CaseAction(
                "NO CASE",
                if (investigation.caseId == null) Intel.Cyan else Intel.TextLow
            ) { onFile(null) }
            cases.forEach { case ->
                CaseAction(
                    case.name,
                    if (investigation.caseId == case.id) Intel.Cyan else Intel.TextLow
                ) { onFile(case.id) }
            }
        }
        NewCaseField(investigation.id) { name -> onNewCase(name) }

        var tags by rememberSaveable(
            investigation.id,
            key = "tags-${investigation.id}"
        ) { mutableStateOf(investigation.tags) }
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            singleLine = true,
            label = { Text("Tags, comma separated", style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onTags(tags) }),
            colors = fieldColours(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(Modifier.padding(top = 6.dp)) {
            CaseAction(
                if (tags.trim() == investigation.tags) "SAVED" else "SAVE TAGS",
                if (tags.trim() == investigation.tags) Intel.TextLow else Intel.Cyan
            ) { onTags(tags) }
        }
    }
}

@Composable
private fun fieldColours() = TextFieldDefaults.colors(
    focusedContainerColor = Intel.Elevated,
    unfocusedContainerColor = Intel.Elevated,
    focusedTextColor = Intel.TextHi,
    unfocusedTextColor = Intel.TextHi,
    cursorColor = Intel.Cyan,
    focusedIndicatorColor = Intel.Cyan,
    unfocusedIndicatorColor = Intel.Hairline,
    focusedLabelColor = Intel.Cyan,
    unfocusedLabelColor = Intel.TextLow
)

@Composable
private fun CaseAction(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        // Every case chip on the screen — CREATE, REMOVE, the four
        // export formats, SAVE TAGS, DELETE CASE — is this, and it
        // was about 28dp tall against the 48dp a thumb needs. The
        // minimum goes outermost, before clip and background, or it
        // only pads the ripple.
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Intel.Elevated)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
