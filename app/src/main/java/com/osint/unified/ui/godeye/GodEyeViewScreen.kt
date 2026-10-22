package com.osint.unified.ui.godeye

import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.BuildConfig
import com.osint.unified.ui.theme.Intel
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

private val EXAMPLES = listOf("example.com", "8.8.8.8", "40.7128,-74.0060", "+14155550123", "John Smith")

/** Display modes for the globe: tactical filters over the satellite imagery. */
private enum class VisualMode(val label: String) {
    DEFAULT("DEFAULT"), NVG("NVG"), FLIR("FLIR"), CRT("CRT"), GOD("GOD MODE")
}

private val SEARCH_TYPES = listOf(
    "auto" to "AUTO", "name" to "NAME", "email" to "EMAIL", "username" to "USER",
    "domain" to "DOMAIN", "ip" to "IP", "phone" to "PHONE", "coordinate" to "COORD", "image" to "IMAGE"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GodEyeViewScreen(
    viewModel: GodEyeViewModel,
    onOpenHistory: () -> Unit,
    onOpenForensics: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenFramework: () -> Unit = {},
    onOpenWatch: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showAircraft by remember { mutableStateOf(true) }
    var showVessels by remember { mutableStateOf(true) }
    var showQuakes by remember { mutableStateOf(true) }
    var showEvents by remember { mutableStateOf(true) }
    var showOverlay by remember { mutableStateOf(true) }
    var showWeather by remember { mutableStateOf(true) }
    var showFires by remember { mutableStateOf(true) }
    var showSats by remember { mutableStateOf(true) }
    var showNews by remember { mutableStateOf(false) }
    var showGdacs by remember { mutableStateOf(true) }
    var showVolcanoes by remember { mutableStateOf(true) }
    var showAurora by remember { mutableStateOf(true) }
    var showBuoys by remember { mutableStateOf(false) }
    var showMetar by remember { mutableStateOf(false) }
    var showMil by remember { mutableStateOf(true) }
    var showConflict by remember { mutableStateOf(true) }
    var showCyclones by remember { mutableStateOf(true) }
    var showTsunami by remember { mutableStateOf(true) }
    var showAirportStatus by remember { mutableStateOf(true) }
    var showRadiation by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VisualMode.DEFAULT) }
    val context = LocalContext.current
    var inspected by remember { mutableStateOf<Inspected?>(null) }
    // What a long press on the globe turned up: every loaded layer, swept
    // for a radius around that point.
    var area by remember { mutableStateOf<AreaReport.Report?>(null) }
    // The world brief is derived from the same state the globe draws, so it
    // is recomputed rather than fetched, and is never out of step with it.
    var showBrief by rememberSaveable { mutableStateOf(false) }
    // Keyed on the layers the brief reads, not the whole state: the
    // aircraft poll replaces the state object every fifteen seconds, and
    // rebuilding the ranking each time would be work for no new answer.
    val brief = remember(
        state.conflicts, state.gdacs, state.tsunamis, state.cyclones,
        state.volcanoes, state.quakes, state.weather, state.fires,
        state.milAircraft, state.ladd, state.airportStatus, state.news,
        state.spaceWeather, state.loaded,
        // The traffic line reads these three, so leaving them out of the key
        // let the brief quote counts the header beside it had moved past.
        state.aircraft, state.vessels, state.satellites
    ) { WorldBrief.build(state) }
    // The panel of search controls and layer pills is most of a phone
    // screen. On something called a God's Eye View the globe should be the
    // thing you are looking at, so the panel folds away.
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var flightFilter by remember { mutableStateOf("ALL") }
    val god = mode == VisualMode.GOD
    // GOD MODE forces every layer visible.
    fun <T> vis(on: Boolean, data: List<T>): List<T> = if (on || god) data else emptyList()
    // Three layers hold nothing until something asks for them. A mode that
    // claims to show everything has to ask.
    LaunchedEffect(god) {
        if (god) {
            viewModel.ensureCameras()
            viewModel.ensureAirspace()
            viewModel.ensureRadiation(true)
        }
    }

    // Live UTC clock for the HUD.
    var utc by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        while (true) {
            utc = fmt.format(java.util.Date())
            kotlinx.coroutines.delay(1000)
        }
    }
    var showAqi by remember { mutableStateOf(false) }
    var showStations by remember { mutableStateOf(false) }
    var showAirports by remember { mutableStateOf(false) }
    var showCameras by remember { mutableStateOf(false) }
    var showAirspace by remember { mutableStateOf(false) }
    var showLadd by remember { mutableStateOf(false) }
    var showCables by remember { mutableStateOf(false) }
    var showPower by remember { mutableStateOf(false) }

    Scaffold(containerColor = Intel.Base) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            // Classification banner (tactical console convention; green = UNCLASS).
            Box(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF14361B)).padding(vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "UNCLASSIFIED // OSINT // FOR OFFICIAL RESEARCH USE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FE88F)
                )
            }

            // ---- Console header + live telemetry ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Intel.Success))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "OSINT ∷ UNIFIED",
                            style = MaterialTheme.typography.titleSmall,
                            color = Intel.TextHi
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "$utc" + "Z",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Cyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    // One line, truncated rather than wrapped — the counts
                    // used to wrap over three lines and push the map down.
                    Text(
                        "AIR ${state.aircraft.size} · SEA ${state.vessels.size} · " +
                            "SAT ${state.satellites.size} · SEIS ${state.quakes.size}" +
                            (state.spaceWeather?.let { " · Kp %.1f".format(it.kp) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    // Counts are not a situation: eleven thousand aircraft is
                    // an ordinary sky. The top of the brief goes here, where
                    // it is read without being asked for.
                    Text(
                        "▸ " + WorldBrief.headline(brief),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Amber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { showBrief = true }
                    )
                }
                IconButton(onClick = { panelOpen = !panelOpen }) {
                    Icon(
                        if (panelOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (panelOpen) "Hide controls" else "Show controls",
                        tint = Intel.Cyan
                    )
                }
                IconButton(onClick = onOpenFramework) {
                    Icon(Icons.Filled.AccountTree, contentDescription = "OSINT Framework", tint = Intel.TextMid)
                }
                IconButton(onClick = onOpenDiagnostics) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = "Self-test", tint = Intel.TextMid)
                }
                IconButton(onClick = onOpenForensics) {
                    Icon(Icons.Filled.Build, contentDescription = "Forensics & decoders", tint = Intel.TextMid)
                }
                IconButton(onClick = onOpenWatch) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = "Watched targets",
                        tint = Intel.TextMid
                    )
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = "History", tint = Intel.TextMid)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Intel.TextMid)
                }
            }

            if (panelOpen) {
            // ---- Command search ----
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                leadingIcon = { Text("›", style = MaterialTheme.typography.titleMedium, color = Intel.Cyan) },
                placeholder = { Text("query any target — name · email · domain · ip · coord · image", style = MaterialTheme.typography.bodySmall) },
                trailingIcon = {
                    IconButton(onClick = viewModel::onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Intel.Cyan)
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Intel.Cyan,
                    unfocusedBorderColor = Intel.Hairline,
                    cursorColor = Intel.Cyan,
                    focusedContainerColor = Intel.Surface,
                    unfocusedContainerColor = Intel.Surface,
                    focusedTextColor = Intel.TextHi,
                    unfocusedTextColor = Intel.TextHi,
                    focusedPlaceholderColor = Intel.TextLow,
                    unfocusedPlaceholderColor = Intel.TextLow
                ),
                keyboardActions = KeyboardActions(onSearch = { viewModel.onSearch() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // Search-type selector: AUTO detects; others force the type so a
            // query always routes to the right tools.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                SEARCH_TYPES.forEach { (key, label) ->
                    TypeChip(label, state.searchType == key) { viewModel.onTypeSelected(key) }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                EXAMPLES.forEach { example ->
                    ExamplePill(example) {
                        viewModel.onQueryChange(example)
                        viewModel.onSearch()
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // When a callsign filter is on, the feed size is not what is
                // on screen, and a pill reading 11,000 over a map holding
                // forty dots is the map disagreeing with itself.
                LayerPill("AIR", filterFlights(state.aircraft, flightFilter).size, Intel.NetCyan, showAircraft || god, loaded = "aircraft" in state.loaded) { showAircraft = !showAircraft }
                LayerPill("MIL", state.milAircraft.size, Color(0xFFFFD23F), showMil || god, loaded = "milAircraft" in state.loaded) { showMil = !showMil }
                LayerPill("SEA", state.vessels.size, Intel.Teal, showVessels || god, loaded = "vessels" in state.loaded) { showVessels = !showVessels }
                LayerPill("SEIS", state.quakes.size, Intel.Coral, showQuakes || god, loaded = "quakes" in state.loaded) { showQuakes = !showQuakes }
                LayerPill("EVT", state.naturalEvents.size, Intel.Amber, showEvents || god, loaded = "naturalEvents" in state.loaded) { showEvents = !showEvents }
                LayerPill("WX", state.weather.size, Color(0xFF7FB2FF), showWeather || god, loaded = "weather" in state.loaded) { showWeather = !showWeather }
                LayerPill("FIRE", state.fires.size, Color(0xFFFF5A4D), showFires || god, loaded = "fires" in state.loaded) { showFires = !showFires }
                LayerPill("SAT", state.satellites.size, Color(0xFFC0A8FF), showSats || god, loaded = "satellites" in state.loaded) { showSats = !showSats }
                LayerPill("HAZARD", state.gdacs.size, Color(0xFFFF8A3D), showGdacs || god, loaded = "gdacs" in state.loaded) { showGdacs = !showGdacs }
                LayerPill("VOLC", state.volcanoes.size, Color(0xFFFF4D4D), showVolcanoes || god, loaded = "volcanoes" in state.loaded) { showVolcanoes = !showVolcanoes }
                LayerPill("CONFLICT", state.conflicts.size, Color(0xFFFF2E2E), showConflict || god, loaded = "conflicts" in state.loaded) { showConflict = !showConflict }
                LayerPill("AQI", state.airQuality.size, Color(0xFFB4E66B), showAqi || god, loaded = "airQuality" in state.loaded) { showAqi = !showAqi }
                LayerPill("STATIONS", state.stations.size, Color(0xFF9AB0C0), showStations || god, loaded = "stations" in state.loaded) { showStations = !showStations }
                LayerPill("APT", state.airports.size, Color(0xFF8FD3FF), showAirports || god, loaded = "airports" in state.loaded) { showAirports = !showAirports }
                LayerPill("POWER", state.powerPlants.size, Color(0xFFFFC14D), showPower || god, loaded = "powerPlants" in state.loaded) { showPower = !showPower }
                LayerPill("AURORA", state.aurora.size, Color(0xFF66FFB2), showAurora || god, loaded = "aurora" in state.loaded) { showAurora = !showAurora }
                LayerPill("BUOY", state.buoys.size, Color(0xFF4DD0E1), showBuoys || god, loaded = "buoys" in state.loaded) { showBuoys = !showBuoys }
                LayerPill("METAR", state.metar.size, Color(0xFF9FE8FF), showMetar || god, loaded = "metar" in state.loaded) { showMetar = !showMetar }
                LayerPill("NEWS", state.news.size, Color(0xFFFF79C6), showNews || god, loaded = "news" in state.loaded) { showNews = !showNews }
                LayerPill(
                    "CCTV", state.cameras.size, Color(0xFFB388FF), showCameras || god,
                    loadsOnDemand = true, loaded = "cameras" in state.loaded
                ) {
                    showCameras = !showCameras
                    if (showCameras) viewModel.ensureCameras()
                }
                LayerPill("LADD", state.ladd.size, Color(0xFFFFD166), showLadd || god, loaded = "ladd" in state.loaded) { showLadd = !showLadd }
                LayerPill("CABLE", state.cables.size, Color(0xFF7FFFD4), showCables || god, loaded = "cables" in state.loaded) { showCables = !showCables }
                LayerPill("CYCLONE", state.cyclones.size, Color(0xFFFF7AC6), showCyclones || god, loaded = "cyclones" in state.loaded) { showCyclones = !showCyclones }
                LayerPill("TSUNAMI", state.tsunamis.size, Color(0xFF4DF0FF), showTsunami || god, loaded = "tsunamis" in state.loaded) { showTsunami = !showTsunami }
                LayerPill("ATC", state.airportStatus.size, Color(0xFFFFA64D), showAirportStatus || god, loaded = "airportStatus" in state.loaded) { showAirportStatus = !showAirportStatus }
                LayerPill(
                    "RAD", state.radiation.size, Color(0xFFD4FF4D), showRadiation || god,
                    loadsOnDemand = true, loaded = "radiation" in state.loaded
                ) {
                    showRadiation = !showRadiation
                    viewModel.ensureRadiation(showRadiation)
                }
                LayerPill(
                    "SUA", state.airspace.size, Color(0xFFFF5370), showAirspace || god,
                    loadsOnDemand = true, loaded = "airspace" in state.loaded
                ) {
                    showAirspace = !showAirspace
                    if (showAirspace) viewModel.ensureAirspace()
                }
                if (state.overlayPoints.isNotEmpty()) {
                    LayerPill("OVLY", state.overlayPoints.size, Intel.CyanBright, showOverlay || god, loaded = "overlayPoints" in state.loaded) { showOverlay = !showOverlay }
                }
            }

            Text(
                "tap a marker to identify it · tap the map to investigate a point · " +
                    "long-press for everything in the area",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )

            }

            if (panelOpen && (showAircraft || god)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    listOf("ALL", "CARGO", "MIL", "CIVIL").forEach { f ->
                        TypeChip(f, flightFilter == f) { flightFilter = f }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 2.dp)
                    .clipToBounds()
            ) {
                GlobeMap(
                    aircraft = vis(showAircraft, filterFlights(state.aircraft, flightFilter)),
                    vessels = vis(showVessels, state.vessels),
                    quakes = vis(showQuakes, state.quakes),
                    events = vis(showEvents, state.naturalEvents),
                    overlay = vis(showOverlay, state.overlayPoints),
                    weather = vis(showWeather, state.weather),
                    fires = vis(showFires, state.fires),
                    satellites = vis(showSats, state.satellites),
                    news = vis(showNews, state.news),
                    gdacs = vis(showGdacs, state.gdacs),
                    volcanoes = vis(showVolcanoes, state.volcanoes),
                    aurora = vis(showAurora, state.aurora),
                    buoys = vis(showBuoys, state.buoys),
                    metar = vis(showMetar, state.metar),
                    milAircraft = vis(showMil, state.milAircraft),
                    conflicts = vis(showConflict, state.conflicts),
                    airQuality = vis(showAqi, state.airQuality),
                    stations = vis(showStations, state.stations),
                    airports = vis(showAirports, state.airports),
                    powerPlants = vis(showPower, state.powerPlants),
                    cameras = vis(showCameras, state.cameras),
                    airspace = vis(showAirspace, state.airspace),
                    ladd = vis(showLadd, state.ladd),
                    cables = vis(showCables, state.cables),
                    cyclones = vis(showCyclones, state.cyclones),
                    tsunamis = vis(showTsunami, state.tsunamis),
                    airportStatus = vis(showAirportStatus, state.airportStatus),
                    radiation = vis(showRadiation, state.radiation),
                    findings = state.findings,
                    focus = state.mapFocus,
                    onInspect = { inspected = it },
                    onViewport = viewModel::onViewportChanged,
                    onMapTap = { lat, lon -> viewModel.onMapClick(lat, lon) },
                    onAreaPress = { lat, lon, zoom ->
                        area = AreaReport.build(state, lat, lon, AreaReport.radiusForZoom(zoom))
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds()
                )
                // Tactical display filter over the imagery.
                when (mode) {
                    VisualMode.NVG -> Box(Modifier.matchParentSize().background(Color(0x5512FF7A)))
                    VisualMode.FLIR -> Box(Modifier.matchParentSize().background(Color(0x55FF6A00)))
                    VisualMode.CRT -> {
                        Box(Modifier.matchParentSize().background(Color(0x3326FF95)))
                        Canvas(Modifier.matchParentSize()) {
                            var y = 0f
                            while (y < size.height) {
                                drawLine(Color(0x26000000), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                y += 4f
                            }
                        }
                    }
                    else -> Unit
                }

                // Mode cycle button.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .border(1.dp, Intel.Cyan, RoundedCornerShape(6.dp))
                        .clickable {
                            mode = VisualMode.values()[(mode.ordinal + 1) % VisualMode.values().size]
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, color = Intel.CyanBright)
                }

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

    if (showBrief) {
        AlertDialog(
            onDismissRequest = { showBrief = false },
            containerColor = Intel.Surface,
            titleContentColor = Intel.TextHi,
            textContentColor = Intel.TextMid,
            title = {
                Column {
                    Text(
                        "WORLD BRIEF",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.Cyan
                    )
                    Text(
                        "$utc" + "Z · from the layers loaded now",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (brief.sections.isEmpty()) {
                        Text(
                            "No layer has reported yet. Give the feeds a few seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Intel.TextMid
                        )
                    }
                    brief.sections.forEach { section ->
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (section.severity >= 9) Intel.Danger else Intel.Cyan,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                        section.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = Intel.TextLow
                            )
                        }
                        section.entries.forEach { entry ->
                            Text(
                                entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = Intel.TextHi,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = entry.lat != null) {
                                        val la = entry.lat
                                        val lo = entry.lon
                                        if (la != null && lo != null) {
                                            inspected = Inspected(
                                                section.title.substringBefore(" —"),
                                                entry.text, la, lo, entry.url
                                            )
                                        }
                                        showBrief = false
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                    if (brief.quiet.isNotEmpty()) {
                        Text(
                            "Reported nothing anywhere: " + brief.quiet.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBrief = false }) {
                    Text("CLOSE", style = MaterialTheme.typography.labelMedium, color = Intel.Cyan)
                }
            }
        )
    }

    area?.let { report ->
        AlertDialog(
            onDismissRequest = { area = null },
            containerColor = Intel.Surface,
            titleContentColor = Intel.TextHi,
            textContentColor = Intel.TextMid,
            title = {
                Column {
                    Text(
                        "AREA · ${AreaReport.fmtKm(report.radiusKm)} RADIUS",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.Cyan
                    )
                    Text(
                        "%.4f, %.4f".format(report.lat, report.lon),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (report.total == 0) AreaReport.headline(report)
                        else "${report.total} within range · ${AreaReport.headline(report)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Intel.TextMid
                    )
                    com.osint.unified.domain.Mgrs.from(report.lat, report.lon)?.let { grid ->
                        Text(
                            grid,
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    report.groups.forEach { group ->
                        Text(
                            "${group.kind.uppercase()} · ${group.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Cyan,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                        group.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        inspected = Inspected(
                                            item.kind, item.label, item.lat, item.lon, item.url
                                        )
                                        area = null
                                    }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    AreaReport.fmtKm(item.distanceKm),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Intel.TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(58.dp)
                                )
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Intel.TextHi,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (group.total > group.items.size) {
                            Text(
                                "…and ${group.total - group.items.size} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = Intel.TextLow
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onMapClick(report.lat, report.lon)
                    area = null
                }) {
                    Text(
                        "INVESTIGATE POINT",
                        style = MaterialTheme.typography.labelMedium,
                        color = Intel.Cyan
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { area = null }) {
                    Text("CLOSE", style = MaterialTheme.typography.labelMedium, color = Intel.TextMid)
                }
            }
        )
    }

    inspected?.let { obj ->
        AlertDialog(
            onDismissRequest = { inspected = null },
            containerColor = Intel.Surface,
            titleContentColor = Intel.TextHi,
            textContentColor = Intel.TextMid,
            title = {
                Text(
                    obj.kind.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Intel.Cyan
                )
            },
            text = {
                Column {
                    Text(
                        obj.label, style = MaterialTheme.typography.bodyMedium,
                        color = Intel.CyanBright, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("LAT/LON", style = MaterialTheme.typography.labelSmall, color = Intel.TextLow)
                    Text(
                        "%.5f, %.5f".format(obj.lat, obj.lon),
                        style = MaterialTheme.typography.bodySmall,
                        color = Intel.TextHi, fontFamily = FontFamily.Monospace
                    )
                    com.osint.unified.domain.Mgrs.from(obj.lat, obj.lon)?.let { grid ->
                        Spacer(Modifier.size(6.dp))
                        Text("MGRS", style = MaterialTheme.typography.labelSmall, color = Intel.TextLow)
                        Text(
                            grid, style = MaterialTheme.typography.bodySmall,
                            color = Intel.TextHi, fontFamily = FontFamily.Monospace
                        )
                    }
                    // What INVESTIGATE will actually search for, so it is never
                    // a surprise that a decorative label got sent to the tools.
                    val query = investigableQuery(obj)
                    if (query != null) {
                        Spacer(Modifier.size(8.dp))
                        Text("INVESTIGATE AS", style = MaterialTheme.typography.labelSmall, color = Intel.TextLow)
                        Text(
                            query, style = MaterialTheme.typography.bodySmall,
                            color = Intel.Amber, fontFamily = FontFamily.Monospace
                        )
                    }
                    obj.url?.let { link ->
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "OPEN SOURCE ▸",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.Cyan,
                            modifier = Modifier.clickable {
                                com.osint.unified.ui.common.LinkOpener.open(context, link)
                            }
                        )
                        Text(
                            link.take(90), style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow
                        )
                    }
                }
            },
            confirmButton = {
                val query = investigableQuery(obj)
                TextButton(
                    enabled = query != null,
                    onClick = {
                        query?.let { viewModel.onQueryChange(it); viewModel.onSearch() }
                        inspected = null
                    }
                ) {
                    Text(
                        "INVESTIGATE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (query != null) Intel.Cyan else Intel.TextLow
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onMapClick(obj.lat, obj.lon); inspected = null
                }) { Text("LOCATION", style = MaterialTheme.typography.labelMedium, color = Intel.TextMid) }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            backendUrl = state.backendUrl,
            tar1090Url = state.tar1090Url,
            aisUrl = state.aisUrl,
            overlayUrl = state.overlayUrl,
            onSave = { backend, tar, ais, overlay ->
                viewModel.saveSettings(backend, tar, ais, overlay)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * Native OpenStreetMap globe via osmdroid. No WebView and no WebGL — tiles
 * are drawn directly by Android, which renders reliably where the embedded
 * web map libraries did not. Tap the map to investigate a coordinate, tap a
 * marker to investigate that aircraft or vessel, and long-press anywhere for
 * everything every loaded layer holds around that point.
 */
@Composable
private fun GlobeMap(
    aircraft: List<Aircraft>,
    vessels: List<Vessel>,
    quakes: List<Quake>,
    events: List<NaturalEvent>,
    overlay: List<OverlayPoint>,
    weather: List<GeoBlip>,
    fires: List<GeoBlip>,
    satellites: List<GeoBlip>,
    news: List<GeoBlip>,
    gdacs: List<GeoBlip>,
    volcanoes: List<GeoBlip>,
    aurora: List<GeoBlip>,
    buoys: List<GeoBlip>,
    metar: List<GeoBlip>,
    milAircraft: List<GeoBlip>,
    conflicts: List<GeoBlip>,
    airQuality: List<GeoBlip>,
    stations: List<GeoBlip>,
    airports: List<GeoBlip>,
    powerPlants: List<GeoBlip>,
    cameras: List<GeoBlip>,
    airspace: List<GeoBlip>,
    ladd: List<GeoBlip>,
    cables: List<GeoBlip>,
    cyclones: List<GeoBlip>,
    tsunamis: List<GeoBlip>,
    airportStatus: List<GeoBlip>,
    radiation: List<GeoBlip>,
    findings: List<FindingPin>,
    focus: Pair<Double, Double>?,
    onInspect: (Inspected) -> Unit,
    onViewport: (Double, Double, Double) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    onAreaPress: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val appliedFocus = remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // The visible area, updated as the map is panned or zoomed. Markers are
    // culled to it so a layer can hold tens of thousands of points without
    // the map trying to draw them all.
    var viewport by remember { mutableStateOf<BoundingBox?>(null) }
    // Cache marker dot drawables by colour+size so a rebuild of hundreds of
    // markers doesn't allocate a fresh ShapeDrawable each time.
    val dotCache = remember { HashMap<Long, Drawable>() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // osmdroid refuses to fetch tiles without a user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                // Esri World Imagery: keyless satellite basemap (no token), and
                // its tile path is z/y/x with no extension, so build it directly.
                setTileSource(object : OnlineTileSourceBase(
                    "EsriWorldImagery", 0, 19, 256, "",
                    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
                ) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        val z = MapTileIndex.getZoom(pMapTileIndex)
                        val x = MapTileIndex.getX(pMapTileIndex)
                        val y = MapTileIndex.getY(pMapTileIndex)
                        return "$baseUrl$z/$y/$x"
                    }
                })
                setMultiTouchControls(true)
                setBackgroundColor(0xFF0B0E11.toInt())
                controller.setZoom(2.5)
                controller.setCenter(GeoPoint(20.0, 0.0))
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onMapTap(p.latitude, p.longitude)
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean {
                        onAreaPress(p.latitude, p.longitude, zoomLevelDouble)
                        return true
                    }
                }
                overlays.add(MapEventsOverlay(receiver))
                // Debounced so a fling triggers one rebuild, not fifty.
                addMapListener(
                    DelayedMapListener(
                        object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                viewport = boundingBox
                                onViewport(mapCenter.latitude, mapCenter.longitude, zoomLevelDouble)
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                viewport = boundingBox
                                onViewport(mapCenter.latitude, mapCenter.longitude, zoomLevelDouble)
                                return false
                            }
                        },
                        250L
                    )
                )
            }
        },
        update = { map -> runCatching {
            // Rebuild only the markers; keep the tap-events overlay in place.
            map.overlays.removeAll { it is Marker }
            val density = map.resources.displayMetrics.density
            val box = viewport ?: map.boundingBox
            // Collected first, then culled to the viewport and thinned to a
            // budget, so a layer holding tens of thousands of points costs
            // only what is actually on screen.
            val pending = ArrayList<PendingMarker>(2048)
            fun add(
                kind: String,
                lat: Double,
                lon: Double,
                color: Int,
                sizeDp: Int,
                title: String?,
                clickable: Boolean,
                url: String? = null
            ) {
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || lat.isNaN() || lon.isNaN()) return
                if (box != null && !inBox(box, lat, lon)) return
                pending.add(PendingMarker(kind, lat, lon, color, sizeDp, title, clickable, url))
            }
            fun flush() {
                // Thinning has to be fair between layers. The satellite
                // catalogue alone is twelve thousand objects; taking markers
                // in the order layers happen to be added would let it eat the
                // whole budget and push aircraft, vessels and everything else
                // off the map entirely.
                //
                // So each layer present gets an equal share, any layer using
                // less than its share hands the remainder back, and layers
                // over their share are sampled with a stride so what survives
                // stays spread across the view instead of clumping.
                val keep = if (pending.size <= MARKER_BUDGET) pending else {
                    val byKind = pending.groupBy { it.kind }
                    val quotas = HashMap<String, Int>()
                    var remaining = MARKER_BUDGET
                    var contenders = byKind.keys.toMutableSet()
                    while (contenders.isNotEmpty() && remaining > 0) {
                        val share = (remaining / contenders.size).coerceAtLeast(1)
                        val satisfied = contenders.filter { (byKind[it]?.size ?: 0) <= share }
                        if (satisfied.isEmpty()) {
                            contenders.forEach { quotas[it] = share }
                            remaining -= share * contenders.size
                            break
                        }
                        satisfied.forEach { kind ->
                            val n = byKind[kind]?.size ?: 0
                            quotas[kind] = n
                            remaining -= n
                        }
                        contenders = (contenders - satisfied.toSet()).toMutableSet()
                    }
                    byKind.flatMap { (kind, markers) ->
                        val quota = quotas[kind] ?: 0
                        when {
                            quota <= 0 -> emptyList()
                            markers.size <= quota -> markers
                            else -> {
                                val stride = (markers.size / quota).coerceAtLeast(1)
                                markers.filterIndexed { i, _ -> i % stride == 0 }.take(quota)
                            }
                        }
                    }
                }
                keep.forEach { pm ->
                    val px = (pm.sizeDp * density).toInt()
                    val key = (pm.color.toLong() shl 8) xor px.toLong()
                    val icon = dotCache.getOrPut(key) { dot(pm.color, px) }
                    val m = Marker(map)
                    m.position = GeoPoint(pm.lat, pm.lon)
                    m.icon = icon
                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    m.title = pm.title
                    // Layers marked non-clickable are fields of readings —
                    // an aurora cell, an AQI blob. Opening an inspector on
                    // one produces a dialog whose only content is a disabled
                    // INVESTIGATE button, which is worse than no dialog.
                    if (pm.clickable) {
                        m.setOnMarkerClickListener { _, _ ->
                            onInspect(
                                Inspected(
                                    pm.kind,
                                    pm.title.orEmpty().ifBlank { pm.kind },
                                    pm.lat,
                                    pm.lon,
                                    pm.url
                                )
                            )
                            true
                        }
                    }
                    map.overlays.add(m)
                }
            }
            quakes.forEach { add("Earthquake", it.lat, it.lon, 0xFFF0997B.toInt(), 8, "M%.1f".format(it.mag), true) }
            events.forEach { add("Natural event", it.lat, it.lon, 0xFFF2C94C.toInt(), 9, "${it.category}: ${it.title}", true) }
            news.forEach { add("News hotspot", it.lat, it.lon, 0xFFFF79C6.toInt(), 7, it.label, true, it.url) }
            aurora.forEach { add("Aurora forecast", it.lat, it.lon, 0xFF66FFB2.toInt(), 6, it.label, false, it.url) }
            stations.forEach { add("Seismic station", it.lat, it.lon, 0xFF9AB0C0.toInt(), 5, it.label, false, it.url) }
            airports.forEach { add("Airport", it.lat, it.lon, 0xFF8FD3FF.toInt(), 6, it.label, true, it.url) }
            powerPlants.forEach { add("Power plant", it.lat, it.lon, 0xFFFFC14D.toInt(), 6, it.label, true, it.url) }
            cameras.forEach { add("Traffic camera", it.lat, it.lon, 0xFFB388FF.toInt(), 6, it.label, true, it.url) }
            airspace.forEach { add("Restricted airspace", it.lat, it.lon, 0xFFFF5370.toInt(), 7, it.label, true, it.url) }
            ladd.forEach { add("Hidden-owner aircraft", it.lat, it.lon, 0xFFFFD166.toInt(), 7, it.label, true, it.url) }
            cables.forEach { add("Cable landing", it.lat, it.lon, 0xFF7FFFD4.toInt(), 5, it.label, true, it.url) }
            airQuality.forEach { add("Air quality", it.lat, it.lon, 0xFFB4E66B.toInt(), 9, it.label, false, it.url) }
            buoys.forEach { add("Ocean buoy", it.lat, it.lon, 0xFF4DD0E1.toInt(), 6, it.label, true, it.url) }
            metar.forEach { add("Airfield weather", it.lat, it.lon, 0xFF9FE8FF.toInt(), 6, it.label, false, it.url) }
            conflicts.forEach { add("Conflict", it.lat, it.lon, 0xFFFF2E2E.toInt(), 11, it.label, true, it.url) }
            gdacs.forEach { add("Disaster alert", it.lat, it.lon, 0xFFFF8A3D.toInt(), 10, it.label, true, it.url) }
            volcanoes.forEach { add("Volcano", it.lat, it.lon, 0xFFFF4D4D.toInt(), 9, it.label, true, it.url) }
            weather.forEach { add("Weather alert", it.lat, it.lon, 0xFF7FB2FF.toInt(), 9, it.label, true, it.url) }
            fires.forEach { add("Wildfire", it.lat, it.lon, 0xFFFF5A4D.toInt(), 8, it.label, true, it.url) }
            overlay.forEach { add("Custom overlay", it.lat, it.lon, 0xFF5CE1D6.toInt(), 9, it.label, false) }
            radiation.forEach { add("Radiation", it.lat, it.lon, 0xFFD4FF4D.toInt(), 7, it.label, false, it.url) }
            airportStatus.forEach { add("Airport status", it.lat, it.lon, 0xFFFFA64D.toInt(), 10, it.label, true, it.url) }
            cyclones.forEach { add("Tropical cyclone", it.lat, it.lon, 0xFFFF7AC6.toInt(), 14, it.label, true, it.url) }
            tsunamis.forEach { add("Tsunami bulletin", it.lat, it.lon, 0xFF4DF0FF.toInt(), 13, it.label, true, it.url) }
            satellites.forEach { add("Satellite", it.lat, it.lon, 0xFFC0A8FF.toInt(), 11, it.label, true, it.url) }
            vessels.forEach { add("Vessel", it.lat, it.lon, 0xFF1D9E75.toInt(), 9, it.name.ifBlank { it.mmsi }, true) }
            aircraft.forEach { add("Aircraft", it.lat, it.lon, 0xFF4FC3F7.toInt(), 9, it.callsign.ifBlank { it.icao }, true) }
            milAircraft.forEach { add("Military aircraft", it.lat, it.lon, 0xFFFFD23F.toInt(), 10, it.label, true, it.url) }
            // Investigation findings sit on top, larger and brighter.
            findings.forEach { add("Finding", it.lat, it.lon, 0xFFE24FD6.toInt(), 14, it.label, true) }
            flush()

            // Fly to a newly-focused location (coordinate search or first geo finding).
            if (focus != null && focus != appliedFocus.value) {
                map.controller.animateTo(GeoPoint(focus.first, focus.second))
                map.controller.setZoom(9.0)
                appliedFocus.value = focus
            }
            map.invalidate()
        } }
    )
}

/**
 * Turns a marker's display label into something worth searching.
 *
 * Labels are built for reading — "GHOST52 · GLF4", "CAM Hwy 5 at Pocket ·
 * Sacramento" — and feeding one verbatim to the tools produces a dossier
 * full of "no entity named 'GHOST52 · GLF4'". The useful identifier is the
 * first segment, and only for layers where an identifier means something.
 * Everything else investigates by position instead.
 */
private fun investigableQuery(obj: Inspected): String? {
    val head = obj.label.substringBefore(" · ").trim()
    return when (obj.kind) {
        "Aircraft", "Military aircraft", "Hidden-owner aircraft" ->
            head.removePrefix("LADD ").trim().takeIf { it.isNotBlank() }
        "Vessel" -> head.takeIf { it.isNotBlank() }
        "Satellite" -> head.takeIf { it.isNotBlank() }
        "Airport" -> head.removePrefix("APT ").trim().takeIf { it.isNotBlank() }
        "Finding" -> obj.label.takeIf { it.isNotBlank() }
        // A camera, a cable landing or a weather alert is a place, not a
        // name — searching its caption finds nothing, so these fall through
        // to LOCATION instead of offering a search that cannot work.
        else -> null
    }
}

/** The most markers drawn at once. Layers may hold far more than this;
 *  what is off screen costs nothing, and what is left is thinned evenly. */
private const val MARKER_BUDGET = 2500

private data class PendingMarker(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val color: Int,
    val sizeDp: Int,
    val title: String?,
    val clickable: Boolean,
    val url: String? = null
)

/** What the object inspector is showing: which layer the marker came from,
 *  its label, where it is, and any link that layer carries. */
data class Inspected(
    val kind: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val url: String? = null
)

/** Viewport test that tolerates a map scrolled past the antimeridian, where
 *  the west edge is numerically greater than the east. */
private fun inBox(box: BoundingBox, lat: Double, lon: Double): Boolean {
    if (lat > box.latNorth || lat < box.latSouth) return false
    val west = box.lonWest
    val east = box.lonEast
    return if (west <= east) lon in west..east else lon >= west || lon <= east
}

/** Filters aircraft by callsign pattern: cargo carriers, military-style
 *  callsigns, or civil passenger traffic. */
private fun filterFlights(list: List<Aircraft>, filter: String): List<Aircraft> {
    if (filter == "ALL") return list
    val cargo = listOf("FDX", "UPS", "GTI", "CLX", "CKS", "ABX", "ATN", "BOX", "MPH", "QAC")
    val mil = listOf("RCH", "CNV", "PAT", "SPAR", "REACH", "TITAN", "DOOM", "BAMA", "NAVY", "ARMY", "EVAC")
    return list.filter { a ->
        val cs = a.callsign.uppercase().trim()
        when (filter) {
            "CARGO" -> cargo.any { cs.startsWith(it) }
            "MIL" -> mil.any { cs.startsWith(it) }
            "CIVIL" -> cs.isNotBlank() && cargo.none { cs.startsWith(it) } && mil.none { cs.startsWith(it) }
            else -> true
        }
    }
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
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Intel.Cyan else Intel.Surface)
            .border(1.dp, if (selected) Intel.Cyan else Intel.Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Intel.Base else Intel.TextMid
        )
    }
}

@Composable
private fun ExamplePill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Intel.Surface)
            .border(1.dp, Intel.Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Intel.TextMid)
    }
}

@Composable
private fun LayerPill(
    label: String,
    count: Int,
    color: Color,
    on: Boolean,
    /** Layers that only fetch when switched on. Until then they have no
     *  count, and showing "0" would read as "this layer is broken". */
    loadsOnDemand: Boolean = false,
    /** False until the feed has completed its first fetch. A layer that has
     *  not run yet is not the same as one that ran and found nothing. */
    loaded: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Intel.Elevated else Intel.Surface)
            .border(1.dp, if (on) color else Intel.Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (on) color else Intel.TextLow))
        Spacer(Modifier.width(6.dp))
        val shown = when {
            count > 0 -> "$count"
            loadsOnDemand && !on -> "tap"
            // A fetch that has answered with nothing says "0"; only one
            // still in flight says "…". These used to be the same, so a
            // layer whose fetch failed sat on the loading marker for ever.
            !loaded -> "…"
            else -> "0"
        }
        Text(
            "$label $shown",
            style = MaterialTheme.typography.labelSmall,
            color = if (on) Intel.TextHi else Intel.TextLow
        )
    }
}

@Composable
private fun SettingsDialog(
    backendUrl: String,
    tar1090Url: String,
    aisUrl: String,
    overlayUrl: String,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var backend by remember { mutableStateOf(backendUrl) }
    var tar by remember { mutableStateOf(tar1090Url) }
    var ais by remember { mutableStateOf(aisUrl) }
    var overlay by remember { mutableStateOf(overlayUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Intel.Surface,
        titleContentColor = Intel.TextHi,
        textContentColor = Intel.TextMid,
        title = { Text("LIVE FEEDS", style = MaterialTheme.typography.titleSmall, color = Intel.TextHi) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FeedNote("Aircraft are live worldwide via OpenSky (keyless). Earthquakes and events are always live. Everything below is optional.")

                FeedHeader("AIRCRAFT  ·  override")
                SettingsField(tar, { tar = it }, "tar1090 feed URL", "http://host:8080/data/aircraft.json")

                FeedHeader("VESSELS  ·  override (else demo)")
                SettingsField(ais, { ais = it }, "AIS vessel feed URL", "https://host/vessels.json")

                FeedHeader("CUSTOM OVERLAY  ·  any GeoJSON points")
                SettingsField(overlay, { overlay = it }, "GeoJSON points URL", "https://host/points.geojson")

                FeedHeader("CLOUD BACKEND  ·  optional tools")
                SettingsField(backend, { backend = it }, "Backend URL", "https://your-api.onrender.com")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(backend, tar, ais, overlay) }) {
                Text("SAVE", style = MaterialTheme.typography.labelMedium, color = Intel.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", style = MaterialTheme.typography.labelMedium, color = Intel.TextMid)
            }
        }
    )
}

@Composable
private fun FeedNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Intel.TextLow,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun FeedHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Intel.Cyan,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    top: androidx.compose.ui.unit.Dp = 0.dp
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Intel.Cyan,
            unfocusedBorderColor = Intel.Hairline,
            cursorColor = Intel.Cyan,
            focusedLabelColor = Intel.Cyan,
            unfocusedLabelColor = Intel.TextMid,
            focusedTextColor = Intel.TextHi,
            unfocusedTextColor = Intel.TextHi,
            focusedPlaceholderColor = Intel.TextLow,
            unfocusedPlaceholderColor = Intel.TextLow
        ),
        modifier = Modifier.fillMaxWidth().padding(top = top)
    )
}
