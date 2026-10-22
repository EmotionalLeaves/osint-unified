package com.osint.unified.ui.godeye

import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.BuildConfig
import com.osint.unified.R
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

private val EXAMPLES = listOf("example.com", "8.8.8.8", "40.7128,-74.0060", "+14155550123", "John Smith")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GodEyeViewScreen(
    viewModel: GodEyeViewModel,
    onOpenHistory: () -> Unit,
    onOpenForensics: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showAircraft by remember { mutableStateOf(true) }
    var showVessels by remember { mutableStateOf(true) }
    var showQuakes by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OSINT Unified") },
                actions = {
                    IconButton(onClick = onOpenForensics) {
                        Icon(Icons.Filled.Build, contentDescription = "Forensics & decoders")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    IconButton(onClick = viewModel::onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardActions = KeyboardActions(onSearch = { viewModel.onSearch() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                EXAMPLES.forEach { example ->
                    SuggestionChip(
                        onClick = {
                            viewModel.onQueryChange(example)
                            viewModel.onSearch()
                        },
                        label = { Text(example) }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                FilterChip(selected = showAircraft, onClick = { showAircraft = !showAircraft },
                    label = { Text("Aircraft ${state.aircraft.size}") })
                FilterChip(selected = showVessels, onClick = { showVessels = !showVessels },
                    label = { Text("Vessels ${state.vessels.size}") })
                FilterChip(selected = showQuakes, onClick = { showQuakes = !showQuakes },
                    label = { Text("Quakes ${state.quakes.size}") })
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                GlobeMap(
                    aircraft = if (showAircraft) state.aircraft else emptyList(),
                    vessels = if (showVessels) state.vessels else emptyList(),
                    quakes = if (showQuakes) state.quakes else emptyList(),
                    onInvestigate = { label ->
                        viewModel.onQueryChange(label)
                        viewModel.onSearch()
                    },
                    onMapTap = { lat, lon -> viewModel.onMapClick(lat, lon) },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                ) {
                    Text(
                        "build ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            backendUrl = state.backendUrl,
            tar1090Url = state.tar1090Url,
            aisUrl = state.aisUrl,
            onSave = { backend, tar, ais ->
                viewModel.saveSettings(backend, tar, ais)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * Native OpenStreetMap globe via osmdroid. No WebView and no WebGL — tiles
 * are drawn directly by Android, which renders reliably where the embedded
 * web map libraries did not. Tap the map to investigate a coordinate; tap a
 * marker to investigate that aircraft/vessel.
 */
@Composable
private fun GlobeMap(
    aircraft: List<Aircraft>,
    vessels: List<Vessel>,
    quakes: List<Quake>,
    onInvestigate: (String) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // osmdroid refuses to fetch tiles without a user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(
                    XYTileSource(
                        "CartoDark", 0, 19, 256, ".png",
                        arrayOf(
                            "https://a.basemaps.cartocdn.com/dark_all/",
                            "https://b.basemaps.cartocdn.com/dark_all/",
                            "https://c.basemaps.cartocdn.com/dark_all/"
                        )
                    )
                )
                setMultiTouchControls(true)
                setBackgroundColor(0xFF0B0E11.toInt())
                controller.setZoom(2.5)
                controller.setCenter(GeoPoint(20.0, 0.0))
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onMapTap(p.latitude, p.longitude)
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }
                overlays.add(MapEventsOverlay(receiver))
            }
        },
        update = { map ->
            // Rebuild only the markers; keep the tap-events overlay in place.
            map.overlays.removeAll { it is Marker }
            val density = map.resources.displayMetrics.density
            fun add(lat: Double, lon: Double, color: Int, sizeDp: Int, title: String?, clickable: Boolean) {
                val m = Marker(map)
                m.position = GeoPoint(lat, lon)
                m.icon = dot(color, (sizeDp * density).toInt())
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.title = title
                m.setOnMarkerClickListener { mk, _ ->
                    if (clickable && !mk.title.isNullOrBlank()) onInvestigate(mk.title)
                    true
                }
                map.overlays.add(m)
            }
            quakes.forEach { add(it.lat, it.lon, 0xFFF0997B.toInt(), 8, "M%.1f".format(it.mag), false) }
            vessels.forEach { add(it.lat, it.lon, 0xFF1D9E75.toInt(), 9, it.name.ifBlank { it.mmsi }, true) }
            aircraft.forEach { add(it.lat, it.lon, 0xFF4FC3F7.toInt(), 9, it.callsign.ifBlank { it.icao }, true) }
            map.invalidate()
        }
    )
}

/** A filled circle marker icon built at runtime (no drawable resources). */
private fun dot(color: Int, sizePx: Int): Drawable {
    val size = sizePx.coerceAtLeast(6)
    return ShapeDrawable(OvalShape()).apply {
        paint.color = color
        intrinsicWidth = size
        intrinsicHeight = size
        setBounds(0, 0, size, size)
    }
}

@Composable
private fun SettingsDialog(
    backendUrl: String,
    tar1090Url: String,
    aisUrl: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var backend by remember { mutableStateOf(backendUrl) }
    var tar by remember { mutableStateOf(tar1090Url) }
    var ais by remember { mutableStateOf(aisUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = backend,
                    onValueChange = { backend = it },
                    singleLine = true,
                    label = { Text("Cloud backend URL") },
                    placeholder = { Text("https://your-api.onrender.com") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tar,
                    onValueChange = { tar = it },
                    singleLine = true,
                    label = { Text("tar1090 feed URL") },
                    placeholder = { Text("http://host:8080/data/aircraft.json") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = ais,
                    onValueChange = { ais = it },
                    singleLine = true,
                    label = { Text("AIS vessel feed URL") },
                    placeholder = { Text("https://host/vessels.json") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Leave feeds blank to use demo aircraft and vessels.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(backend, tar, ais) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
