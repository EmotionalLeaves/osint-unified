package com.osint.unified

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osint.unified.ui.diagnostics.DiagnosticsScreen
import com.osint.unified.ui.diagnostics.FeedProbe
import com.osint.unified.ui.forensics.ForensicsScreen
import com.osint.unified.ui.framework.FrameworkScreen
import com.osint.unified.ui.godeye.GodEyeViewModel
import com.osint.unified.ui.godeye.GodEyeViewScreen
import com.osint.unified.ui.history.HistoryScreen
import com.osint.unified.ui.history.HistoryViewModel
import com.osint.unified.ui.investigation.InvestigationScreen
import com.osint.unified.ui.about.MoreScreen
import com.osint.unified.ui.common.OsintNavBar
import com.osint.unified.ui.watch.WatchScreen
import com.osint.unified.ui.theme.OsintTheme
import com.osint.unified.work.WatchWorker

class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * Feeds stop while the app is not on screen.
     *
     * Twenty-six polling loops carried on in the background, which is a
     * phone bill and a flat battery for data nobody is looking at.
     */
    override fun onStart() {
        super.onStart()
        godEyeViewModel.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        godEyeViewModel.setForeground(false)
        // Re-locked the moment it leaves the screen, which is the moment
        // the phone is likely to change hands.
        if ((application as OsintApplication).settings.appLockEnabled) {
            locked.value = true
        }
    }

    /** Whether the app is showing its lock screen rather than itself. */
    private val locked = androidx.compose.runtime.mutableStateOf(false)


    private val godEyeViewModel: GodEyeViewModel by viewModels {
        val app = application as OsintApplication
        GodEyeViewModel.Factory(app.router, app.repository, app.aircraftDataSource, app.aisDataSource, app.earthquakeDataSource, app.naturalEventsDataSource, app.overlayDataSource, app.weatherDataSource, app.firesDataSource, app.satellitesDataSource, app.newsDataSource, app.gdacsDataSource, app.volcanoDataSource, app.worldWarningsDataSource, app.seaLevelDataSource, app.outageDataSource, app.auroraDataSource, app.buoyDataSource, app.metarDataSource, app.milAircraftDataSource, app.airQualityDataSource, app.seismicStationDataSource, app.conflictDataSource, app.airportDataSource, app.powerPlantDataSource, app.cctvDataSource, app.airspaceDataSource, app.spaceWeatherDataSource, app.cableLandingDataSource, app.cycloneDataSource, app.tsunamiDataSource, app.radiationDataSource, app.airportStatusDataSource, app.availableLocalTools, app.settings, app.exporter)
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        val app = application as OsintApplication
        HistoryViewModel.Factory(app.repository, app.router, app.exporter)
    }

    /**
     * Asked once, and nothing depends on the answer: a refused permission
     * makes a watch silent, not broken — the change is still recorded and
     * shown on the watch screen.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Bumped when a watch notification is tapped.
     *
     * A notification that does nothing when tapped is a notification that
     * teaches people to swipe it away. The value is a timestamp rather
     * than a flag, so a second tap while the app is already on the watch
     * screen is still a distinct signal, and the composition can tell a
     * handled one from a new one across a rotation.
     */
    private val openWatchAt = mutableLongStateOf(0L)
    private val openWatchId = mutableLongStateOf(-1L)

    /**
     * Something shared in from another app, waiting to be dealt with.
     *
     * Text becomes a search; an image goes to forensics. Both are bumped
     * with a timestamp rather than set as a flag, so sharing the same
     * thing twice is still two events.
     */
    private val sharedAt = mutableLongStateOf(0L)
    private val sharedText = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val sharedImage = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    private fun consume(intent: Intent?) {
        if (intent == null) return
        if (intent.action == WatchWorker.ACTION_OPEN_WATCH) {
            openWatchId.longValue = intent.getLongExtra(WatchWorker.EXTRA_WATCH_ID, -1L)
            openWatchAt.longValue = System.currentTimeMillis()
            return
        }

        // An investigation should start where you found the thing, not
        // after a round trip through the clipboard.
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }

        val image = if (intent.action == Intent.ACTION_SEND &&
            intent.type?.startsWith("image/") == true
        ) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else {
            null
        }

        if (text == null && image == null) return
        sharedText.value = text
        sharedImage.value = image
        sharedAt.longValue = System.currentTimeMillis()
    }

    /**
     * The one interesting thing in a shared message.
     *
     * Share sheets hand over a sentence with a link in it far more often
     * than a bare identifier — "look at this https://example.com/u/jdoe"
     * — and classifying the sentence gets nowhere. A URL in the text is
     * what was meant; failing that, the text itself.
     */
    private fun targetIn(raw: String): String {
        Regex("""https?://\S+""").find(raw)?.value?.let { return it.trimEnd('.', ',', ')') }
        return raw.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(300) ?: raw
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and navigation bars. The console fills the
        // screen; a black strip at each end was wasted space on a map.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The recent-apps thumbnail is a screenshot of whatever was on
        // screen, and the app is usually showing who you are looking at.
        // It also blocks screen recording of a dossier.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        locked.value = (application as OsintApplication).settings.appLockEnabled
        consume(intent)
        // Rather than overriding onNewIntent, whose signature has moved
        // between versions of androidx.activity.
        addOnNewIntentListener { consume(it) }
        setContent {
            OsintTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val settings = (application as OsintApplication).settings
                    if (locked.value) {
                        com.osint.unified.ui.common.AppLockScreen(this@MainActivity) {
                            locked.value = false
                        }
                        return@Surface
                    }

                    var accepted by rememberSaveable { mutableStateOf(settings.termsAccepted) }
                    if (!accepted) {
                        // Said once, before the app is used, rather than only
                        // in a README the user never sees.
                        com.osint.unified.ui.about.FirstRunScreen {
                            settings.termsAccepted = true
                            accepted = true
                        }
                        return@Surface
                    }

                    // Asked after the terms, not over them. The system
                    // dialog was launched from onCreate, so it appeared on
                    // top of the one screen in the app whose whole purpose
                    // is being read before anything else happens.
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            runCatching {
                                notificationPermission.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    }

                    var screen by rememberSaveable { mutableStateOf("godeye") }
                    val godState by godEyeViewModel.state.collectAsStateWithLifecycle()

                    // A new search (from the bar or a globe tap) opens the
                    // investigation. The signal that was already acted on is
                    // remembered across configuration changes: without that,
                    // a new composition after a rotation saw the same
                    // non-zero signal and forced the dossier open again, so
                    // once you had searched you could not rotate the phone
                    // anywhere else in the app.
                    var handledNav by rememberSaveable { mutableLongStateOf(0L) }
                    LaunchedEffect(godState.navSignal) {
                        if (godState.navSignal > handledNav) {
                            handledNav = godState.navSignal
                            screen = "investigation"
                        }
                    }

                    var handledShare by rememberSaveable { mutableLongStateOf(0L) }
                    val shareSignal = sharedAt.longValue
                    LaunchedEffect(shareSignal) {
                        if (shareSignal <= handledShare) return@LaunchedEffect
                        handledShare = shareSignal
                        val text = sharedText.value
                        when {
                            sharedImage.value != null -> screen = "forensics"
                            text != null -> {
                                godEyeViewModel.onQueryChange(targetIn(text))
                                godEyeViewModel.onSearch()
                            }
                        }
                    }

                    var handledWatch by rememberSaveable { mutableLongStateOf(0L) }
                    val watchSignal = openWatchAt.longValue
                    LaunchedEffect(watchSignal) {
                        if (watchSignal > handledWatch) {
                            handledWatch = watchSignal
                            screen = "watch"
                        }
                    }

                    // System back returns to the globe from any sub-screen.
                    // The tool directory is included: its own handler takes
                    // precedence while it is inside a folder, so this one
                    // only sees the press when it is at the root. Excluding
                    // it meant back at the root of TOOLS closed the app.
                    BackHandler(enabled = screen != "godeye") {
                        // From a dossier reached by a pivot, back belongs to
                        // the investigation it came out of. An enquiry is a
                        // chain, and leaping to the globe from three steps in
                        // loses the thread.
                        screen = when {
                            screen == "investigation" && godState.breadcrumb.size > 1 -> {
                                godEyeViewModel.onOpenParent()
                                screen
                            }
                            // These were opened from MORE, so back belongs
                            // there and not two levels up at the globe.
                            screen in setOf("forensics", "diagnostics", "sources") -> "more"
                            else -> "godeye"
                        }
                    }

                    // How many changes on watched targets have not been
                    // looked at, so the bar can say so rather than leaving
                    // the one time-sensitive thing in the app silent.
                    var unseenWatches by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(0)
                    }
                    LaunchedEffect(Unit) {
                        (application as OsintApplication).watchRepository.observeUnseen()
                            .collect { rows -> unseenWatches = rows.sumOf { it.unseen } }
                    }

                    // A page in the isolated browser can become a case
                    // without leaving it: the browser asks here, because it
                    // is drawn above the navigation and has no other way to.
                    val investigateFromBrowser by com.osint.unified.ui.common
                        .InAppNavigation.investigateTarget.collectAsStateWithLifecycle()
                    var handledBrowserNav by rememberSaveable { mutableLongStateOf(0L) }
                    LaunchedEffect(investigateFromBrowser) {
                        val request = investigateFromBrowser ?: return@LaunchedEffect
                        if (request.second <= handledBrowserNav) return@LaunchedEffect
                        handledBrowserNav = request.second
                        godEyeViewModel.onQueryChange(request.first)
                        godEyeViewModel.onSearch()
                    }

                    val snapshot by com.osint.unified.ui.common.InAppNavigation
                        .snapshotTaken.collectAsStateWithLifecycle()
                    var handledSnapshot by rememberSaveable { mutableLongStateOf(0L) }
                    LaunchedEffect(snapshot) {
                        val taken = snapshot ?: return@LaunchedEffect
                        if (taken.at <= handledSnapshot) return@LaunchedEffect
                        handledSnapshot = taken.at
                        godEyeViewModel.onSnapshot(taken.url, taken.title, taken.html)
                    }

                    // Anything the app found opens here rather than in the
                    // signed-in browser.
                    val privateUrl by com.osint.unified.ui.common.PrivateBrowserHost
                        .url.collectAsStateWithLifecycle()
                    privateUrl?.let { u ->
                        com.osint.unified.ui.common.PrivateBrowserDialog(u) {
                            com.osint.unified.ui.common.PrivateBrowserHost.close()
                        }
                    }

                    // Each tab keeps its own saved state while another is
                    // on screen. Without this the globe's composable left
                    // the tree on every tab switch and took its
                    // rememberSaveable values with it: layers you had
                    // turned off came back on and started polling again,
                    // and the map jumped back to the last search at zoom
                    // nine instead of where you had left it.
                    val tabState = androidx.compose.runtime.saveable
                        .rememberSaveableStateHolder()
                    Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    tabState.SaveableStateProvider(screen) {
                    when (screen) {
                        "investigation" -> InvestigationScreen(
                            godEyeViewModel,
                            (application as OsintApplication).watchRepository,
                            onBack = { screen = "godeye" }
                        )
                        "history" -> HistoryScreen(
                            historyViewModel,
                            onReopen = { target ->
                                godEyeViewModel.onQueryChange(target)
                                godEyeViewModel.onSearch()
                            },
                            onBack = { screen = "godeye" }
                        )
                        "watch" -> WatchScreen(
                            (application as OsintApplication).watchRepository,
                            focusWatchId = openWatchId.longValue.takeIf { it > 0 },
                            onOpen = { target ->
                                godEyeViewModel.onQueryChange(target)
                                godEyeViewModel.onSearch()
                            },
                            onBack = { screen = "godeye" }
                        )
                        "forensics" -> ForensicsScreen(
                            (application as OsintApplication).exifReader,
                            sharedImage = sharedImage.value,
                            onBack = { screen = "more" }
                        )
                        "diagnostics" -> {
                            val app = application as OsintApplication
                            DiagnosticsScreen(
                                app.executorFactory,
                                feeds = listOf(
                                    FeedProbe("Aircraft (OpenSky/adsb.fi)") { app.aircraftDataSource.fetch().size },
                                    FeedProbe("Military ADS-B") { app.milAircraftDataSource.fetch().size },
                                    FeedProbe("Vessels (AIS \u00b7 Fintraffic + Kystverket)") { app.aisDataSource.fetch().size },
                                    FeedProbe("Earthquakes (USGS)") { app.earthquakeDataSource.fetch().size },
                                    FeedProbe("Natural events (EONET)") { app.naturalEventsDataSource.fetch().size },
                                    FeedProbe("Wildfires (NASA FIRMS)") { app.firesDataSource.fetch().size },
                                    FeedProbe("Volcanoes (Smithsonian GVP)") { app.volcanoDataSource.fetch().size },
                                    FeedProbe("World warnings (WMO SWIC)") { app.worldWarningsDataSource.fetch().size },
                                    FeedProbe("Sea level (IOC/UNESCO)") { app.seaLevelDataSource.fetch().size },
                                    FeedProbe("Internet outages (IODA)") { app.outageDataSource.fetch().size },
                                    FeedProbe("Disasters (GDACS)") { app.gdacsDataSource.fetch().size },
                                    FeedProbe("Weather alerts (NWS \u00b7 US)") { app.weatherDataSource.fetch().size },
                                    FeedProbe("Aurora (SWPC)") { app.auroraDataSource.fetch().size },
                                    FeedProbe("News (GDELT)") { app.newsDataSource.fetch().size },
                                    FeedProbe("Ocean buoys (NDBC)") { app.buoyDataSource.fetch().size },
                                    FeedProbe("METAR (AWC)") { app.metarDataSource.fetch().size },
                                    FeedProbe("Satellites (Celestrak)") { app.satellitesDataSource.fetch().size },
                                    FeedProbe("Air quality (Open-Meteo)") { app.airQualityDataSource.fetch().size },
                                    FeedProbe("Seismic stations (IRIS)") { app.seismicStationDataSource.fetch().size },
                                    FeedProbe("Conflicts (Wikidata)") { app.conflictDataSource.fetch().size },
                                    FeedProbe("Airports (bundled)") { app.airportDataSource.fetch().size },
                                    FeedProbe("Power plants (bundled)") { app.powerPlantDataSource.fetch().size },
                                    FeedProbe("Cameras (OpenStreetMap + Caltrans)") { app.cctvDataSource.fetch().size },
                                    FeedProbe("Airspace (FAA \u00b7 US + OSM military)") { app.airspaceDataSource.fetch().size },
                                    FeedProbe("Space weather (SWPC)") { app.spaceWeatherDataSource.fetch().alerts.size + 1 },
                                    FeedProbe("Cable landings") { app.cableLandingDataSource.fetch().size },
                                    FeedProbe("Tropical cyclones (NHC + JTWC)") { app.cycloneDataSource.fetch().size },
                                    FeedProbe("Tsunami bulletins (NTWC/PTWC)") { app.tsunamiDataSource.fetch().size },
                                    FeedProbe("Airport status (FAA \u00b7 US)") { app.airportStatusDataSource.fetch().size },
                                    FeedProbe("Radiation (Safecast archive)") { app.radiationDataSource.fetch(35.65, 139.84, 8.0).size },
                                    FeedProbe("Custom overlay") { app.overlayDataSource.fetch().size }
                                ),
                                onBack = { screen = "more" }
                            )
                        }
                        "sources" -> com.osint.unified.ui.about.SourcesScreen(
                            onBack = { screen = "more" }
                        )
                        "more" -> MoreScreen(
                            settings = (application as OsintApplication).settings,
                            lockAvailable = com.osint.unified.ui.common.AppLock
                                .available(this@MainActivity),
                            onOpenForensics = { screen = "forensics" },
                            onOpenDiagnostics = { screen = "diagnostics" },
                            onOpenSources = { screen = "sources" }
                        )
                        "framework" -> FrameworkScreen(
                            initialTarget = godState.observable?.raw.orEmpty(),
                            onBack = { screen = "godeye" }
                        )
                        else -> GodEyeViewScreen(godEyeViewModel)
                    }
                    }
                    }
                    // Named destinations along the bottom, within reach of a
                    // thumb — on every screen, including the tool
                    // directory. Hiding it there left that tab with no way
                    // out but a system back that closed the app.
                    OsintNavBar(screen, unseenWatches) { target ->
                        screen = target
                    }
                    }
                }
            }
        }
    }
}
