package com.osint.unified.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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
    var proxyOn by remember { mutableStateOf(settings.proxyEnabled) }
    var host by remember { mutableStateOf(settings.proxyHost) }
    var port by remember { mutableStateOf(settings.proxyPort.toString()) }
    var lockOn by remember { mutableStateOf(settings.appLockEnabled) }

    fun applyProxy() {
        val parsed = port.toIntOrNull() ?: 0
        settings.proxyEnabled = proxyOn
        settings.proxyHost = host
        if (parsed in 1..65535) settings.proxyPort = parsed
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
                    onValueChange = { host = it; applyProxy() },
                    singleLine = true,
                    label = { Text("host", style = M3.typography.labelSmall) },
                    textStyle = M3.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColours(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5); applyProxy() },
                    singleLine = true,
                    label = { Text("port", style = M3.typography.labelSmall) },
                    textStyle = M3.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColours(),
                    modifier = Modifier.width(110.dp)
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
