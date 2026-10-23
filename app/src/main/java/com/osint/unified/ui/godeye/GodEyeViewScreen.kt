package com.osint.unified.ui.godeye

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.Locale

private val EXAMPLES = listOf("example.com", "8.8.8.8", "40.7128,-74.0060", "+14155550123", "John Smith")

/**
 * Display modes: tactical filters over the imagery.
 *
 * GOD is not one of these and used to be. Cycling past CRT quietly
 * turned on every layer there is — downloading eight thousand airspace
 * polygons, a camera sweep and a radiation query — because somebody was
 * trying tinted overlays. A colour filter and "fetch everything" are not
 * the same kind of choice and no longer share a button.
 */
private enum class VisualMode(val label: String) {
    DEFAULT("DEFAULT"), NVG("NVG"), FLIR("FLIR"), CRT("CRT")
}

private val SEARCH_TYPES = listOf(
    "auto" to "AUTO", "name" to "NAME", "email" to "EMAIL", "username" to "USER",
    "domain" to "DOMAIN", "ip" to "IP", "phone" to "PHONE", "coordinate" to "COORD", "image" to "IMAGE"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GodEyeViewScreen(
    viewModel: GodEyeViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAircraft by rememberSaveable { mutableStateOf(true) }
    var showVessels by rememberSaveable { mutableStateOf(true) }
    var showQuakes by rememberSaveable { mutableStateOf(true) }
    var showEvents by rememberSaveable { mutableStateOf(true) }
    var showOverlay by rememberSaveable { mutableStateOf(true) }
    var showWeather by rememberSaveable { mutableStateOf(true) }
    var showFires by rememberSaveable { mutableStateOf(true) }
    var showSats by rememberSaveable { mutableStateOf(true) }
    var showNews by rememberSaveable { mutableStateOf(false) }
    var showGdacs by rememberSaveable { mutableStateOf(true) }
    var showVolcanoes by rememberSaveable { mutableStateOf(true) }
    var showWorldWarnings by rememberSaveable { mutableStateOf(true) }
    var showSeaLevel by rememberSaveable { mutableStateOf(false) }
    var showPorts by rememberSaveable { mutableStateOf(false) }
    var showSites by rememberSaveable { mutableStateOf(false) }
    var showLaunches by rememberSaveable { mutableStateOf(true) }
    var showOutages by rememberSaveable { mutableStateOf(true) }
    var showAurora by rememberSaveable { mutableStateOf(true) }
    var showBuoys by rememberSaveable { mutableStateOf(false) }
    var showMetar by rememberSaveable { mutableStateOf(false) }
    var showMil by rememberSaveable { mutableStateOf(true) }
    var showConflict by rememberSaveable { mutableStateOf(true) }
    var showCyclones by rememberSaveable { mutableStateOf(true) }
    var showTsunami by rememberSaveable { mutableStateOf(true) }
    var showAirportStatus by rememberSaveable { mutableStateOf(true) }
    var showRadiation by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(VisualMode.DEFAULT) }
    // Everything on at once, which costs real bandwidth, so it is its own
    // deliberate switch rather than the fifth stop on a colour cycle.
    var god by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var inspected by remember { mutableStateOf<Inspected?>(null) }
    // What a long press on the globe turned up: every loaded layer, swept
    // for a radius around that point.
    var area by remember { mutableStateOf<AreaReport.Report?>(null) }
    // Where the map was last tapped, offered as a choice rather than acted on.
    var tapped by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var zoomNow by remember { mutableDoubleStateOf(2.5) }
    // The world brief is derived from the same state the globe draws, so it
    // is recomputed rather than fetched, and is never out of step with it.
    var showBrief by rememberSaveable { mutableStateOf(false) }
    // Keyed on the layers the brief reads, not the whole state: the
    // aircraft poll replaces the state object every fifteen seconds, and
    // rebuilding the ranking each time would be work for no new answer.
    val brief = remember(
        state.conflicts, state.gdacs, state.tsunamis, state.cyclones,
        state.volcanoes, state.worldWarnings, state.seaLevel, state.outages,
        state.ports, state.launches, state.sites,
        state.quakes, state.weather, state.fires,
        state.milAircraft, state.airportStatus, state.news,
        state.spaceWeather, state.loaded,
        // The traffic line reads these three, so leaving them out of the key
        // let the brief quote counts the header beside it had moved past.
        state.aircraft, state.vessels, state.satellites
    ) { WorldBrief.build(state) }
    // The panel of search controls and layer pills is most of a phone
    // screen. On something called a God's Eye View the globe should be the
    // thing you are looking at, so the panel folds away — and starts
    // folded. Twenty-eight layer buttons, each 48 dp tall for the sake of
    // thumbs, plus a search box and three hint lines, is more than a 360 dp
    // phone has: the front door was a wall of buttons with a sliver of map
    // underneath.
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    // A thing called a God's Eye View should be a globe. Mercator is the
    // right tool for looking at a street and the wrong one for looking at
    // the Earth, so the sphere is what opens, and the flat map is one tap
    // away for the close work it is actually good at.
    var roundEarth by rememberSaveable { mutableStateOf(true) }
    // One camera for both views. The globe and the map are the same
    // view at different distances, so turning the Earth to a place and
    // pinching in has to arrive there rather than at a default.
    var globeCamera by rememberSaveable(
        saver = listSaver(
            save = { listOf(it.value.lat, it.value.lon, it.value.zoom) },
            restore = {
                mutableStateOf(
                    GlobeCamera(
                        (it.getOrNull(0) as? Float) ?: 20f,
                        (it.getOrNull(1) as? Float) ?: 0f,
                        (it.getOrNull(2) as? Float) ?: 1f
                    )
                )
            }
        )
    ) { mutableStateOf(GlobeCamera(20f, 0f, 1f)) }
    var globeSize by remember { mutableStateOf(0 to 0) }
    // Where the map should jump to when the globe hands over, and the
    // other way about. Not the search focus: that is a different kind
    // of move and the map already has one.
    var handoff by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    var flightFilter by rememberSaveable { mutableStateOf("ALL") }
    // Filtered once per change, not twice per recomposition over 11,000
    // aircraft — the pill and the map now also read the same list.
    val visibleAircraft = remember(state.aircraft, flightFilter) {
        filterFlights(state.aircraft, flightFilter)
    }
    var showCameras by rememberSaveable { mutableStateOf(false) }
    var showAirspace by rememberSaveable { mutableStateOf(false) }
    var showAirports by rememberSaveable { mutableStateOf(false) }
    var showAqi by rememberSaveable { mutableStateOf(false) }
    var showCables by rememberSaveable { mutableStateOf(false) }
    var showPower by rememberSaveable { mutableStateOf(false) }
    var showStations by rememberSaveable { mutableStateOf(false) }

    // Which feeds are worth spending someone's battery and mobile data on.
    //
    // Every one of the twenty-six polling loops ran for ever: in the
    // background, off screen, and for layers switched off. METAR alone is
    // twenty-four requests every ten minutes, and the aircraft sweep at
    // world zoom is measured in hundreds of megabytes an hour. A feed
    // nobody is looking at should not be fetched.
    val visibleLayers = remember(
        showAircraft,
        showAirportStatus,
        showAirports,
        showAqi,
        showAurora,
        showBuoys,
        showCables,
        showConflict,
        showCyclones,
        showEvents,
        showFires,
        showGdacs,
        showMetar,
        showMil,
        showNews,
        showOverlay,
        showPower,
        showQuakes,
        showSats,
        showStations,
        showTsunami,
        showVessels,
        showVolcanoes,
        showWorldWarnings,
        showSeaLevel,
        showPorts,
        showSites,
        showLaunches,
        showOutages,
        showWeather,
        showCameras, showAirspace, showRadiation, god
    ) {
        buildSet {
            if (showAircraft || god) add("aircraft")
            if (showMil || god) add("milAircraft")
            if (showVessels || god) add("vessels")
            if (showQuakes || god) add("quakes")
            if (showEvents || god) add("naturalEvents")
            if (showWeather || god) add("weather")
            if (showFires || god) add("fires")
            if (showSats || god) add("satellites")
            if (showGdacs || god) add("gdacs")
            if (showVolcanoes || god) add("volcanoes")
            if (showWorldWarnings || god) add("worldWarnings")
            if (showSeaLevel || god) add("seaLevel")
            if (showPorts || god) add("ports")
            if (showSites || god) add("sites")
            if (showLaunches || god) add("launches")
            if (showOutages || god) add("outages")
            if (showConflict || god) add("conflicts")
            if (showAqi || god) add("airQuality")
            if (showStations || god) add("stations")
            if (showAirports || god) add("airports")
            if (showPower || god) add("powerPlants")
            if (showAurora || god) add("aurora")
            if (showBuoys || god) add("buoys")
            if (showMetar || god) add("metar")
            if (showNews || god) add("news")
            if (showCables || god) add("cables")
            if (showCyclones || god) add("cyclones")
            if (showTsunami || god) add("tsunamis")
            if (showAirportStatus || god) add("airportStatus")
            if (showOverlay || god) add("overlayPoints")
            if (showCameras || god) add("cameras")
            if (showAirspace || god) add("airspace")
            if (showRadiation || god) add("radiation")
            add("spaceWeather")
        }
    }
    LaunchedEffect(visibleLayers) { viewModel.setVisibleLayers(visibleLayers) }

    // GOD MODE forces every layer visible.
    fun <T> vis(on: Boolean, data: List<T>): List<T> = if (on || god) data else emptyList()


    Scaffold(containerColor = Intel.Base) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            // Classification banner (tactical console convention; green = UNCLASS).

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
                        UtcClock()
                    }
                    // One line, truncated rather than wrapped — the counts
                    // used to wrap over three lines and push the map down.
                    Text(
                        "AIR ${state.aircraft.size} · SEA ${state.vessels.size} · " +
                            "SAT ${state.satellites.size} · SEIS ${state.quakes.size}" +
                            (state.spaceWeather?.kp?.let { " · Kp %.1f".format(Locale.US, it) } ?: ""),
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
                Text(
                    if (roundEarth) "GLOBE" else "MAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Cyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Intel.Elevated)
                        .border(1.dp, Intel.Cyan, RoundedCornerShape(50))
                        .clickable {
                            // The toggle carries the camera too. A
                            // person who presses MAP wants the map of
                            // where they are looking, not of wherever
                            // the map happened to be left.
                            val (w, h) = globeSize
                            if (roundEarth) {
                                if (w > 0) {
                                    val span = GlobeHandoff.spanDegrees(w, h, globeCamera.zoom)
                                    handoff = Triple(
                                        globeCamera.lat.toDouble(),
                                        globeCamera.lon.toDouble(),
                                        GlobeHandoff.tileZoom(span, w)
                                    )
                                }
                            } else if (w > 0 && zoomNow > 0.0) {
                                globeCamera = globeCamera.copy(
                                    zoom = GlobeHandoff.globeZoom(
                                        GlobeHandoff.mapSpanDegrees(zoomNow, w), w, h
                                    )
                                )
                            }
                            roundEarth = !roundEarth
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                // The other five destinations moved to the bar along the
                // bottom, where they have names. Settings stays here: it
                // adjusts this screen.
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Intel.TextMid)
                }
            }

            // However much the panel wants, it gets at most this. Before,
            // it was laid out at whatever height its contents came to and
            // the map took what was left — which on a small screen was
            // nothing at all.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PANEL_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
            ) {
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
                LayerPill("AIR·GLOBAL", visibleAircraft.size, Intel.NetCyan, showAircraft || god, loaded = "aircraft" in state.loaded, trouble = "aircraft" in state.feedTrouble) { showAircraft = !showAircraft }
                LayerPill("MIL", state.milAircraft.size, Color(0xFFFFD23F), showMil || god, loaded = "milAircraft" in state.loaded, trouble = "milAircraft" in state.feedTrouble) { showMil = !showMil }
                LayerPill("SEA·N.EUROPE", state.vessels.size, Intel.Teal, showVessels || god, loaded = "vessels" in state.loaded, trouble = "vessels" in state.feedTrouble) { showVessels = !showVessels }
                LayerPill("SEIS", state.quakes.size, Intel.Coral, showQuakes || god, loaded = "quakes" in state.loaded, trouble = "quakes" in state.feedTrouble) { showQuakes = !showQuakes }
                LayerPill("EVT", state.naturalEvents.size, Intel.Amber, showEvents || god, loaded = "naturalEvents" in state.loaded, trouble = "naturalEvents" in state.feedTrouble) { showEvents = !showEvents }
                LayerPill("WX·US", state.weather.size, Color(0xFF7FB2FF), showWeather || god, loaded = "weather" in state.loaded, trouble = "weather" in state.feedTrouble) { showWeather = !showWeather }
                LayerPill("FIRE·GLOBAL", state.fires.size, Color(0xFFFF5A4D), showFires || god, loaded = "fires" in state.loaded, trouble = "fires" in state.feedTrouble) { showFires = !showFires }
                LayerPill("SAT", state.satellites.size, Color(0xFFC0A8FF), showSats || god, loaded = "satellites" in state.loaded, trouble = "satellites" in state.feedTrouble) { showSats = !showSats }
                LayerPill("HAZARD", state.gdacs.size, Color(0xFFFF8A3D), showGdacs || god, loaded = "gdacs" in state.loaded, trouble = "gdacs" in state.feedTrouble) { showGdacs = !showGdacs }
                LayerPill("OUTAGE·GLOBAL", state.outages.size, Color(0xFFFF6B6B), showOutages || god, loaded = "outages" in state.loaded, trouble = "outages" in state.feedTrouble) { showOutages = !showOutages }
                LayerPill("LAUNCH·GLOBAL", state.launches.size, Color(0xFFFFF066), showLaunches || god, loaded = "launches" in state.loaded, trouble = "launches" in state.feedTrouble) { showLaunches = !showLaunches }
                LayerPill(
                    "SITES", state.sites.size, Color(0xFFD98BFF), showSites || god,
                    loadsOnDemand = true, loaded = "sites" in state.loaded,
                    trouble = "sites" in state.feedTrouble
                ) {
                    showSites = !showSites
                    if (showSites) viewModel.ensureSites()
                }
                LayerPill("PORTS·GLOBAL", state.ports.size, Color(0xFF7FD4FF), showPorts || god, loaded = "ports" in state.loaded, trouble = "ports" in state.feedTrouble) { showPorts = !showPorts }
                LayerPill("TIDE·GLOBAL", state.seaLevel.size, Color(0xFF4DD0E1), showSeaLevel || god, loaded = "seaLevel" in state.loaded, trouble = "seaLevel" in state.feedTrouble) { showSeaLevel = !showSeaLevel }
                LayerPill("WARN·GLOBAL", state.worldWarnings.size, Color(0xFFFFD166), showWorldWarnings || god, loaded = "worldWarnings" in state.loaded, trouble = "worldWarnings" in state.feedTrouble) { showWorldWarnings = !showWorldWarnings }
                LayerPill("VOLC·GLOBAL", state.volcanoes.size, Color(0xFFFF4D4D), showVolcanoes || god, loaded = "volcanoes" in state.loaded, trouble = "volcanoes" in state.feedTrouble) { showVolcanoes = !showVolcanoes }
                LayerPill("CONFLICT", state.conflicts.size, Color(0xFFFF2E2E), showConflict || god, loaded = "conflicts" in state.loaded, trouble = "conflicts" in state.feedTrouble) { showConflict = !showConflict }
                LayerPill("AQI", state.airQuality.size, Color(0xFFB4E66B), showAqi || god, loaded = "airQuality" in state.loaded, trouble = "airQuality" in state.feedTrouble) { showAqi = !showAqi }
                LayerPill("STATIONS", state.stations.size, Color(0xFF9AB0C0), showStations || god, loaded = "stations" in state.loaded, trouble = "stations" in state.feedTrouble) { showStations = !showStations }
                LayerPill("APT", state.airports.size, Color(0xFF8FD3FF), showAirports || god, loaded = "airports" in state.loaded, trouble = "airports" in state.feedTrouble) { showAirports = !showAirports }
                LayerPill("POWER", state.powerPlants.size, Color(0xFFFFC14D), showPower || god, loaded = "powerPlants" in state.loaded, trouble = "powerPlants" in state.feedTrouble) { showPower = !showPower }
                LayerPill("AURORA", state.aurora.size, Color(0xFF66FFB2), showAurora || god, loaded = "aurora" in state.loaded, trouble = "aurora" in state.feedTrouble) { showAurora = !showAurora }
                LayerPill("BUOY", state.buoys.size, Color(0xFF4DD0E1), showBuoys || god, loaded = "buoys" in state.loaded, trouble = "buoys" in state.feedTrouble) { showBuoys = !showBuoys }
                LayerPill("METAR", state.metar.size, Color(0xFF9FE8FF), showMetar || god, loaded = "metar" in state.loaded, trouble = "metar" in state.feedTrouble) { showMetar = !showMetar }
                LayerPill("NEWS", state.news.size, Color(0xFFFF79C6), showNews || god, loaded = "news" in state.loaded, trouble = "news" in state.feedTrouble) { showNews = !showNews }
                LayerPill(
                    "CAMERAS", state.cameras.size, Color(0xFFB388FF), showCameras || god,
                    loadsOnDemand = true, loaded = "cameras" in state.loaded,
                    trouble = "cameras" in state.feedTrouble
                ) {
                    showCameras = !showCameras
                    if (showCameras) viewModel.ensureCameras()
                }
                LayerPill("CABLE", state.cables.size, Color(0xFF7FFFD4), showCables || god, loaded = "cables" in state.loaded, trouble = "cables" in state.feedTrouble) { showCables = !showCables }
                LayerPill("CYCLONE·GLOBAL", state.cyclones.size, Color(0xFFFF7AC6), showCyclones || god, loaded = "cyclones" in state.loaded, trouble = "cyclones" in state.feedTrouble) { showCyclones = !showCyclones }
                LayerPill("TSUNAMI·PAC", state.tsunamis.size, Color(0xFF4DF0FF), showTsunami || god, loaded = "tsunamis" in state.loaded, trouble = "tsunamis" in state.feedTrouble) { showTsunami = !showTsunami }
                LayerPill("ATC·US", state.airportStatus.size, Color(0xFFFFA64D), showAirportStatus || god, loaded = "airportStatus" in state.loaded, trouble = "airportStatus" in state.feedTrouble) { showAirportStatus = !showAirportStatus }
                LayerPill(
                    "RAD", state.radiation.size, Color(0xFFD4FF4D), showRadiation || god,
                    loadsOnDemand = true, loaded = "radiation" in state.loaded,
                    trouble = "radiation" in state.feedTrouble
                ) {
                    showRadiation = !showRadiation
                    viewModel.ensureRadiation(showRadiation)
                }
                LayerPill(
                    "MIL GROUND", state.airspace.size, Color(0xFFFF5370), showAirspace || god,
                    loadsOnDemand = true, loaded = "airspace" in state.loaded,
                    trouble = "airspace" in state.feedTrouble
                ) {
                    showAirspace = !showAirspace
                    if (showAirspace) viewModel.ensureAirspace()
                }
                if (state.overlayPoints.isNotEmpty()) {
                    LayerPill("OVLY", state.overlayPoints.size, Intel.CyanBright, showOverlay || god, loaded = "overlayPoints" in state.loaded, trouble = "overlayPoints" in state.feedTrouble) { showOverlay = !showOverlay }
                }
            }

            Text(
                "A pill naming a region covers only that region — an empty " +
                    "layer there means no coverage, not nothing happening. " +
                    "Layers marked GLOBAL cover the whole Earth; cameras and " +
                    "military ground are fetched around wherever you are " +
                    "looking.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            Text(
                "tap a marker to identify it · tap the map for options · " +
                    "long-press for everything in the area",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            Text(
                "▲ hazard   ◆ infrastructure   ■ reading   ➤ aircraft   " +
                    "⌁ vessel   ● finding",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextMid,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            Text(
                "data sources & licences — under MORE",
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
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 2.dp)
                    .clipToBounds()
            ) {
                val layers = MapLayers(
                    aircraft = vis(showAircraft || god, visibleAircraft),
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
                    worldWarnings = vis(showWorldWarnings, state.worldWarnings),
                    seaLevel = vis(showSeaLevel, state.seaLevel),
                    ports = vis(showPorts, state.ports),
                    sites = vis(showSites, state.sites),
                    launches = vis(showLaunches, state.launches),
                    outages = vis(showOutages, state.outages),
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
                    cables = vis(showCables, state.cables),
                    cyclones = vis(showCyclones, state.cyclones),
                    tsunamis = vis(showTsunami, state.tsunamis),
                    airportStatus = vis(showAirportStatus, state.airportStatus),
                    radiation = vis(showRadiation, state.radiation),
                    findings = state.findings
                )
                if (roundEarth) {
                    // Thinned before the sphere ever sees it. The globe
                    // projects every point it is handed on every frame,
                    // and the fire detections alone are now six figures
                    // — a number that cannot be turned into trigonometry
                    // sixty times a second. Done off the main thread and
                    // only when the layers change, not when the camera
                    // moves: the far hemisphere is culled at draw time
                    // for the cost of one comparison per point.
                    val globePoints by produceState(emptyList<GlobePoint>(), layers) {
                        value = withContext(Dispatchers.Default) { thinGlobe(layers.points()) }
                    }
                    GlobeView(
                        points = globePoints,
                        camera = globeCamera,
                        onCamera = { next ->
                            globeCamera = next
                            // Past a large country across the screen a
                            // sphere is the wrong thing to be looking
                            // at, so it hands over mid-pinch and the
                            // zoom simply continues on tiles.
                            val (w, h) = globeSize
                            if (w > 0 && GlobeHandoff.shouldHandToMap(w, h, next.zoom)) {
                                handoff = Triple(
                                    next.lat.toDouble(),
                                    next.lon.toDouble(),
                                    GlobeHandoff.tileZoom(
                                        GlobeHandoff.spanDegrees(w, h, next.zoom), w
                                    )
                                )
                                roundEarth = false
                            }
                        },
                        onSize = { w, h -> globeSize = w to h },
                        onInspect = { p ->
                            inspected = Inspected(
                                p.kind, p.title.orEmpty().ifBlank { p.kind },
                                p.lat, p.lon, p.url
                            )
                        },
                        onPress = { lat, lon -> tapped = lat to lon },
                        onLongPress = { lat, lon, zoom ->
                            area = AreaReport.build(
                                state, lat, lon, AreaReport.radiusForZoom(zoom)
                            )
                        }
                    )
                } else {
                GlobeMap(
                    layers = layers,
                    focus = state.mapFocus,
                    handoff = handoff,
                    onInspect = { inspected = it },
                    onViewport = { lat, lon, zoom ->
                        zoomNow = zoom
                        viewModel.onViewportChanged(lat, lon, zoom)
                        // And back the other way: zoom out far enough
                        // and the tiles become a sphere again, at the
                        // same place and showing the same ground.
                        val (w, h) = globeSize
                        if (w > 0 && GlobeHandoff.shouldReturnToGlobe(zoom, w)) {
                            globeCamera = GlobeCamera(
                                lat.toFloat(),
                                lon.toFloat(),
                                GlobeHandoff.globeZoom(
                                    GlobeHandoff.mapSpanDegrees(zoom, w), w, h
                                )
                            )
                            handoff = null
                            roundEarth = true
                        }
                    },
                    // Markers are a few dp across, so most taps miss. Every
                    // miss used to fire forty lookups from the person's own
                    // IP, create a history row and throw them into a dossier
                    // for a random patch of ocean. The most-touched surface
                    // in the app was wired to its most expensive action.
                    onMapTap = { lat, lon -> tapped = lat to lon },
                    onAreaPress = { lat, lon, zoom ->
                        area = AreaReport.build(state, lat, lon, AreaReport.radiusForZoom(zoom))
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds()
                )
                }
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

                // What a tap offers, instead of what it used to do.
                tapped?.let { (tLat, tLon) ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xE60B0E11))
                            .border(1.dp, Intel.Cyan, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "%.5f, %.5f".format(Locale.US, tLat, tLon),
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.TextHi,
                            fontFamily = FontFamily.Monospace
                        )
                        com.osint.unified.domain.Mgrs.from(tLat, tLon)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = Intel.TextLow,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text(
                                "WHAT'S HERE",
                                style = MaterialTheme.typography.labelMedium,
                                color = Intel.Cyan,
                                modifier = Modifier.clickable {
                                    area = AreaReport.build(
                                        state, tLat, tLon,
                                        AreaReport.radiusForZoom(zoomNow)
                                    )
                                    tapped = null
                                }
                            )
                            Text(
                                "INVESTIGATE",
                                style = MaterialTheme.typography.labelMedium,
                                color = Intel.Amber,
                                modifier = Modifier.clickable {
                                    viewModel.onMapClick(tLat, tLon)
                                    tapped = null
                                }
                            )
                            Text(
                                "DISMISS",
                                style = MaterialTheme.typography.labelMedium,
                                color = Intel.TextMid,
                                modifier = Modifier.clickable { tapped = null }
                            )
                        }
                    }
                }

                // Mode cycle button.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .border(1.dp, Intel.Cyan, RoundedCornerShape(6.dp))
                        .clickable {
                            mode = VisualMode.entries[(mode.ordinal + 1) % VisualMode.entries.size]
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, color = Intel.CyanBright)
                }

                // Its own control, and it says what it costs.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (god) Intel.Amber else Color(0xCC000000))
                        .border(
                            1.dp,
                            if (god) Intel.Amber else Intel.Hairline,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            god = !god
                            if (god) {
                                viewModel.ensureCameras()
                                viewModel.ensureSites()
                                viewModel.ensureAirspace()
                                viewModel.ensureRadiation(true)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (god) "ALL LAYERS ON" else "ALL LAYERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (god) Intel.Base else Intel.TextMid
                    )
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
                        "from the layers loaded now",
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
                        "%.4f, %.4f".format(Locale.US, report.lat, report.lon),
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
                                    AreaReport.fmtDistance(item),
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
                        "%.5f, %.5f".format(Locale.US, obj.lat, obj.lon),
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
            secEmail = state.secContactEmail,
            backendToken = state.backendToken,
            onSave = { backend, tar, ais, overlay, sec, token ->
                viewModel.saveSettings(backend, tar, ais, overlay, sec, token)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * The HUD clock, in a leaf of its own.
 *
 * It used to tick in the same scope that calls GlobeMap. Compose had to
 * recompose that whole scope every second, and because the map takes
 * unstable List parameters it could not skip — so once a second the app
 * tore down and re-allocated up to 2,500 map markers on the main thread,
 * for a clock. Reading the state here confines the recomposition to this
 * one Text.
 */
@Composable
private fun UtcClock() {
    var utc by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        while (true) {
            utc = fmt.format(java.util.Date())
            kotlinx.coroutines.delay(1000)
        }
    }
    Text(
        "${utc}Z",
        style = MaterialTheme.typography.labelSmall,
        color = Intel.Cyan,
        fontFamily = FontFamily.Monospace
    )
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
    layers: MapLayers,
    focus: Pair<Double, Double>?,
    /** Where the globe handed over: a place and the zoom that shows
     *  the same ground it was showing. Applied once. */
    handoff: Triple<Double, Double, Double>?,
    onInspect: (Inspected) -> Unit,
    onViewport: (Double, Double, Double) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    onAreaPress: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // Which focus has already been flown to. Saved, not merely
    // remembered: on a plain remember this reset whenever the globe left
    // the screen, so returning from another tab flew the camera back to
    // the last search at zoom nine and threw away wherever you had
    // scrolled to.
    val appliedFocus = rememberSaveable(
        saver = listSaver(
            save = { listOf(it.value?.first, it.value?.second) },
            restore = { saved ->
                val lat = saved.getOrNull(0) as? Double
                val lon = saved.getOrNull(1) as? Double
                mutableStateOf(if (lat != null && lon != null) lat to lon else null)
            }
        )
    ) { mutableStateOf<Pair<Double, Double>?>(null) }
    // Survives a rotation and a trip to the dossier, so coming back does
    // not drop you at zoom 2.5 over the Atlantic.
    val lastCamera = rememberSaveable(
        saver = listSaver(
            save = { listOf(it.value?.first, it.value?.second, it.value?.third) },
            restore = { saved ->
                val lat = saved.getOrNull(0) as? Double
                val lon = saved.getOrNull(1) as? Double
                val zoom = saved.getOrNull(2) as? Double
                mutableStateOf(
                    if (lat != null && lon != null && zoom != null) Triple(lat, lon, zoom)
                    else null
                )
            }
        )
    ) { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    val appliedHandoff = remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    // The visible area, updated as the map is panned or zoomed. Markers are
    // culled to it so a layer can hold tens of thousands of points without
    // the map trying to draw them all.
    //
    // Held as plain numbers rather than osmdroid's BoundingBox because
    // the work that reads it now runs off the main thread, and because a
    // data class compares equal to itself: panning out and back no longer
    // counts as a change.
    var inView by remember { mutableStateOf<MapWindow?>(null) }
    // Cache marker dot drawables by colour+size so a rebuild of hundreds of
    // markers doesn't allocate a fresh icon each time.
    val dotCache = remember { HashMap<Long, Drawable>() }

    // Which markers belong on screen, worked out away from the main
    // thread and only when the layers or the view actually change.
    //
    // This used to happen inside the AndroidView's update block, which
    // Compose runs on *every* recomposition — so typing a single
    // character into the search box walked seventy thousand points,
    // grouped them, thinned them, destroyed every Marker on the map and
    // built them all again, on the thread drawing the keyboard. The
    // result was a map that stuttered whenever anything at all changed,
    // for no new information.
    val plan by produceState(initialValue = emptyList<PendingMarker>(), layers, inView) {
        value = withContext(Dispatchers.Default) { planMarkers(layers, inView) }
    }
    // The plan currently on the map, by identity. Same instance means
    // nothing to do — the common case, and now free.
    val rendered = remember { mutableStateOf<List<PendingMarker>?>(null) }
    // Markers already on the map, keyed by what they represent rather
    // than by position, so an aircraft that moved is moved rather than
    // destroyed and rebuilt.
    val placed = remember { HashMap<String, Marker>() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // osmdroid refuses to fetch tiles without a user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                // Esri World Imagery: keyless satellite basemap (no token), and
                // its tile path is z/y/x with no extension, so build it directly.
                // Attribution is required and is drawn by the overlay below.
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
                // Esri's terms require the imagery to be credited wherever it
                // is shown, and the app was using it with no attribution at
                // all. The overlay also carries OpenStreetMap, whose data
                // the place and infrastructure layers come from.
                overlays.add(
                    org.osmdroid.views.overlay.CopyrightOverlay(ctx).apply {
                        setCopyrightNotice(
                            "Imagery © Esri, Maxar, Earthstar Geographics · " +
                                "Data © OpenStreetMap contributors"
                        )
                        setTextSize(9)
                    }
                )
                // Where the map was last left, rather than the middle of the
                // Atlantic every time the screen is reopened.
                controller.setZoom(lastCamera.value?.third ?: 2.5)
                controller.setCenter(
                    lastCamera.value?.let { GeoPoint(it.first, it.second) }
                        ?: GeoPoint(20.0, 0.0)
                )
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
                                inView = boundingBox.asWindow()
                                lastCamera.value = Triple(
                                    mapCenter.latitude, mapCenter.longitude, zoomLevelDouble
                                )
                                onViewport(mapCenter.latitude, mapCenter.longitude, zoomLevelDouble)
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                inView = boundingBox.asWindow()
                                lastCamera.value = Triple(
                                    mapCenter.latitude, mapCenter.longitude, zoomLevelDouble
                                )
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
            // The first pass happens before any scroll or zoom event, so
            // the window is unknown and everything would be planned. Fill
            // it in and let the recomposition that causes do the work.
            if (inView == null) {
                inView = map.boundingBox.asWindow()
            } else if (plan !== rendered.value) {
                rendered.value = plan
                val density = map.resources.displayMetrics.density
                val wanted = HashSet<String>(plan.size * 2)
                plan.forEach { pm ->
                    var key = pm.identity
                    // Two unnamed readings can round to the same place.
                    // Without this the second would overwrite the first
                    // and the map would be one marker short for ever.
                    if (!wanted.add(key)) {
                        var n = 2
                        while (!wanted.add("$key#$n")) n++
                        key = "$key#$n"
                    }
                    val px = (pm.sizeDp * density).toInt()
                    val shape = shapeFor(pm.kind)
                    val iconKey = (pm.color.toLong() shl 16) xor
                        (px.toLong() shl 4) xor shape.ordinal.toLong()
                    val icon = dotCache.getOrPut(iconKey) { markerIcon(shape, pm.color, px) }
                    // Reused rather than rebuilt. An aircraft that moved
                    // is the same aircraft; destroying its Marker and
                    // allocating another for a new latitude is most of
                    // what made a refresh expensive.
                    val m = placed.getOrPut(key) {
                        Marker(map).also {
                            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            map.overlays.add(it)
                        }
                    }
                    m.position = GeoPoint(pm.lat, pm.lon)
                    if (m.icon !== icon) m.icon = icon
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
                    } else {
                        // A reused marker keeps the listener it was given
                        // last time, so a cell that is now part of a
                        // non-clickable layer would still open a dialog.
                        m.setOnMarkerClickListener { _, _ -> false }
                    }
                }
                if (placed.size > wanted.size) {
                    val gone = placed.keys.filterNot { it in wanted }
                    gone.forEach { key ->
                        placed.remove(key)?.let { map.overlays.remove(it) }
                    }
                }
                map.invalidate()
            }

            // The globe handed this view the camera it was using.
            // Set rather than animated: this is the continuation of a
            // pinch that is still happening, and a half-second flight
            // in the middle of one feels like the map fighting back.
            if (handoff != null && handoff != appliedHandoff.value) {
                map.controller.setZoom(handoff.third)
                map.controller.setCenter(GeoPoint(handoff.first, handoff.second))
                appliedHandoff.value = handoff
                map.invalidate()
            }

            // Fly to a newly-focused location (coordinate search or first geo finding).
            if (focus != null && focus != appliedFocus.value) {
                map.controller.animateTo(GeoPoint(focus.first, focus.second))
                map.controller.setZoom(9.0)
                appliedFocus.value = focus
                map.invalidate()
            }
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
        "Aircraft", "Military aircraft" -> head.takeIf { it.isNotBlank() }
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

/** The most of the screen the control panel may take, open or not. The
 *  map gets the rest, and it is never nothing. */
private val PANEL_MAX_HEIGHT = 300.dp

/** What the object inspector is showing: which layer the marker came from,
 *  its label, where it is, and any link that layer carries. */
data class Inspected(
    val kind: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val url: String? = null
)

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
    /** Set when the last fetch failed. The count beside it, if any, is the
     *  last good answer and not a current one — so it is marked, never
     *  shown as though the feed had just confirmed it. */
    trouble: Boolean = false,
    onClick: () -> Unit
) {
    // A screen reader could not tell whether a layer was on: the pill was a
    // plain clickable with the state carried only by colour, which is also
    // invisible to roughly one man in twelve.
    val stateWord = when {
        trouble -> "not reporting"
        on -> "on"
        else -> "off"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Outermost, so it enlarges the area a thumb can hit without
            // enlarging the pill. Applied after the background and border,
            // it stretched those to 48 dp as well, and twenty-eight
            // half-height buttons became twenty-eight full-height ones.
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(if (on) Intel.Elevated else Intel.Surface)
            .border(1.dp, if (on) color else Intel.Hairline, RoundedCornerShape(50))
            .toggleable(value = on, role = Role.Switch) { onClick() }
            .semantics {
                stateDescription = "$stateWord, $count items"
                contentDescription = "$label layer"
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (on) color else Intel.TextLow))
        Spacer(Modifier.width(6.dp))
        val shown = when {
            trouble && count > 0 -> "$count!"
            trouble -> "!"
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
            color = when {
                trouble -> Intel.Danger
                on -> Intel.TextHi
                else -> Intel.TextLow
            }
        )
    }
}

@Composable
private fun SettingsDialog(
    backendUrl: String,
    tar1090Url: String,
    aisUrl: String,
    overlayUrl: String,
    secEmail: String,
    backendToken: String,
    onSave: (String, String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var backend by remember { mutableStateOf(backendUrl) }
    var tar by remember { mutableStateOf(tar1090Url) }
    var ais by remember { mutableStateOf(aisUrl) }
    var overlay by remember { mutableStateOf(overlayUrl) }
    var sec by remember { mutableStateOf(secEmail) }
    var token by remember { mutableStateOf(backendToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Intel.Surface,
        titleContentColor = Intel.TextHi,
        textContentColor = Intel.TextMid,
        title = { Text("LIVE FEEDS", style = MaterialTheme.typography.titleSmall, color = Intel.TextHi) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FeedNote(
                    "Several layers cover a region rather than the world, and " +
                        "the pill says which. Add your own feed to widen one."
                )
                // Said here rather than discovered as a silent failure.
                // Android refuses cleartext for everything but localhost,
                // and has no way to express "my home network" — so an
                // http:// address on the LAN does not work, and the
                // configuration that appeared to allow it never did.
                FeedNote(
                    "Addresses must be https. Android blocks plain http for " +
                        "everything but localhost, and it has no way to make an " +
                        "exception for a home network, so an http:// feed on " +
                        "your LAN will fail to connect rather than fall back."
                )

                FeedHeader("SEC EDGAR  ·  your contact address")
                FeedNote(
                    "EDGAR refuses anonymous requests and wants a contact " +
                        "address. Yours is sent only to sec.gov, and the " +
                        "EDGAR source stays off until you add one."
                )
                SettingsField(sec, { sec = it }, "Contact email for SEC", "you@example.com")

                FeedHeader("AIRCRAFT  ·  add your own receiver")
                SettingsField(tar, { tar = it }, "tar1090 feed URL", "https://host:8080/data/aircraft.json")

                FeedHeader("VESSELS  ·  Baltic only by default")
                FeedNote(
                    "The default AIS feed is Digitraffic, which covers Finnish " +
                        "waters and the Baltic. There is no free global AIS, so " +
                        "an empty sea elsewhere means no coverage, not no ships."
                )
                SettingsField(ais, { ais = it }, "AIS vessel feed URL", "https://host/vessels.json")

                FeedHeader("CUSTOM OVERLAY  ·  any GeoJSON points")
                SettingsField(overlay, { overlay = it }, "GeoJSON points URL", "https://host/points.geojson")

                FeedHeader("CLOUD BACKEND  ·  optional tools")
                FeedNote(
                    "Your backend refuses unauthenticated requests, so it needs " +
                        "the same token you set as OSINT_BACKEND_TOKEN there. " +
                        "Without that, anyone who found its URL could run scans " +
                        "from your account."
                )
                SettingsField(backend, { backend = it }, "Backend URL", "https://your-api.onrender.com")
                // A bearer token in plain text on a screen somebody may
                // be showing to a colleague, or screenshotting.
                SettingsField(
                    token, { token = it }, "Backend token",
                    "the value of OSINT_BACKEND_TOKEN", secret = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(backend, tar, ais, overlay, sec, token) }) {
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
    top: androidx.compose.ui.unit.Dp = 0.dp,
    /** A secret, shown as dots until asked for. */
    secret: Boolean = false
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret && !revealed) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        trailingIcon = if (!secret) null else {
            {
                Text(
                    if (revealed) "HIDE" else "SHOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Cyan,
                    modifier = Modifier
                        .clickable { revealed = !revealed }
                        .padding(horizontal = 10.dp)
                )
            }
        },
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

/** osmdroid's view-layer box as the plain value the planner reads. */
private fun BoundingBox.asWindow(): MapWindow =
    MapWindow(latNorth, latSouth, lonWest, lonEast)
