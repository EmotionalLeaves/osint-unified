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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.osint.unified.BuildConfig
import com.osint.unified.ui.theme.Intel

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
    onOpenForensics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSources: () -> Unit
) {
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
