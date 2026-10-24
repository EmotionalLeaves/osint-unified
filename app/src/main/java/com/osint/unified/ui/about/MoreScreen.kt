package com.osint.unified.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.osint.unified.BuildConfig
import com.osint.unified.data.AppSettings
import com.osint.unified.data.SigningIdentity
import com.osint.unified.data.db.AppDatabase
import com.osint.unified.network.Http
import com.osint.unified.ui.common.PrivateBrowserHost
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The things that are used occasionally, named rather than iconified.
 *
 * Forensics, the self-test and the source list were three unlabelled
 * glyphs in the title bar, which is a good way to own a feature nobody
 * ever opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    settings: AppSettings,
    lockAvailable: Boolean,
    onOpenForensics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSources: () -> Unit
) {
    // Asking the database how it opened means waiting for it to open, and
    // after an upgrade that includes copying every case into an encrypted
    // file. Off the main thread, with the panel filling in when it knows.
    val context = LocalContext.current
    val storage by produceState(initialValue = false to "checking…", context) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.get(context) }
            AppDatabase.encrypted to AppDatabase.encryptionNote
        }
    }
    val encrypted = storage.first
    val storageNote = storage.second
    // The published-key warning, which was written, imported here,
    // and never called.
    //
    // The whole of SigningIdentity exists to say that the sealing
    // this panel describes assumes nobody else can produce an app
    // with the same identity — and that stopped being true when
    // this project's signing key was committed to a public
    // repository with its password in the build file. The check was
    // there and the sentence was never shown.
    val signing by produceState(initialValue = SigningIdentity.Verdict.UNKNOWN, context) {
        value = withContext(Dispatchers.IO) { SigningIdentity.verdict(context) }
    }

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MORE",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.TextHi
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Intel.Base)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                // The proxy host and port are near the bottom of
                // this scroll, and the keyboard covered both.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Entry(
                "Forensics & decoders",
                "EXIF from a photograph or a URL, hashes, base64, timestamps, " +
                    "coordinate conversion.",
                onOpenForensics
            )
            Entry(
                "Self-test",
                "Ask every feed and every source whether it is actually " +
                    "answering today, and how long it takes.",
                onOpenDiagnostics
            )
            Entry(
                "Data sources & licences",
                "Every feed and dataset this app uses, who publishes it, and " +
                    "under what terms.",
                onOpenSources
            )
            PrivacyControls(settings, lockAvailable)

            // Stated rather than assumed. An app that claims a security
            // property it does not have is worse than one that claims
            // nothing, so this reports what actually happened when the
            // database was opened on this device.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Intel.Surface)
                    .border(
                        1.dp,
                        if (encrypted) Intel.Hairline else Intel.Amber,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(14.dp)
            ) {
                Text(
                    if (encrypted) "CASE DATABASE: ENCRYPTED" else "CASE DATABASE: NOT ENCRYPTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (encrypted) Intel.Success else Intel.Amber
                )
                Text(
                    storageNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "The key is sealed by this device's hardware keystore and " +
                        "cannot be taken off it. Exported dossiers are not " +
                        "encrypted — they are deleted from the cache after a day, " +
                        "but wherever you shared one is your responsibility.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(top = 6.dp)
                )
                when (signing) {
                    SigningIdentity.Verdict.PUBLISHED -> Text(
                        "This copy is signed with a key that is published, so " +
                            "the sealing above is worth less than it sounds: " +
                            "anyone can build an app with the same identity, " +
                            "install it as an update and unseal the key. Treat " +
                            "the encryption as protection against a lost phone, " +
                            "not against someone who has it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Amber,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    SigningIdentity.Verdict.UNKNOWN -> Text(
                        "This build's signature could not be read, so whether " +
                            "it was signed with the published key is unknown. " +
                            "It is not an all-clear.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Amber,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    SigningIdentity.Verdict.OTHER -> Unit
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "OSINT Unified ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow
            )
            Text(
                "Everything here comes from public sources. Nothing is logged " +
                    "anywhere but this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun Entry(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Intel.TextHi)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = Intel.TextMid,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The two things that change where this app's traffic goes and who can
 * read what it found.
 *
 * Both off unless chosen, and both say exactly what they do and do not
 * cover. A privacy control that overstates itself is worse than none:
 * somebody relies on it.
 */
@Composable
private fun PrivacyControls(settings: AppSettings, lockAvailable: Boolean) {
    // Saveable, because an unapplied edit was lost on a rotation — and
    // the field is applied on losing focus, which a rotation does not
    // count as.
    var proxyOn by rememberSaveable { mutableStateOf(settings.proxyEnabled) }
    var host by rememberSaveable { mutableStateOf(settings.proxyHost) }
    var port by rememberSaveable { mutableStateOf(settings.proxyPort.toString()) }
    var lockOn by rememberSaveable { mutableStateOf(settings.appLockEnabled) }
    var portTrouble by rememberSaveable { mutableStateOf(false) }

    fun applyProxy() {
        val parsed = port.toIntOrNull() ?: 0
        // Said, rather than quietly ignored.
        //
        // The host was persisted unconditionally and the port only if
        // it parsed, and then settings.proxyPort — the old value — was
        // what got applied. So a mistyped port left the field showing
        // one number while every request went to another, with nothing
        // on screen to say so. On a privacy control that is the worst
        // kind of silence.
        portTrouble = parsed !in 1..65535
        if (portTrouble) return
        settings.proxyEnabled = proxyOn
        settings.proxyHost = host
        settings.proxyPort = parsed
        if (proxyOn) {
            Http.useProxy(host, settings.proxyPort)
            PrivateBrowserHost.useProxy(host, settings.proxyPort)
        } else {
            Http.useProxy(null, 0)
            PrivateBrowserHost.useProxy(null, 0)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text("PRIVACY", style = M3.typography.labelSmall, color = Intel.Cyan)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Route traffic through a local proxy",
                    style = M3.typography.titleSmall,
                    color = Intel.TextHi
                )
                Text(
                    "Defaults to Orbot's HTTP port, so this sends the app " +
                        "through Tor. Hostnames go to the proxy rather than " +
                        "being looked up on the phone.",
                    style = M3.typography.bodySmall,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = proxyOn,
                onCheckedChange = { proxyOn = it; applyProxy() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Intel.Base,
                    checkedTrackColor = Intel.Cyan,
                    uncheckedTrackColor = Intel.Elevated
                )
            )
        }

        if (proxyOn) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = host,
                    // Applied when the field is done, not per keystroke.
                    //
                    // Typing a port wrote and applied 9, then 90, then
                    // 905, then 9050 — four proxy configurations, three
                    // of them wrong, each persisted. The host field was
                    // worse: every intermediate string was saved as the
                    // proxy host.
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text("host", style = M3.typography.labelSmall) },
                    textStyle = M3.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColours(),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) applyProxy() }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text("port", style = M3.typography.labelSmall) },
                    textStyle = M3.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColours(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { applyProxy() }),
                    isError = portTrouble,
                    supportingText = if (portTrouble) {
                        {
                            Text(
                                "1-65535",
                                style = M3.typography.labelSmall,
                                color = Intel.Danger
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        // A five-digit port plus the field's own
                        // padding is more than 110dp once the system
                        // font is scaled up, and the number was
                        // clipped in a box the user was typing into.
                        .widthIn(min = 110.dp)
                        .onFocusChanged { if (!it.isFocused) applyProxy() }
                )
            }
            Text(
                "Covers everything the app requests itself, including map " +
                    "tiles and the pages the isolated browser loads. It does " +
                    "not cover the bundled scanning binaries — " +
                    "those are separate programs with their own networking, and " +
                    "they will still go out directly. Leave active recon off if " +
                    "that matters.",
                style = M3.typography.labelSmall,
                color = Intel.Amber,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                Http.proxyDescription()?.let { "currently routed via $it" }
                    ?: "not routed — check the address",
                style = M3.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lock the app",
                    style = M3.typography.titleSmall,
                    color = if (lockAvailable) Intel.TextHi else Intel.TextLow
                )
                Text(
                    if (lockAvailable)
                        "Ask for this phone's fingerprint, PIN or pattern each " +
                            "time the app comes back to the screen. The database " +
                            "being encrypted does nothing about an unlocked phone " +
                            "in someone else's hand; this does."
                    else
                        "Unavailable: this phone has no screen lock or " +
                            "fingerprint set up, so there would be no way back " +
                            "in.",
                    style = M3.typography.bodySmall,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = lockOn,
                enabled = lockAvailable,
                onCheckedChange = { lockOn = it; settings.appLockEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Intel.Base,
                    checkedTrackColor = Intel.Cyan,
                    uncheckedTrackColor = Intel.Elevated
                )
            )
        }
    }
}

@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Intel.Cyan, unfocusedBorderColor = Intel.Hairline,
    cursorColor = Intel.Cyan, focusedContainerColor = Intel.Elevated,
    unfocusedContainerColor = Intel.Elevated, focusedTextColor = Intel.TextHi,
    unfocusedTextColor = Intel.TextHi, focusedLabelColor = Intel.TextLow,
    unfocusedLabelColor = Intel.TextLow
)
