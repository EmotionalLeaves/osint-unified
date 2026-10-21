package com.osint.unified.ui.forensics

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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForensicsScreen(exifReader: ExifReader, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var exifBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Forensics & decoders") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
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
                onClick = {
                    scope.launch {
                        exifBusy = true
                        output = exifReader.fromUrl(imageUrl.trim())
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
