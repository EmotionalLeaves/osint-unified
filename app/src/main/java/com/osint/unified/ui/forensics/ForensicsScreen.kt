package com.osint.unified.ui.forensics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.osint.unified.data.ExifReader
import com.osint.unified.domain.DecoderEngine
import com.osint.unified.domain.Identify
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForensicsScreen(
    exifReader: ExifReader,
    /** An image shared into the app from somewhere else, read on arrival. */
    sharedImage: android.net.Uri? = null,
    onBack: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var exifBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun readPhoto(uri: android.net.Uri) {
        exifBusy = true
        output = runCatching { exifReader.fromUri(uri) }
            .getOrElse { "Could not read that image: ${it.message}" }
        exifBusy = false
    }

    // A photograph on the phone, read here rather than uploaded somewhere
    // to be read. The picker needs no storage permission: the system hands
    // back one image and nothing else.
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) scope.launch { readPhoto(uri) } }

    LaunchedEffect(sharedImage) { sharedImage?.let { readPhoto(it) } }

    Column(modifier = Modifier.fillMaxSize().background(Intel.Base)) {
        TopAppBar(
            title = {
                Text(
                    "FORENSICS & DECODERS",
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

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Text / data") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            // What is it? Choosing the right decoder presumes you already
            // know, and an analyst with a string out of a header or a URL
            // usually does not. Recognition is structural — length,
            // alphabet, range — so this says what the string is before
            // offering to decode it, and offers every honest reading when
            // the shape is genuinely ambiguous.
            val guesses = remember(input) { Identify.of(input) }
            if (guesses.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(
                        "LOOKS LIKE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Cyan
                    )
                    guesses.forEach { g ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .let { m ->
                                    val id = g.decoderId
                                    if (id == null) m
                                    else m.clickable { output = DecoderEngine.run(id, input) }
                                }
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    g.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Intel.CyanBright
                                )
                                Text(
                                    g.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Intel.TextMid
                                )
                            }
                            if (g.decoderId != null) {
                                Text(
                                    "DECODE ▸",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Intel.Amber,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DecoderEngine.ops.forEach { op ->
                    FilledTonalButton(onClick = { output = DecoderEngine.run(op.id, input) }) {
                        Text(op.label)
                    }
                }
            }

            if (output.isNotBlank()) {
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = output,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("EXIF / GPS from image URL", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = imageUrl,
                onValueChange = { imageUrl = it },
                singleLine = true,
                label = { Text("Image URL") },
                placeholder = { Text("https://…/photo.jpg") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = { picker.launch("image/*") },
                enabled = !exifBusy,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(if (exifBusy) "Reading…" else "Pick a photo on this phone")
            }
            Text(
                "Read here, on the phone. Nothing is uploaded, and the hash " +
                    "below is of the file as it sits on disk.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 4.dp)
            )

            Button(
                onClick = {
                    scope.launch {
                        exifBusy = true
                        // The reader throws on a failure now rather than
                        // returning the word "Error", so this screen says
                        // what went wrong instead of printing it as a
                        // finding.
                        output = runCatching { exifReader.fromUrl(imageUrl.trim()) }
                            .getOrElse { "Could not read that image: ${it.message}" }
                        exifBusy = false
                    }
                },
                enabled = !exifBusy && imageUrl.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(if (exifBusy) "Reading…" else "Read EXIF")
            }
        }
    }
}
