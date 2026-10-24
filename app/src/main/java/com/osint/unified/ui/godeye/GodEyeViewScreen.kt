package com.osint.unified.ui.godeye

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.osint.unified.OsintApplication
import kotlinx.coroutines.flow.MutableStateFlow
import com.osint.unified.BuildConfig
import com.osint.unified.ui.theme.Intel
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

private val EXAMPLES = listOf("example.com", "8.8.8.8", "40.7128,-74.0060", "+14155550123", "John Smith")

// The router handles twelve types and nine of them had a chip, so
// url, wallet and aircraft were reachable only if auto-detection
// happened to agree with you — and auto-detection deliberately
// refuses to read six hex characters as an aircraft or a bare word
// as a wallet, because those guesses are wrong more often than
// they are right. Forcing the type is the answer to exactly that,
// and for three of the twelve there was no way to.
/** The questions, in the order somebody is likeliest to ask them. */
private val LAYER_PRESETS = listOf(
    "AVIATION", "MARITIME", "DISASTER", "CONFLICT", "INFRASTRUCTURE", "SPACE WX"
)

private val SEARCH_TYPES = listOf(
    "auto" to "AUTO", "name" to "NAME", "email" to "EMAIL", "username" to "USER",
    "domain" to "DOMAIN", "ip" to "IP", "phone" to "PHONE", "coordinate" to "COORD",
    "image" to "IMAGE", "url" to "URL", "wallet" to "WALLET", "aircraft" to "AIRCRAFT"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GodEyeViewScreen(
    viewModel: GodEyeViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Every layer starts off.
    //
    // Thirty-three feeds switched on at once is a map with everything
    // on it, which is the same as a map with nothing on it: the thing
    // you came to look at is under four thousand other dots. Turning
    // one on is a decision, and a decision is what makes the answer
    // mean something.
    var showAircraft by rememberSaveable { mutableStateOf(false) }
    var showVessels by rememberSaveable { mutableStateOf(false) }
    var showQuakes by rememberSaveable { mutableStateOf(false) }
    var showEvents by rememberSaveable { mutableStateOf(false) }
    var showOverlay by rememberSaveable { mutableStateOf(false) }
    var showWeather by rememberSaveable { mutableStateOf(false) }
    var showFires by rememberSaveable { mutableStateOf(false) }
    var showSats by rememberSaveable { mutableStateOf(false) }
    var showNews by rememberSaveable { mutableStateOf(false) }
    var showGdacs by rememberSaveable { mutableStateOf(false) }
    var showVolcanoes by rememberSaveable { mutableStateOf(false) }
    var showWorldWarnings by rememberSaveable { mutableStateOf(false) }
    var showSeaLevel by rememberSaveable { mutableStateOf(false) }
    var showPorts by rememberSaveable { mutableStateOf(false) }
    var showSites by rememberSaveable { mutableStateOf(false) }
    var showLaunches by rememberSaveable { mutableStateOf(false) }
    var showOutages by rememberSaveable { mutableStateOf(false) }
    var showAurora by rememberSaveable { mutableStateOf(false) }
    var showBuoys by rememberSaveable { mutableStateOf(false) }
    var showMetar by rememberSaveable { mutableStateOf(false) }
    var showMil by rememberSaveable { mutableStateOf(false) }
    var showConflict by rememberSaveable { mutableStateOf(false) }
    var showCyclones by rememberSaveable { mutableStateOf(false) }
    var showTsunami by rememberSaveable { mutableStateOf(false) }
    var showAirportStatus by rememberSaveable { mutableStateOf(false) }
    var showRadiation by rememberSaveable { mutableStateOf(false) }
    // Everything on at once, which costs real bandwidth, so it is its own
    // deliberate switch rather than the fifth stop on a colour cycle.
    var god by rememberSaveable { mutableStateOf(false) }
    // The dossier's own coordinates. Drawn on top of everything in
    // magenta and, until now, with no switch at all: they appeared
    // with every layer off, under the line saying nothing was being
    // fetched, and there was no way to clear them off the map.
    var showFindings by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    // One sentence instead of thirty.
    //
    // With no network the app was honest per source and incoherent
    // overall: thirty differently-worded failures on thirty pills, a
    // black map and a dossier of excuses, and nowhere the one line
    // that explains all of it. The permission to read this has been
    // in the manifest since the beginning and nothing read it.
    val link by produceState(
        com.osint.unified.network.Connectivity.State(true, false, false),
        context
    ) {
        com.osint.unified.network.Connectivity.observe(context).collect { value = it }
    }
    // Saveable, all of it.
    //
    // These nine sat among saveable neighbours as plain `remember`s,
    // so a rotation or a tab switch threw away the marker dialog you
    // had open, the area report a Default-dispatcher pass over every
    // armed layer had just produced, the coordinate action sheet, and
    // the map's last centre and zoom — and `zoomNow` resetting to 2.5
    // made the next WHAT'S HERE use a two-thousand-kilometre radius
    // instead of the real one.
    var inspected by rememberSaveable(stateSaver = InspectedSaver) {
        mutableStateOf<Inspected?>(null)
    }
    // What a long press on the globe turned up: every loaded layer, swept
    // for a radius around that point.
    // Not saveable, deliberately: it is rebuilt from `areaWanted`
    // below, which is, and a whole report in a Bundle is a transaction
    // the size of the layers it swept.
    var area by remember { mutableStateOf<AreaReport.Report?>(null) }
    // The place a report has been asked for, and the report itself.
    //
    // AreaReport.build does a haversine per point of every layer that
    // is on, with no viewport cull: with fires and satellites armed
    // that is several hundred thousand sin/cos/asin calls, and all
    // three call sites were running it synchronously inside the
    // gesture handler. A long press froze the screen for seconds and
    // on a slow phone was an ANR. Every other heavy thing in this
    // package — thinGlobe, the vector load — is already
    // off the main thread; this was the one that was not.
    // The standing request, not a one-shot: it used to be cleared the
    // moment the report was built, which left nothing to rebuild from
    // after a rotation.
    var areaWanted by rememberSaveable(stateSaver = TripleDoubleSaver) {
        mutableStateOf<Triple<Double, Double, Double>?>(null)
    }
    // Markers on screen, and markers the armed layers hold.
    //
    // Zoom a map in far enough and an empty screen is the correct
    // answer — everything the layers hold is somewhere else — but the
    // pills above it go on reporting layer totals, so the app looks
    // broken at exactly the moment it is being careful. Reported from
    // a second phone as "zoom in and all the layers stop working",
    // which is what that looks like from the outside.
    var plotted by rememberSaveable(stateSaver = IntPairSaver) { mutableStateOf(0 to 0) }
    // Keyed on the request alone.
    //
    // `state` was a key because the request used to be cleared the
    // moment the report was built, which made the second key almost
    // unreachable. Now that the request survives — so the report can
    // be rebuilt after a rotation — keeping `state` would re-sweep
    // every armed layer on every poll for as long as the dialog is
    // open. A report is a snapshot of a moment, and this is that
    // moment.
    LaunchedEffect(areaWanted, state.loaded.isEmpty()) {
        val want = areaWanted ?: return@LaunchedEffect
        // Not against a state that has not loaded anything yet.
        //
        // `areaWanted` is saveable so the report survives a
        // rotation, where the view model survives with it. It also
        // survives the process being killed, where the view model
        // does not — and the effect then fired once against the
        // empty initial state and, with `state` no longer a key,
        // never rebuilt. Relaunching from Recents opened a modal
        // report, unasked, saying nothing was anywhere. The second
        // key is a one-shot: it flips once when the first feed
        // answers and never flips back.
        if (state.loaded.isEmpty() && state.results.isEmpty()) return@LaunchedEffect
        val built = withContext(Dispatchers.Default) {
            AreaReport.build(state, want.first, want.second, want.third)
        }
        area = built
    }
    // Where the map was last tapped, offered as a choice rather than acted on.
    var tapped by rememberSaveable(stateSaver = DoublePairSaver) {
        mutableStateOf<Pair<Double, Double>?>(null)
    }
    var zoomNow by rememberSaveable { mutableDoubleStateOf(2.5) }
    // The world brief is derived from the same state the globe draws, so it
    // is recomputed rather than fetched, and is never out of step with it.
    var showBrief by rememberSaveable { mutableStateOf(false) }
    // Keyed on the layers the brief reads, not the whole state: the
    // aircraft poll replaces the state object every fifteen seconds, and
    // rebuilding the ranking each time would be work for no new answer.
    // Off the main thread, because it reads every layer.
    //
    // build() scores every item of every armed layer — a regex per
    // label — and the fire layer alone can hold six figures. That ran
    // inside `remember`, which is to say synchronously during
    // composition, at the moment a poll returned. The brief is a
    // sentence at the top of the screen; it can arrive a frame late.
    val brief by produceState(
        initialValue = WorldBrief.Brief(emptyList(), emptyList()),
        state.conflicts, state.gdacs, state.tsunamis, state.cyclones,
        state.volcanoes, state.worldWarnings, state.seaLevel, state.outages,
        state.ports, state.launches, state.sites,
        state.quakes, state.weather, state.fires,
        state.milAircraft, state.airportStatus, state.news,
        state.spaceWeather, state.loaded,
        // Without this the brief never grows its NOT REPORTING
        // section: a feed failing changes only this map, so every
        // other key compared equal and the cached brief — including
        // its all-clear lines — was handed back unchanged.
        state.feedTrouble,
        // The traffic line reads these three, so leaving them out of the key
        // let the brief quote counts the header beside it had moved past.
        state.aircraft, state.vessels, state.satellites
    ) { value = withContext(Dispatchers.Default) { WorldBrief.build(state) } }
    // The panel of search controls and layer pills is most of a phone
    // screen. On something called a God's Eye View the globe should be the
    // thing you are looking at, so the panel folds away — and starts
    // folded. Twenty-eight layer buttons, each 48 dp tall for the sake of
    // thumbs, plus a search box and three hint lines, is more than a 360 dp
    // phone has: the front door was a wall of buttons with a sliver of map
    // underneath.
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    // What is left of the screen after everything above the map.
    //
    // Measured, not guessed. PANEL_MAX_HEIGHT was 300dp on every
    // device; a share of screenHeightDp replaced it and was better
    // but still wrong, because a share of the whole screen takes no
    // account of what is already using it. In landscape the header
    // and the search box are 132dp of a 270dp viewport, the panel
    // claimed 137 more, and the map — the entire point of the
    // screen — was laid out at about one pixel. The same thing
    // happens in portrait at a 2x system font size.
    //
    // So: measure the chrome, and give the panel only what is left
    // once the map has kept a usable amount.
    var bannerPx by remember { mutableIntStateOf(0) }
    var headerPx by remember { mutableIntStateOf(0) }
    var searchPx by remember { mutableIntStateOf(0) }
    var typesPx by remember { mutableIntStateOf(0) }
    var panelPx by remember { mutableIntStateOf(0) }
    // One camera, because there is one view.
    //
    // There used to be two: a sphere and a flat map, a button to
    // swap between them, a conversion so the swap kept your place,
    // and a hysteresis band so a pinch landing on the boundary did
    // not flip back and forth under the thumb. All of that existed
    // because the bundled whole-Earth mosaic runs out of pixels at
    // about a large country across — so the globe now paints itself
    // with the same satellite tiles the map was showing, and goes
    // the rest of the way on its own.
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

    // The globe tells the app where it is looking, the same way the
    // map does.
    //
    // It never did, and the globe is the default view — so for anyone
    // who had not been to the flat map and back, the viewport was
    // simply unknown. Aircraft fetched the whole planet however far in
    // the globe was turned, which is the sweep the bounding box was
    // added to avoid. Turning on the sites layer did nothing at all,
    // silently, because it begins `val where = focus ?: return`.
    // Radiation fell back to a point in the Atlantic off West Africa
    // and showed the count as though it described the view. And a tap
    // always reported an area of 2000 km radius because the zoom it
    // read was still at its initial value.
    //
    // Reported on a coarse grid with a pause after it, because this
    // fires on every frame of a drag and one of the things downstream
    // of it is a network query.
    run {
        val (vw, vh) = globeSize
        // A quarter of what is on screen, not a fixed two degrees.
        //
        // Two degrees is about two hundred kilometres. That was a sensible
        // grain when the globe stopped at a country across and a map took
        // over below; the map reported every pan. With the globe going to
        // street level, panning from London to Reading crossed no grid line,
        // nothing was reported, the camera layer never re-asked — and an
        // area report there stated no cameras within 25 km, an absence it
        // had never looked for. Snapped to a power of two so the key does
        // not churn as the zoom slides.
        val span = if (vw > 0) GlobeHandoff.spanDegrees(vw, vh, globeCamera.zoom) else 180.0
        val step = 2.0.pow(floor(log2((span / 4.0).coerceIn(1e-5, VIEWPORT_STEP.toDouble()))))
        LaunchedEffect(
            vw, vh,
            floor(globeCamera.lat / step).toLong(),
            floor(globeCamera.lon / step).toLong(),
            (log2(globeCamera.zoom.toDouble().coerceAtLeast(0.1)) * 4).roundToInt()
        ) {
            if (vw > 0) {
                kotlinx.coroutines.delay(VIEWPORT_SETTLE_MS)
                val lat = globeCamera.lat.toDouble()
                val lon = globeCamera.lon.toDouble()
                val z = GlobeHandoff.tileZoom(
                    GlobeHandoff.spanDegrees(vw, vh, globeCamera.zoom), vw, lat
                )
                zoomNow = z
                viewModel.onViewportChanged(lat, lon, z)
            }
        }
    }
    // Fly to a newly focused place: a coordinate search, or the first
    // geo-located finding of an investigation.
    //
    // The flat map did this and the globe did not, so deleting the
    // map would have deleted the behaviour with it — searching a
    // coordinate would have set a focus nothing acted on.
    //
    // Saved, not merely remembered. The focus stays set in the view
    // model, and this screen is rebuilt on every return from another tab
    // and every rotation — so with a plain `remember` the marker reset,
    // the effect fired again, and the camera was thrown back to the last
    // search each time, wherever the person had since gone. The deleted
    // map had exactly this fix, with exactly this comment, and it was
    // lost in the move.
    var appliedFocus by rememberSaveable(stateSaver = DoublePairSaver) {
        mutableStateOf<Pair<Double, Double>?>(null)
    }
    LaunchedEffect(state.mapFocus, globeSize) {
        val focus = state.mapFocus ?: return@LaunchedEffect
        val (w, h) = globeSize
        if (w <= 0 || focus == appliedFocus) return@LaunchedEffect
        appliedFocus = focus
        globeCamera = GlobeCamera(
            focus.first.toFloat().coerceIn(-89f, 89f),
            focus.second.toFloat(),
            GlobeHandoff.globeZoom(
                GlobeHandoff.mapSpanDegrees(FOCUS_TILE_ZOOM, w, focus.first), w, h
            )
        )
    }
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
            // Space weather rides with the aurora layer, which is what
            // it is for. It used to be added unconditionally, which
            // meant the layer count was never zero — so the "+ ADD
            // LAYERS" label was unreachable, CLEAR was always offered,
            // and the line saying nothing is being fetched could never
            // appear and would have been untrue if it had.
            if (showAurora || god) add("spaceWeather")
        }
    }
    LaunchedEffect(visibleLayers) { viewModel.setVisibleLayers(visibleLayers) }

    // Stop fetching when this screen is not the one being looked at.
    //
    // The app being in the foreground is not the same as the map
    // being on screen: read a dossier for ten minutes with AIR·GLOBAL
    // on and the aircraft sweep went on running every twenty-five
    // seconds for a globe nobody could see. Not cleared, only paused
    // — the screen is coming back, and the layers' last answer is
    // better than an empty globe for a poll interval.
    DisposableEffect(Unit) {
        viewModel.setMapOnScreen(true)
        onDispose { viewModel.setMapOnScreen(false) }
    }

    // GOD MODE forces every layer visible.
    fun <T> vis(on: Boolean, data: List<T>): List<T> = if (on || god) data else emptyList()


    Scaffold(containerColor = Intel.Base) { pad ->
      // The height the content actually has, after the system bars
      // and the navigation bar — which is what the panel has to
      // divide, and is 80dp or more short of the display height.
      BoxWithConstraints(modifier = Modifier.padding(pad).fillMaxSize()) {
        val viewport = maxHeight
        val density = LocalDensity.current
        // The banner's height counts only while the banner is there.
        // onSizeChanged does not fire when a composable leaves, so
        // its last measurement would otherwise stay in the total for
        // the rest of the session.
        val banner = if (com.osint.unified.data.db.AppDatabase.lockedOut) bannerPx else 0
        val chrome = with(density) { (banner + headerPx + searchPx + typesPx).toDp() }
        // A map worth looking at. Below this there is no point
        // drawing one, and the panel has to give way instead.
        val mapFloor = 200.dp
        val forPanel = viewport - chrome
        // Two ways to arrange a panel and a map. Where there is room
        // for both, the panel sits above and pushes the map down,
        // which is what it has always done. Where there is not, the
        // panel floats over the map instead of evicting it: same
        // opaque strip in the same place, but the map keeps its
        // height behind it.
        // Below this a scrolling panel is not worth having; better
        // to float it over the map than to leave a 50dp slot.
        val minPanel = 140.dp
        val overlay = forPanel - mapFloor < minPanel
        // Never negative. In landscape at a large font `chrome` can
        // exceed the viewport, and a negative Dp handed to heightIn
        // coerces to zero rather than throwing — which is survivable
        // but means the arithmetic above stopped describing anything.
        val panelMax = (if (overlay) forPanel * 0.72f else forPanel - mapFloor)
            .coerceAtLeast(0.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            // An encrypted database this device can no longer open.
            //
            // Said here rather than only on the settings screen. The
            // symptom is an empty case list, and the obvious reading
            // of an empty case list is that the app lost your work —
            // so the one thing that must not happen is for the app to
            // know better and not say.
            if (com.osint.unified.data.db.AppDatabase.lockedOut) {
                var showWhy by rememberSaveable { mutableStateOf(false) }
                Text(
                    "⚠ YOUR EARLIER CASES CANNOT BE OPENED — TAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Base,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { bannerPx = it.height }
                        .background(Intel.Danger)
                        .clickable { showWhy = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                if (showWhy) {
                    AlertDialog(
                        onDismissRequest = { showWhy = false },
                        containerColor = Intel.Surface,
                        titleContentColor = Intel.TextHi,
                        textContentColor = Intel.TextMid,
                        title = {
                            Text(
                                "EARLIER CASES LOCKED",
                                style = MaterialTheme.typography.titleSmall,
                                color = Intel.TextHi
                            )
                        },
                        text = {
                            Text(
                                com.osint.unified.data.db.AppDatabase.encryptionNote,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showWhy = false }) {
                                Text(
                                    "UNDERSTOOD",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Intel.Cyan
                                )
                            }
                        }
                    )
                }
            }
            // Classification banner (tactical console convention; green = UNCLASS).

            // ---- Console header + live telemetry ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerPx = it.height }
                    .padding(start = 14.dp, end = 4.dp, top = 10.dp)
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
                        // visibleAircraft, not the raw feed: with a
                        // callsign filter on, the header read AIR 11234
                        // over a pill reading 42 and a map holding 42,
                        // which is the screen disagreeing with itself in
                        // two places at once.
                        "AIR ${visibleAircraft.size} · SEA ${state.vessels.size} · " +
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
                // No toggle, because there is nothing to toggle
                // between. The globe is the view; it paints itself
                // with satellite tiles once it is close enough to
                // need them, which is all the map was ever for.
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
            // ---- Command search ----
            //
            // Outside the fold, because the fold starts closed.
            //
            // The comment forty lines below used to say "what folds
            // away is the search box, the type chips and the
            // examples" — and `panelOpen` defaults to false. So on a
            // fresh install the search field did not exist on screen
            // at all: the only way to reach the one thing this app is
            // for was to notice an unlabelled chevron in the top bar
            // and tap it. Reported from a second phone as "none of
            // the search features really worked", and as the app
            // being "basically just a list of tools", which is
            // exactly what is left when you cannot find the search.
            //
            // Somebody had already hit this with the layer controls,
            // moved those out, and wrote down why. The box that
            // everything else exists to serve stayed behind the
            // chevron.
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
                // A search that will not run says so under the box
                // it will not run from.
                isError = state.queryTrouble != null,
                supportingText = state.queryTrouble?.let {
                    { Text(it, style = MaterialTheme.typography.labelSmall) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { searchPx = it.height }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // The type chips belong with the box they change.
            //
            // The search field was lifted out of the fold because on
            // a fresh install there was no visible search box at all.
            // The chips that decide what a search MEANS were left
            // behind it, which is the same bug one step along: a box
            // you can find and no way to tell it that "london" is a
            // place and not somebody's handle. The comment three
            // screens down still said "what folds away is the search
            // box, the type chips and the examples" while the box had
            // already gone. The examples can stay folded; they are a
            // convenience, and these are not.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { typesPx = it.height }
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                SEARCH_TYPES.forEach { (key, label) ->
                    TypeChip(label, state.searchType == key) { viewModel.onTypeSelected(key) }
                }
            }

            // The panel and the map share what is left, in one of the
            // two arrangements decided above.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Source order would draw the panel under the
                    // map; this keeps the panel above it without
                    // moving three hundred lines past it.
                    .zIndex(1f)
                    .fillMaxWidth()
                    .background(Intel.Base)
                    // Measured OUTSIDE the scroller, which is the
                    // whole point of measuring it.
                    //
                    // verticalScroll measures its child with an
                    // infinite height and then clamps itself, so an
                    // onSizeChanged placed after it in the chain
                    // reports the full unclipped content height —
                    // not the height the panel occupies. That number
                    // is the map's top padding, so opening the panel
                    // on any phone where its content overflows pushed
                    // the map straight through its own 200dp floor to
                    // nothing: the exact failure this measurement was
                    // added to prevent, reintroduced by one modifier
                    // being on the wrong side of another.
                    .onSizeChanged { panelPx = it.height }
                    .heightIn(max = panelMax)
                    .verticalScroll(rememberScrollState())
            ) {
            if (panelOpen) {
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
            }

            // The layer controls are not part of the panel that folds.
            //
            // They were, and the panel starts folded. That was survivable
            // while every layer was on by default and the map arrived
            // covered in markers. Now that nothing is on until you ask,
            // a new map is empty and the only way to put something on it
            // was hidden behind a chevron — so the layers looked broken
            // rather than merely off, which is exactly the impression
            // turning them off by default was supposed to avoid.
            //
            // What folds away is the search box, the type chips and the
            // examples. What does not is the row of what is on and the
            // button that adds more.

            // Everything off, in one gesture. Turning thirty-three
            // switches back one at a time to get a clean map is the
            // kind of chore that makes people stop using a feature.
            // ALL LAYERS is a shortcut, not a mode that outranks the
            // switches.
            //
            // Every armed test reads `showX || god`, so while it was on
            // a tap on a pill flipped a flag nothing read, CLEAR reset
            // thirty-three flags nothing read, and the six presets were
            // all the same preset: press ALL LAYERS then CLEAR and
            // nothing at all happened, with every feed still polling
            // under a row that still said 34 ON. And `god` is saveable,
            // so that state survived a restart.
            //
            // The shortcut collapses into the switches the moment
            // anything else touches them. Turning it on means "all of
            // them on", and that is a sentence the thirty-three flags
            // can say by themselves.
            val settleGod = {
                if (god) {
                    showAircraft = true; showAirportStatus = true
                    showAirports = true; showAirspace = true
                    showAqi = true; showAurora = true
                    showBuoys = true; showCables = true
                    showCameras = true; showConflict = true
                    showCyclones = true; showEvents = true
                    showFires = true; showGdacs = true
                    showLaunches = true; showMetar = true
                    showMil = true; showNews = true
                    showOutages = true; showOverlay = true
                    showPorts = true; showPower = true
                    showQuakes = true; showRadiation = true
                    showSats = true; showSeaLevel = true
                    showSites = true; showStations = true
                    showTsunami = true; showVessels = true
                    showVolcanoes = true; showWeather = true
                    showWorldWarnings = true
                    god = false
                }
            }

            val clearLayers = {
                // Including the dossier's own markers. They are not a
                // feed, so ALL LAYERS has no business arming them —
                // but CLEAR says "everything off, in one gesture",
                // and leaving a lit FINDINGS pill above a line
                // reading "no layers on, so nothing is being fetched"
                // is the kind of disagreement this screen keeps
                // having with itself.
                showFindings = false
                // First, because everything below is read as
                // `showX || god` and without this line not one of the
                // thirty-three assignments reaches the map.
                god = false
                showAircraft = false
                showAirportStatus = false
                showAirports = false
                showAirspace = false
                showAqi = false
                showAurora = false
                showBuoys = false
                showCables = false
                showCameras = false
                showConflict = false
                showCyclones = false
                showEvents = false
                showFires = false
                showGdacs = false
                showLaunches = false
                showMetar = false
                showMil = false
                showNews = false
                showOutages = false
                showOverlay = false
                showPorts = false
                showPower = false
                showQuakes = false
                showRadiation = false
                // Not just hidden: the view model keeps its own flag
                // and goes on querying Safecast on every pan without
                // this, for a layer nobody can see.
                viewModel.ensureRadiation(false)
                showSats = false
                showSeaLevel = false
                showSites = false
                showStations = false
                showTsunami = false
                showVessels = false
                showVolcanoes = false
                showWeather = false
                showWorldWarnings = false
            }

            // Six questions, instead of thirty-three switches.
            //
            // The layer list is a list of feeds, and nobody arrives
            // wanting a feed: they arrive wanting to know what is
            // happening in the air, or at sea, or where something has
            // just gone wrong. Finding the six or seven pills that
            // answer one of those, out of thirty-three in a flat row
            // with a search box to find a pill, is the work the app
            // should have done. Each of these clears everything and
            // turns on the set that answers one question, including
            // the on-demand ones, which have to be asked as well as
            // switched on.
            val applyPreset: (String) -> Unit = { preset ->
                clearLayers()
                when (preset) {
                    "AVIATION" -> {
                        showAircraft = true; showMil = true; showAirports = true
                        showMetar = true; showAirportStatus = true
                        showAirspace = true; viewModel.ensureAirspace()
                    }
                    "MARITIME" -> {
                        showVessels = true; showPorts = true; showBuoys = true
                        showSeaLevel = true; showCables = true
                    }
                    "DISASTER" -> {
                        showQuakes = true; showVolcanoes = true; showGdacs = true
                        showCyclones = true; showTsunami = true; showFires = true
                        showWorldWarnings = true; showWeather = true
                    }
                    "CONFLICT" -> {
                        showConflict = true; showMil = true; showNews = true
                        showSites = true; viewModel.ensureSites()
                        showAirspace = true; viewModel.ensureAirspace()
                    }
                    "INFRASTRUCTURE" -> {
                        showPower = true; showCables = true; showPorts = true
                        showAirports = true; showStations = true; showOutages = true
                        showCameras = true; viewModel.ensureCameras()
                    }
                    "SPACE WX" -> {
                        showAurora = true; showSats = true; showLaunches = true
                        showRadiation = true; viewModel.ensureRadiation(true)
                    }
                }
            }

            // The pill row shows what is on, and nothing else.
            //
            // Thirty-three of them at once is clutter whichever way they
            // are arranged, and arranging them is the wrong fix: with
            // every layer off to start with, the row is empty until you
            // put something in it, and what is in it is what you asked
            // for. The whole list lives one tap away, where a filter
            // makes sense because that screen is a list.
            var layerFilter by rememberSaveable { mutableStateOf("") }
            var picking by rememberSaveable { mutableStateOf(false) }

            // One set of pills, rendered in two places: here, showing
            // only what is on, and inside the picker, showing all of
            // them. Written once, because two copies of thirty-three
            // switches would disagree within a week.
            //
            // A callsign filter makes the aircraft feed's size
            // different from what is on screen, and a pill reading
            // 11,000 over a map holding forty dots is the map
            // disagreeing with itself — so that one counts the
            // filtered set.
            //
            // Grouped, because a wall of thirty-three is a wall.
            //
            // The presets answer "what is happening in the air"
            // in one tap; this is for the person who wants one
            // particular feed and had to find it in a flat row
            // with a search box for finding a pill. The headings
            // only appear in the picker — in the panel above the
            // map the row shows what is on, and a heading over a
            // group with nothing in it is worse than no heading.
            val feedGroups: List<Pair<String, @Composable () -> Unit>> = listOf(
                "IN THE AIR" to {
                LayerPill("AIR·GLOBAL", visibleAircraft.size, Intel.NetCyan, showAircraft || god, full = "Aircraft", loaded = "aircraft" in state.loaded, trouble = "aircraft" in state.feedTrouble) { settleGod(); showAircraft = !showAircraft }
                LayerPill("MIL", state.milAircraft.size, Color(0xFFFFD23F), showMil || god, full = "Military aircraft", loaded = "milAircraft" in state.loaded, trouble = "milAircraft" in state.feedTrouble) { settleGod(); showMil = !showMil }
                LayerPill("APT", state.airports.size, Color(0xFF8FD3FF), showAirports || god, full = "Airports", loaded = "airports" in state.loaded, trouble = "airports" in state.feedTrouble) { settleGod(); showAirports = !showAirports }
                LayerPill("ATC·US", state.airportStatus.size, Color(0xFFFFA64D), showAirportStatus || god, full = "Airport delays (US)", loaded = "airportStatus" in state.loaded, trouble = "airportStatus" in state.feedTrouble) { settleGod(); showAirportStatus = !showAirportStatus }
                LayerPill("METAR", state.metar.size, Color(0xFF9FE8FF), showMetar || god, full = "Airport weather", loaded = "metar" in state.loaded, trouble = "metar" in state.feedTrouble, partial = "metar" in state.feedPartial) { settleGod(); showMetar = !showMetar }
                LayerPill(
                "MIL GROUND", state.airspace.size, Color(0xFFFF5370), showAirspace || god, full = "Military areas", loadsOnDemand = true, loaded = "airspace" in state.loaded,
                trouble = "airspace" in state.feedTrouble,
                partial = "airspace" in state.feedPartial
                ) {
                settleGod()
                showAirspace = !showAirspace
                if (showAirspace) viewModel.ensureAirspace()
                }
                },
                "AT SEA" to {
                LayerPill("SEA·N.EUROPE", state.vessels.size, Intel.Teal, showVessels || god, full = "Vessels (AIS)", loaded = "vessels" in state.loaded, trouble = "vessels" in state.feedTrouble, partial = "vessels" in state.feedPartial) { settleGod(); showVessels = !showVessels }
                LayerPill("PORTS·GLOBAL", state.ports.size, Color(0xFF7FD4FF), showPorts || god, full = "Ports", loaded = "ports" in state.loaded, trouble = "ports" in state.feedTrouble) { settleGod(); showPorts = !showPorts }
                LayerPill("BUOY", state.buoys.size, Color(0xFF4DD0E1), showBuoys || god, full = "Ocean buoys", loaded = "buoys" in state.loaded, trouble = "buoys" in state.feedTrouble) { settleGod(); showBuoys = !showBuoys }
                LayerPill("TIDE·GLOBAL", state.seaLevel.size, Color(0xFF4DD0E1), showSeaLevel || god, full = "Sea level gauges", loaded = "seaLevel" in state.loaded, trouble = "seaLevel" in state.feedTrouble) { settleGod(); showSeaLevel = !showSeaLevel }
                LayerPill("CABLE", state.cables.size, Color(0xFF7FFFD4), showCables || god, full = "Submarine cables", loaded = "cables" in state.loaded, trouble = "cables" in state.feedTrouble) { settleGod(); showCables = !showCables }
                },
                "THE GROUND MOVING" to {
                LayerPill("SEIS", state.quakes.size, Intel.Coral, showQuakes || god, full = "Earthquakes", loaded = "quakes" in state.loaded, trouble = "quakes" in state.feedTrouble, partial = "quakes" in state.feedPartial) { settleGod(); showQuakes = !showQuakes }
                LayerPill("VOLC·GLOBAL", state.volcanoes.size, Color(0xFFFF4D4D), showVolcanoes || god, full = "Volcanoes", loaded = "volcanoes" in state.loaded, trouble = "volcanoes" in state.feedTrouble) { settleGod(); showVolcanoes = !showVolcanoes }
                LayerPill("STATIONS", state.stations.size, Color(0xFF9AB0C0), showStations || god, full = "Seismic stations", loaded = "stations" in state.loaded, trouble = "stations" in state.feedTrouble) { settleGod(); showStations = !showStations }
                LayerPill("EVT", state.naturalEvents.size, Intel.Amber, showEvents || god, full = "Natural events", loaded = "naturalEvents" in state.loaded, trouble = "naturalEvents" in state.feedTrouble) { settleGod(); showEvents = !showEvents }
                LayerPill("FIRE·GLOBAL", state.fires.size, Color(0xFFFF5A4D), showFires || god, full = "Wildfires", loaded = "fires" in state.loaded, trouble = "fires" in state.feedTrouble, partial = "fires" in state.feedPartial) { settleGod(); showFires = !showFires }
                },
                "WEATHER AND HAZARD" to {
                LayerPill("WX·US", state.weather.size, Color(0xFF7FB2FF), showWeather || god, full = "Weather alerts (US)", loaded = "weather" in state.loaded, trouble = "weather" in state.feedTrouble) { settleGod(); showWeather = !showWeather }
                LayerPill("WARN·GLOBAL", state.worldWarnings.size, Color(0xFFFFD166), showWorldWarnings || god, full = "Severe weather warnings", loaded = "worldWarnings" in state.loaded, trouble = "worldWarnings" in state.feedTrouble) { settleGod(); showWorldWarnings = !showWorldWarnings }
                LayerPill("CYCLONE·GLOBAL", state.cyclones.size, Color(0xFFFF7AC6), showCyclones || god, full = "Tropical cyclones", loaded = "cyclones" in state.loaded, trouble = "cyclones" in state.feedTrouble, partial = "cyclones" in state.feedPartial) { settleGod(); showCyclones = !showCyclones }
                LayerPill("TSUNAMI", state.tsunamis.size, Color(0xFF4DF0FF), showTsunami || god, full = "Tsunami bulletins (Pacific, Caribbean, Indonesia)", loaded = "tsunamis" in state.loaded, trouble = "tsunamis" in state.feedTrouble, partial = "tsunamis" in state.feedPartial) { settleGod(); showTsunami = !showTsunami }
                LayerPill("HAZARD", state.gdacs.size, Color(0xFFFF8A3D), showGdacs || god, full = "Disaster alerts", loaded = "gdacs" in state.loaded, trouble = "gdacs" in state.feedTrouble) { settleGod(); showGdacs = !showGdacs }
                LayerPill("AQI", state.airQuality.size, Color(0xFFB4E66B), showAqi || god, full = "Air quality", loaded = "airQuality" in state.loaded, trouble = "airQuality" in state.feedTrouble, partial = "airQuality" in state.feedPartial) { settleGod(); showAqi = !showAqi }
                },
                "BUILT AND WATCHED" to {
                LayerPill("POWER", state.powerPlants.size, Color(0xFFFFC14D), showPower || god, full = "Power plants", loaded = "powerPlants" in state.loaded, trouble = "powerPlants" in state.feedTrouble) { settleGod(); showPower = !showPower }
                LayerPill("OUTAGE·GLOBAL", state.outages.size, Color(0xFFFF6B6B), showOutages || god, full = "Internet outages", loaded = "outages" in state.loaded, trouble = "outages" in state.feedTrouble) { settleGod(); showOutages = !showOutages }
                LayerPill(
                "CAMERAS", state.cameras.size, Color(0xFFB388FF), showCameras || god, full = "Public cameras", loadsOnDemand = true, loaded = "cameras" in state.loaded,
                trouble = "cameras" in state.feedTrouble,
                partial = "cameras" in state.feedPartial
                ) {
                settleGod()
                showCameras = !showCameras
                if (showCameras) viewModel.ensureCameras()
                }
                LayerPill(
                "SITES", state.sites.size, Color(0xFFD98BFF), showSites || god, full = "Borders, embassies, prisons", loadsOnDemand = true, loaded = "sites" in state.loaded,
                trouble = "sites" in state.feedTrouble
                ) {
                settleGod()
                showSites = !showSites
                if (showSites) viewModel.ensureSites()
                }
                LayerPill(
                "RAD", state.radiation.size, Color(0xFFD4FF4D), showRadiation || god, full = "Radiation monitors", loadsOnDemand = true, loaded = "radiation" in state.loaded,
                trouble = "radiation" in state.feedTrouble
                ) {
                settleGod()
                showRadiation = !showRadiation
                viewModel.ensureRadiation(showRadiation)
                }
                },
                "ABOVE THE WEATHER" to {
                LayerPill("SAT", state.satellites.size, Color(0xFFC0A8FF), showSats || god, full = "Satellites overhead", loaded = "satellites" in state.loaded, trouble = "satellites" in state.feedTrouble) { settleGod(); showSats = !showSats }
                LayerPill("LAUNCH·GLOBAL", state.launches.size, Color(0xFFFFF066), showLaunches || god, full = "Rocket launches", loaded = "launches" in state.loaded, trouble = "launches" in state.feedTrouble) { settleGod(); showLaunches = !showLaunches }
                LayerPill("AURORA", state.aurora.size, Color(0xFF66FFB2), showAurora || god, full = "Aurora", loaded = "aurora" in state.loaded, trouble = "aurora" in state.feedTrouble) { settleGod(); showAurora = !showAurora }
                },
                "BEING REPORTED" to {
                LayerPill("CONFLICT", state.conflicts.size, Color(0xFFFF2E2E), showConflict || god, full = "Armed conflict", loaded = "conflicts" in state.loaded, trouble = "conflicts" in state.feedTrouble) { settleGod(); showConflict = !showConflict }
                LayerPill("NEWS", state.news.size, Color(0xFFFF79C6), showNews || god, full = "News", loaded = "news" in state.loaded, trouble = "news" in state.feedTrouble) { settleGod(); showNews = !showNews }
                if (state.overlayPoints.isNotEmpty()) {
                LayerPill("OVLY", state.overlayPoints.size, Intel.CyanBright, showOverlay || god, full = "Your own overlay", loaded = "overlayPoints" in state.loaded, trouble = "overlayPoints" in state.feedTrouble) { settleGod(); showOverlay = !showOverlay }
                }
                },
            )

            // Appended rather than declared in the list, because the
            // picker draws every heading it is given and this group
            // has one pill that is not always there — so on a fresh
            // install it showed a bare "THIS INVESTIGATION" with
            // nothing under it, which the comment on the pill row
            // above calls worse than no heading.
            val layerGroups: List<Pair<String, @Composable () -> Unit>> =
                if (state.findings.isEmpty()) {
                    feedGroups
                } else {
                    feedGroups + ("THIS INVESTIGATION" to @Composable {
                        LayerPill(
                            "FINDINGS", state.findings.size, Color(0xFFE24FD6),
                            showFindings, full = "Coordinates from this dossier"
                        ) { showFindings = !showFindings }
                    })
                }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                val on = visibleLayers.size
                Text(
                    if (on == 0) "+ ADD LAYERS" else "+ LAYERS · $on ON",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.Cyan,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(50))
                        .background(Intel.Elevated)
                        .border(1.dp, Intel.Cyan, RoundedCornerShape(50))
                        .clickable { picking = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Choose which layers to show, $on on"
                        }
                )
                if (on > 0) {
                    Text(
                        "CLEAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(50))
                            .clickable { clearLayers() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // What is on, and only that: one row, no headings,
            // because LayerPill draws nothing for a layer that is
            // off and a heading over an empty group is noise.
            CompositionLocalProvider(
                LocalLayerFilter provides "",
                LocalShowAllLayers provides false
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    layerGroups.forEach { (_, pills) -> pills() }
                }
            }

            // Nothing on means nothing fetched — the feeds are gated on
            // the layers, deliberately, because a phone on mobile data
            // should not be downloading vessel positions for a map
            // nobody asked to see. But an empty globe with an empty row
            // of pills under it looks like an app that is broken rather
            // than one that is idle, so it says which it is.
            if (!link.online || link.walledGarden) {
                Text(
                    if (!link.online) {
                        "No network. Nothing can be fetched, and every count " +
                            "below is the last thing that was."
                    } else {
                        "This network is not reaching the internet — a sign-in " +
                            "page, or a connection that has dropped. Feeds will " +
                            "fail until it does."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.Danger,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // The one thing a broken cache does that the person can
            // see on their bill. Two of these feeds are seven and
            // fourteen megabytes, and an uncacheable one is fetched
            // again on every poll for as long as the app is open.
            val cacheTrouble by remember(context) {
                (context.applicationContext as? OsintApplication)?.diskCache?.trouble
                    ?: MutableStateFlow<String?>(null)
            }.collectAsStateWithLifecycle()
            cacheTrouble?.let { why ->
                Text(
                    "The feed cache cannot be written ($why). The large " +
                        "feeds will be downloaded again on every refresh " +
                        "until there is room for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.Amber,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (visibleLayers.isEmpty()) {
                Text(
                    "No layers on, so nothing is being fetched and no " +
                        "data is being used. Add a layer to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextLow,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // An empty map is sometimes the right answer, and it
            // always looks like a broken one.
            //
            // Markers are culled to what the map can see. Zoom in far
            // enough and everything the layers hold is somewhere
            // else, so the correct screen is empty — while the pills
            // above go on reporting the layer totals, which is the
            // app disagreeing with itself at exactly the moment it is
            // being careful. Reported from a second phone as "zoom in
            // and all the layers stop working", because that is what
            // it looks like from the outside. Said plainly instead.
            if (visibleLayers.isNotEmpty() && plotted.second > 0) {
                val (shown, total) = plotted
                if (shown == 0) {
                    Text(
                        "$total on the armed layers, none of them in this view. " +
                            "Zoom out or pan to reach them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Intel.Amber,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                } else if (shown < total) {
                    Text(
                        "Showing $shown of $total — the rest are over the " +
                            "horizon, or thinned out of a crowd. Zoom in " +
                            "for the ones here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Intel.TextLow,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            if (picking) {
                LayerPicker(
                    filter = layerFilter,
                    onFilter = { layerFilter = it },
                    onPreset = applyPreset,
                    onClose = { picking = false }
                ) {
                    CompositionLocalProvider(
                        LocalLayerFilter provides layerFilter,
                        LocalShowAllLayers provides true
                    ) {
                        layerGroups.forEach { (title, pills) ->
                            Text(
                                title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Intel.TextLow,
                                modifier = Modifier
                                    .padding(start = 12.dp, top = 10.dp, bottom = 2.dp)
                                    .semantics { heading() }
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                            ) {
                                pills()
                            }
                        }
                    }
                }
            }

            if (panelOpen) {
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
                    .fillMaxSize()
                    .padding(
                        top = if (overlay) 2.dp
                        else with(density) { panelPx.toDp() } + 2.dp
                    )
                    .clipToBounds()
                    // Measured on the container both views fill, not
                    // on the globe. Measuring it on the globe meant
                    // that a session which started on the map had no
                    // size, and zooming the map out could never hand
                    // back to a sphere whose dimensions were unknown.
                    .onSizeChanged { globeSize = it.width to it.height }
            ) {
                val layers = MapLayers(
                    aircraft = vis(showAircraft, visibleAircraft),
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
                    // Not through vis(): these are not a feed, so
                    // ALL LAYERS has no business arming them.
                    findings = if (showFindings) state.findings else emptyList()
                )
                run {
                    // Thinned before the sphere ever sees it. The globe
                    // projects every point it is handed on every frame,
                    // and the fire detections alone are now six figures
                    // — a number that cannot be turned into trigonometry
                    // sixty times a second.
                    //
                    // It used to be thinned for the whole Earth and
                    // never again, on the reasoning that the far
                    // hemisphere is culled at draw time anyway. That
                    // reasoning is right about correctness and wrong
                    // about what you see: the budget was being spent
                    // on the whole planet while the camera was looking
                    // at a country. The visible fraction of a sphere
                    // is (1 - cos(span/2))/2, so at the 22 degrees
                    // where the globe hands over to tiles, 0.9% of the
                    // survivors are on screen — about twenty markers
                    // shared between thirty layers, which is under one
                    // each. Every layer emptied together as the user
                    // zoomed in, with the pills still reporting full
                    // counts. Reported as "zooming into the map stops
                    // every layer working", and it is the globe, which
                    // is the view that opens.
                    //
                    // So the budget follows the camera — on a real
                    // move, and never on a frame.
                    //
                    // The radius is to the corner of the view, not
                    // across its width: a point at angle θ from the
                    // camera lands at radius·sin(θ) from the centre
                    // of the disc in any direction, so the furthest
                    // visible point is at a corner. Measuring across
                    // the width culled markers along the top and
                    // bottom of a portrait globe.
                    //
                    // And the step is snapped to a power of two
                    // before it is used as a key. It was a plain
                    // function of the zoom, and the zoom changes on
                    // every frame of a pinch, a flick and a
                    // double-tap flight — so produceState cancelled
                    // and relaunched a full pass over six figures of
                    // points sixty times a second, discarded every
                    // one of them, and left the markers frozen at
                    // the pre-gesture set for the whole gesture.
                    // That is precisely the per-frame cost this is
                    // here to avoid.
                    val visible = GlobeHandoff.visibleRadiusDegrees(
                        globeSize.first, globeSize.second, globeCamera.zoom
                    )
                    val rawStep = (visible / 3.0).coerceAtLeast(1.0)
                    val step = 2.0.pow(ceil(log2(rawStep)))
                    // A degree of longitude is a shorter distance the
                    // further from the equator you are, so a key in
                    // plain degrees recomputes several times as often
                    // near the poles for the same movement across the
                    // ground. Widened by the same factor the gestures
                    // already use.
                    val lonStep = (step / cos(Math.toRadians(globeCamera.lat.toDouble()))
                        .coerceAtLeast(0.15)).coerceAtMost(180.0)
                    val keyLat = kotlin.math.round(globeCamera.lat / step)
                    val keyLon = kotlin.math.round(globeCamera.lon / lonStep)
                    // Keyed on the version, not on the layers.
                    //
                    // `layers` is a data class holding thirty lists,
                    // so using it as a key compares every point in
                    // every one of them, element by element, on the
                    // main thread, on every recomposition — the cost
                    // of the thinning again, paid where it hurts
                    // most. The version changes only when a feed
                    // returns something genuinely different, and
                    // `visibleLayers` covers the other thing that
                    // changes what is drawn: which layers are on.
                    val globePlan by produceState(
                        GlobePlan(emptyList(), 0),
                        state.layerVersion, visibleLayers, god,
                        keyLat, keyLon, step
                    ) {
                        value = withContext(Dispatchers.Default) {
                            planGlobe(
                                layers.points(),
                                centre = globeCamera.lat.toDouble() to globeCamera.lon.toDouble(),
                                // The corner radius, plus the
                                // furthest the camera can drift
                                // before the key flips — half a
                                // step in latitude and half a
                                // longitude step, which at this
                                // latitude is worth at most half a
                                // step of ground.
                                keepWithinDegrees = visible + step
                            )
                        }
                    }
                    val globePoints = globePlan.points
                    // The globe reports what it drew, exactly as the
                    // flat map does, so the sentence below the pills
                    // works on the view that opens and not only on the
                    // one you have to switch to.
                    LaunchedEffect(globePlan) {
                        plotted = globePoints.size to globePlan.total
                    }
                    GlobeView(
                        points = globePoints,
                        camera = globeCamera,
                        onCamera = { next -> globeCamera = next },
                        onInspect = { p ->
                            inspected = Inspected(
                                p.kind, p.title.orEmpty().ifBlank { p.kind },
                                p.lat, p.lon, p.url
                            )
                        },
                        onPress = { lat, lon -> tapped = lat to lon },
                        onLongPress = { lat, lon, zoom ->
                            areaWanted = Triple(
                                lat, lon, AreaReport.radiusForZoom(zoom)
                            )
                        }
                    )
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
                                    areaWanted = Triple(
                                        tLat, tLon, AreaReport.radiusForZoom(zoomNow)
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
                            } else {
                                // Turning it off has to disarm radiation
                                // too. The view model keeps its own flag
                                // for that layer, so without this every
                                // pan went on querying Safecast for the
                                // rest of the session for something
                                // nobody could see — which is exactly
                                // what clearLayers already says.
                                viewModel.ensureRadiation(false)
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
            onDismissRequest = { area = null; areaWanted = null },
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
                    // Before the list, not after it. The reader takes
                    // a layer this report does not mention as empty
                    // here, and for a layer whose feed is down or
                    // whose circle never reached this point that was
                    // never what it meant.
                    if (report.caveats.isNotEmpty()) {
                        Text(
                            "NOT ANSWERED FOR HERE · ${report.caveats.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Amber,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                        report.caveats.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = Intel.TextMid,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
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
                                        areaWanted = null
                                    }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    AreaReport.fmtDistance(item),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Intel.TextLow,
                                    fontFamily = FontFamily.Monospace,
                                    // A minimum, not a width. The same
                                    // column in the dossier already
                                    // says why: at a large font size a
                                    // fixed 58dp either wraps the
                                    // distance onto two lines or loses
                                    // its end.
                                    modifier = Modifier.widthIn(min = 58.dp)
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
                    areaWanted = null
                }) {
                    Text(
                        "INVESTIGATE POINT",
                        style = MaterialTheme.typography.labelMedium,
                        color = Intel.Cyan
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { area = null; areaWanted = null }) {
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
                // The sibling dialogs scroll; this one did not, and
                // AlertDialog clips its text slot rather than
                // scrolling it. In landscape that put OPEN SOURCE —
                // the last line — off the bottom with no way to
                // reach it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    } else {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "This marker is a place rather than a name, so " +
                                "there is nothing here to search for. " +
                                "Investigating it looks at what is around it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow
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
            // A button that is there and does nothing is worse than one
            // that is not there.
            //
            // Many markers are a place rather than a name — a camera, a
            // cable landing, a weather alert — and searching their
            // caption finds nothing, so INVESTIGATE was disabled for
            // them. The comment on investigableQuery says those "fall
            // through to LOCATION"; they did not. The dialog kept a
            // greyed INVESTIGATE in the usual place and a person tapped
            // it and nothing happened, which reads as the app being
            // broken rather than as the marker having no name in it.
            //
            // So when there is nothing to search for, the action that
            // does work takes the slot and says what it will do.
            confirmButton = {
                val query = investigableQuery(obj)
                if (query != null) {
                    TextButton(
                        onClick = {
                            viewModel.onQueryChange(query)
                            viewModel.onSearch()
                            inspected = null
                        }
                    ) {
                        Text(
                            "INVESTIGATE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.Cyan
                        )
                    }
                } else {
                    TextButton(
                        onClick = {
                            viewModel.onMapClick(obj.lat, obj.lon)
                            inspected = null
                        }
                    ) {
                        Text(
                            "INVESTIGATE THE PLACE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.Cyan
                        )
                    }
                }
            },
            dismissButton = {
                if (investigableQuery(obj) != null) {
                    TextButton(onClick = {
                        viewModel.onMapClick(obj.lat, obj.lon); inspected = null
                    }) {
                        Text(
                            "LOCATION",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.TextMid
                        )
                    }
                } else {
                    TextButton(onClick = { inspected = null }) {
                        Text(
                            "CLOSE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.TextMid
                        )
                    }
                }
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
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Once a second, and only while there is something to see.
    //
    // The composition is retained when the Activity stops, so a plain
    // LaunchedEffect(Unit) kept ticking behind a dark screen all
    // night. The thirty feed polls are gated on `foreground`; these
    // two loops were written later and were not.
    LaunchedEffect(owner) {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                utc = fmt.format(java.util.Date())
                kotlinx.coroutines.delay(1000)
            }
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

/** Close enough to see a town, which is what a coordinate search means. */
private const val FOCUS_TILE_ZOOM = 9.0

/** How far the globe has to turn, in degrees, before it is worth
 *  telling the rest of the app about. */
private const val VIEWPORT_STEP = 2f

/** And how long it has to stay there. A drag emits sixty cameras a
 *  second and a viewport report can start a network query. */
private const val VIEWPORT_SETTLE_MS = 500L

/** What the object inspector is showing: which layer the marker came from,
 *  its label, where it is, and any link that layer carries. */
data class Inspected(
    val kind: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val url: String? = null
)

// ---------------------------------------------------------------
// Savers for the small pieces of screen state that used to be lost
// on a rotation or a tab switch.
//
// listSaver rather than a parcelable: these are a handful of
// primitives each, a Bundle holds them without ceremony, and nothing
// outside this file needs the type to be anything in particular. A
// null is stored as an empty list, which is the one shape listSaver
// cannot confuse with a value.
// ---------------------------------------------------------------

internal val InspectedSaver: Saver<Inspected?, Any> = listSaver(
    save = {
        if (it == null) emptyList()
        else listOf(it.kind, it.label, it.lat, it.lon, it.url ?: "")
    },
    restore = {
        if (it.size < 5) null
        else Inspected(
            it[0] as String,
            it[1] as String,
            it[2] as Double,
            it[3] as Double,
            (it[4] as String).ifBlank { null }
        )
    }
)

internal val TripleDoubleSaver: Saver<Triple<Double, Double, Double>?, Any> = listSaver(
    save = { if (it == null) emptyList() else listOf(it.first, it.second, it.third) },
    restore = { if (it.size < 3) null else Triple(it[0], it[1], it[2]) }
)

internal val DoublePairSaver: Saver<Pair<Double, Double>?, Any> = listSaver(
    save = { if (it == null) emptyList() else listOf(it.first, it.second) },
    restore = { if (it.size < 2) null else it[0] to it[1] }
)

internal val IntPairSaver: Saver<Pair<Int, Int>, Any> = listSaver(
    save = { listOf(it.first, it.second) },
    restore = { if (it.size < 2) 0 to 0 else it[0] to it[1] }
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

/**
 * What the person typed into the layer filter, if anything.
 *
 * A composition local rather than a parameter: there are thirty-three
 * pills and the next one written would be the one that forgot to
 * accept it.
 */
private val LocalLayerFilter = compositionLocalOf { "" }

/**
 * Whether a pill should draw even when its layer is off.
 *
 * False on the map, where the row is what you have switched on. True
 * inside the picker, where the point is to see everything there is.
 */
private val LocalShowAllLayers = compositionLocalOf { false }

/**
 * The whole list of layers, one tap from the map.
 *
 * A sheet rather than a screen: choosing a layer is something you do
 * while looking at the map, and anything that replaces the map makes
 * you forget what you were looking for.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LayerPicker(
    filter: String,
    onFilter: (String) -> Unit,
    onPreset: (String) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Intel.Base,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Text(
                    "LAYERS",
                    style = MaterialTheme.typography.titleSmall,
                    color = Intel.TextHi,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                Text(
                    "DONE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.Cyan,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(50))
                        .background(Intel.Elevated)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Text(
                "START WITH A QUESTION",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.Cyan,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                LAYER_PRESETS.forEach { preset ->
                    Text(
                        preset,
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Base,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(50))
                            .background(Intel.Cyan)
                            .clickable {
                                onPreset(preset)
                                onClose()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            LayerFilterField(filter, onFilter)
            Text(
                "Or tap one below to turn it on. A layer only fetches while " +
                    "it is on, so an empty map costs nothing.",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

/** Narrows the pill row by name. */
@Composable
private fun LayerFilterField(value: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = Intel.TextHi),
            cursorBrush = SolidColor(Intel.Cyan),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(Intel.Surface)
                .border(1.dp, Intel.Hairline, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .semantics { contentDescription = "Filter layers by name" },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "filter layers — fire, sea, global…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Intel.TextLow
                    )
                }
                inner()
            }
        )
        if (value.isNotEmpty()) {
            Text(
                "CLEAR",
                style = MaterialTheme.typography.labelSmall,
                color = Intel.Cyan,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .clickable { onChange("") }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LayerPill(
    label: String,
    count: Int,
    color: Color,
    on: Boolean,
    /**
     * What this layer is, in words.
     *
     * The abbreviations are right for the row over the map, where
     * there are a handful of them and space for none — but the picker
     * is a list of thirty-three of them, and "SEIS", "EVT", "OVLY" and
     * "ATC·US" are only legible to somebody who already knows what
     * they are looking at. The clutter in a list of four-letter codes
     * is not the length of the list.
     */
    full: String = "",
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
    /**
     * Set when the feed answered but not all of its agencies did.
     * Distinct from [trouble], which means nothing current came back
     * at all: this count is real, and smaller than the world.
     */
    partial: Boolean = false,
    onClick: () -> Unit
) {
    // Filtered here rather than at the call sites. Thirty-three of
    // those exist and a parameter on every one of them would be a
    // parameter forgotten on the next layer added; this way a new
    // pill is filterable the moment it is written.
    // Off layers are not drawn on the map's own row — that row is
    // what you have switched on, and before today it was every feed
    // this app has, which is thirty-three pills of clutter above a
    // map you cannot see.
    val picking = LocalShowAllLayers.current
    if (!picking && !on) return
    val filter = LocalLayerFilter.current
    // Matched against both, so "quake" and "SEIS" each find the same
    // pill and nobody has to learn the codes to search.
    if (filter.isNotBlank() &&
        !label.contains(filter.trim(), ignoreCase = true) &&
        !full.contains(filter.trim(), ignoreCase = true)
    ) {
        return
    }
    val shownLabel = if (picking && full.isNotBlank()) full else label

    // A screen reader could not tell whether a layer was on: the pill was a
    // plain clickable with the state carried only by colour, which is also
    // invisible to roughly one man in twelve.
    val stateWord = when {
        trouble -> "not reporting"
        partial -> "on, partial coverage"
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
                // The words, not the code: "SEIS layer" read aloud
                // is not a description of anything.
                contentDescription =
                    (if (full.isNotBlank()) full else label) + " layer"
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (on) color else Intel.TextLow))
        Spacer(Modifier.width(6.dp))
        val shown = when {
            trouble && count > 0 -> "$count!"
            trouble -> "!"
            // Answered, but not by everyone it asks. The count is
            // real and the coverage is not the world, and before this
            // the two were the same pill.
            partial && on -> "$count~"
            // Nothing is being fetched for a layer that is switched
            // off, so it has no count to report and no fetch in
            // flight. This has to come first: opening the picker on a
            // fresh install showed all thirty-three pills on the
            // loading marker, as though thirty-three requests were
            // pending, because "has not loaded" and "is loading" were
            // the same test. It also beat the count test, so an
            // on-demand layer that was switched off went on showing
            // the number it found last time.
            !on -> if (loadsOnDemand) "tap" else ""
            count > 0 -> "$count"
            // A fetch that has answered with nothing says "0"; only one
            // still in flight says "…". These used to be the same, so a
            // layer whose fetch failed sat on the loading marker for ever.
            !loaded -> "…"
            else -> "0"
        }
        Text(
            if (shown.isEmpty()) shownLabel else "$shownLabel $shown",
            style = MaterialTheme.typography.labelSmall,
            color = when {
                trouble -> Intel.Danger
                partial && on -> Intel.Amber
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
    // Saveable, because the dialog that holds them is.
    //
    // `showSettings` survives a rotation, so the dialog came back
    // and every field in it snapped to the persisted value, losing
    // whatever had been typed. In-progress text is a stronger case
    // for this than anything else on the screen.
    var backend by rememberSaveable { mutableStateOf(backendUrl) }
    var tar by rememberSaveable { mutableStateOf(tar1090Url) }
    var ais by rememberSaveable { mutableStateOf(aisUrl) }
    var overlay by rememberSaveable { mutableStateOf(overlayUrl) }
    var sec by rememberSaveable { mutableStateOf(secEmail) }
    var token by rememberSaveable { mutableStateOf(backendToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Intel.Surface,
        titleContentColor = Intel.TextHi,
        textContentColor = Intel.TextMid,
        title = { Text("LIVE FEEDS", style = MaterialTheme.typography.titleSmall, color = Intel.TextHi) },
        text = {
            // Six fields, and the last of them was behind the
            // keyboard.
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                FeedNote(
                    "Nothing here needs filling in. Every layer and every " +
                        "source works with all of these blank; they are for " +
                        "widening a feed that only covers one region, or for " +
                        "the two sources that need something from you."
                )
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

                // Folded away, because almost nobody has one.
                //
                // A fresh install opened this screen on six empty
                // boxes, two of which are a server URL and a bearer
                // token for a backend the user would have had to
                // deploy themselves. Everything above works with
                // nothing typed into it; this does not exist unless
                // you built it, and presenting it as setup makes a
                // working app look unconfigured.
                var advanced by rememberSaveable { mutableStateOf(backend.isNotBlank()) }
                Text(
                    if (advanced) "ADVANCED ▾" else "ADVANCED ▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = Intel.Cyan,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .minimumInteractiveComponentSize()
                        .clickable { advanced = !advanced }
                )
                if (advanced) {
                    FeedHeader("CLOUD BACKEND  ·  optional tools")
                    FeedNote(
                        "Only if you have deployed the companion backend " +
                            "yourself. Nothing on this screen above here needs " +
                            "it, and the tools that use it stay hidden while it " +
                            "is blank."
                    )
                    FeedNote(
                        "Your backend refuses unauthenticated requests, so it " +
                            "needs the same token you set as " +
                            "OSINT_BACKEND_TOKEN there. Without that, anyone " +
                            "who found its URL could run scans from your " +
                            "account."
                    )
                    SettingsField(backend, { backend = it }, "Backend URL", "https://your-api.onrender.com")
                    // A bearer token in plain text on a screen somebody may
                    // be showing to a colleague, or screenshotting.
                    SettingsField(
                        token, { token = it }, "Backend token",
                        "the value of OSINT_BACKEND_TOKEN", secret = true
                    )
                }
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

