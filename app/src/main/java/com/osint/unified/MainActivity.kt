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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
    /**
     * Whether the app is on screen, recorded rather than applied.
     *
     * Applying it here meant touching the view model, which resolves
     * the view-model factory, which resolves the database — on the
     * main thread, before the first frame, racing the warm-up thread
     * that exists to do exactly that work off it. The composition
     * applies it once the database is open; see OsintApplication.ready.
     */
    private val foreground = androidx.compose.runtime.mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        foreground.value = true
    }

    override fun onStop() {
        super.onStop()
        foreground.value = false
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
        GodEyeViewModel.Factory(app.router, app.repository, app.aircraftDataSource, app.aisDataSource, app.earthquakeDataSource, app.naturalEventsDataSource, app.overlayDataSource, app.weatherDataSource, app.firesDataSource, app.satellitesDataSource, app.newsDataSource, app.gdacsDataSource, app.volcanoDataSource, app.worldWarningsDataSource, app.seaLevelDataSource, app.outageDataSource, app.portDataSource, app.launchDataSource, app.sitesDataSource, app.auroraDataSource, app.buoyDataSource, app.metarDataSource, app.milAircraftDataSource, app.airQualityDataSource, app.seismicStationDataSource, app.conflictDataSource, app.airportDataSource, app.powerPlantDataSource, app.cctvDataSource, app.airspaceDataSource, app.spaceWeatherDataSource, app.cableLandingDataSource, app.cycloneDataSource, app.tsunamiDataSource, app.radiationDataSource, app.airportStatusDataSource, app.availableLocalTools, app.settings, app.exporter)
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

    /** Set on an intent once it has been acted on. */
    private val consumedKey = "com.osint.unified.CONSUMED"

    private val sharedText = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val sharedImage = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    /**
     * A shared target held back because a search was already running.
     *
     * onSearch cancels every in-flight job, nulls the current
     * investigation and drops the pending notes write before it decides
     * whether to fan out — so "prepared, not fired" was true of the new
     * target and said nothing about the old one. RUN ALL forty sources
     * deep, a link shared in from another app, and the lot was gone
     * with no warning and no undo. It is offered instead, and the
     * person decides.
     */
    private val pendingShare = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * The share this activity has already acted on, as its content.
     *
     * [consumedKey] is an extra put on the Intent object, which is
     * right for a configuration change — the same object comes back —
     * and useless after process death, where the system rebuilds the
     * Intent from the parcel it captured at launch, before the extra
     * existed. Reopening from recents then re-fired the old share over
     * whatever was open. Saved instance state survives that, so the
     * content itself is what is remembered.
     */
    private var consumedShare: String? = null

    private fun consume(intent: Intent?) {
        if (intent == null) return
        // Marked on the intent itself, because Android hands the
        // launching intent back to every recreate.
        //
        // The signal was a fresh System.currentTimeMillis(), so a
        // rotation produced a newer timestamp than the one
        // rememberSaveable had restored and the share fired again:
        // onSearch() cleared results, notes, marks and breadcrumb and
        // sent forty requests a second time. The same intent object
        // survives the recreate, so a flag on it is what distinguishes
        // "shared again" from "the system handed me the same share".
        if (intent.getBooleanExtra(consumedKey, false)) return
        intent.putExtra(consumedKey, true)
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
        val signature = "${intent.action}|${text.orEmpty()}|${image?.toString().orEmpty()}"
        if (signature == consumedShare) return
        consumedShare = signature
        sharedText.value = text
        sharedImage.value = image
        sharedAt.longValue = System.currentTimeMillis()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(CONSUMED_SHARE, consumedShare)
    }

    private companion object {
        const val CONSUMED_SHARE = "com.osint.unified.CONSUMED_SHARE"
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
        consumedShare = savedInstanceState?.getString(CONSUMED_SHARE)
        consume(intent)
        // Rather than overriding onNewIntent, whose signature has moved
        // between versions of androidx.activity.
        addOnNewIntentListener { consume(it) }
        setContent {
            OsintTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val settings = (application as OsintApplication).settings

                    // Which tab is open, and each tab's saved state, held
                    // ABOVE the lock screen.
                    //
                    // The lock used to `return@Surface` over the top of
                    // these, so the whole content subtree left the
                    // composition and every rememberSaveable in it
                    // unregistered its provider. Answer a phone call three
                    // screens deep with twelve layers armed, come back,
                    // unlock: back on the globe with every toggle at its
                    // default. That is the exact failure the state holder
                    // was added to prevent, reintroduced on every single
                    // background cycle. Declared here, they survive the
                    // lock, and SaveableStateProvider keeps each tab's
                    // state for it.
                    var screen by rememberSaveable { mutableStateOf("godeye") }
                    val tabState = androidx.compose.runtime.saveable
                        .rememberSaveableStateHolder()

                    // And every marker that says a signal has already been
                    // acted on, for the same reason and more urgently.
                    //
                    // These were below the gate. rememberSaveable only
                    // survives onSaveInstanceState; plain disposal takes it,
                    // and the lock's return@Surface is plain disposal. So on
                    // every unlock all five were back at zero while the
                    // signals themselves were still non-zero, because they
                    // live in the view model or in process-wide singletons
                    // that outlast any composition. navSignal is a counter
                    // and is >= 1 after any search at all, so unlocking
                    // dumped you into the dossier whatever tab you had been
                    // on; the browser's request and snapshot were never
                    // cleared, so unlocking re-ran a forty-source fan-out on
                    // the last page browsed and filed its snapshot again;
                    // sharedAt is a field on this activity and survives
                    // onStop, so the last share re-ran too. Every background
                    // cycle, for exactly the people who turned the lock on.
                    var handledNav by rememberSaveable { mutableLongStateOf(0L) }
                    var handledShare by rememberSaveable { mutableLongStateOf(0L) }
                    var handledWatch by rememberSaveable { mutableLongStateOf(0L) }
                    var handledBrowserNav by rememberSaveable { mutableLongStateOf(0L) }
                    var handledSnapshot by rememberSaveable { mutableLongStateOf(0L) }

                    // And the photo picker, for the same reason again.
                    //
                    // Registered inside Forensics it was disposed the
                    // instant it was used: launching it stops this
                    // activity, onStop re-locks, the lock gate tears down
                    // the subtree, and the launcher unregisters while the
                    // system is still holding the result. The user picked a
                    // photograph, unlocked, and nothing had happened.
                    val pickedImage = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf<Pair<android.net.Uri, Long>?>(null)
                    }
                    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) pickedImage.value = uri to System.currentTimeMillis()
                    }

                    if (locked.value) {
                        com.osint.unified.ui.common.AppLockScreen(this@MainActivity) {
                            locked.value = false
                        }
                        return@Surface
                    }

                    // Nothing below this line may touch the database,
                    // and everything below it does: the view-model
                    // factories resolve the repository the moment
                    // composition asks for a view model.
                    val ready by (application as OsintApplication)
                        .ready.collectAsStateWithLifecycle()
                    if (!ready) {
                        PreparingScreen()
                        return@Surface
                    }
                    LaunchedEffect(foreground.value) {
                        godEyeViewModel.setForeground(foreground.value)
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

                    val godState by godEyeViewModel.state.collectAsStateWithLifecycle()

                    // A new search (from the bar or a globe tap) opens the
                    // investigation. The signal that was already acted on is
                    // remembered across configuration changes: without that,
                    // a new composition after a rotation saw the same
                    // non-zero signal and forced the dossier open again, so
                    // once you had searched you could not rotate the phone
                    // anywhere else in the app.
                    LaunchedEffect(godState.navSignal) {
                        if (godState.navSignal > handledNav) {
                            handledNav = godState.navSignal
                            screen = "investigation"
                        }
                    }

                    val shareSignal = sharedAt.longValue
                    LaunchedEffect(shareSignal) {
                        if (shareSignal <= handledShare) return@LaunchedEffect
                        handledShare = shareSignal
                        val text = sharedText.value
                        sharedText.value = null
                        when {
                            sharedImage.value != null -> screen = "forensics"
                            // Offered rather than taken. See pendingShare.
                            text != null && godState.runningTools.isNotEmpty() ->
                                pendingShare.value = targetIn(text)
                            text != null -> {
                                // Prepared, not fired.
                                //
                                // This activity is exported — it has to be,
                                // for the launcher — and carries SEND and
                                // PROCESS_TEXT filters, so any app on the
                                // device can hand it a target of its
                                // choosing. Running the fan-out on arrival
                                // meant that app decided who this one
                                // investigated, from this user's address,
                                // with nothing shown first. The dossier
                                // opens with the target classified and RUN
                                // ALL waiting, which is one tap for the
                                // person who meant it and nothing at all
                                // for anyone who did not.
                                godEyeViewModel.onQueryChange(targetIn(text))
                                godEyeViewModel.onSearch(fanOut = false)
                            }
                        }
                    }

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
                    LaunchedEffect(investigateFromBrowser) {
                        val request = investigateFromBrowser ?: return@LaunchedEffect
                        if (request.second <= handledBrowserNav) return@LaunchedEffect
                        handledBrowserNav = request.second
                        com.osint.unified.ui.common.InAppNavigation.clearInvestigate()
                        godEyeViewModel.onQueryChange(request.first)
                        godEyeViewModel.onSearch()
                    }

                    val snapshot by com.osint.unified.ui.common.InAppNavigation
                        .snapshotTaken.collectAsStateWithLifecycle()
                    LaunchedEffect(snapshot) {
                        val taken = snapshot ?: return@LaunchedEffect
                        if (taken.at <= handledSnapshot) return@LaunchedEffect
                        handledSnapshot = taken.at
                        com.osint.unified.ui.common.InAppNavigation.clearSnapshot()
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
                    // nine instead of where you had left it. Declared
                    // above, with `screen`, so the lock cannot take it.
                    Column(Modifier.fillMaxSize()) {
                    pendingShare.value?.let { target ->
                        SharedTargetOffer(
                            target = target,
                            onInvestigate = {
                                pendingShare.value = null
                                godEyeViewModel.onQueryChange(target)
                                godEyeViewModel.onSearch(fanOut = false)
                            },
                            onDismiss = { pendingShare.value = null }
                        )
                    }
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
                            // Whichever arrived last. A share carries the
                            // moment it was shared; a pick carries the
                            // moment it was picked.
                            incomingImage = pickedImage.value
                                ?: sharedImage.value?.let { it to sharedAt.longValue },
                            onPickImage = { runCatching { photoPicker.launch("image/*") } },
                            onImageHandled = {
                                pickedImage.value = null
                                sharedImage.value = null
                            },
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
                                    FeedProbe("Ports (bundled)") { app.portDataSource.fetch().size },
                                    FeedProbe("Launches (The Space Devs)") { app.launchDataSource.fetch().size },
                                    FeedProbe("Controlled sites (OSM, around Berlin)") { app.sitesDataSource.near(52.52, 13.40).size },
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
                        // Tapping the tab you are already on returns it
                        // to its own root, which is the only way back to
                        // the CASES list once a target has been opened
                        // and left by a route that does not clear it:
                        // the selection lives in the view model and
                        // outlives the screen.
                        if (target == screen && target == "history") {
                            historyViewModel.select(null)
                            historyViewModel.openCase(null)
                        }
                        screen = target
                    }
                    }
                }
            }
        }
    }
}

/**
 * A target shared in from another app while a search was running.
 *
 * Shown rather than acted on. Any app on this device can hand this one
 * a target, and acting on it immediately meant that app decided who
 * this user investigated — and, worse, threw away whatever enquiry was
 * already in progress to do it. One tap takes it; one tap discards it;
 * doing nothing leaves the current search alone.
 */
@Composable
private fun SharedTargetOffer(
    target: String,
    onInvestigate: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = com.osint.unified.ui.theme.Intel.Elevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
                        androidx.compose.foundation.layout.WindowInsetsSides.Top +
                            androidx.compose.foundation.layout.WindowInsetsSides.Horizontal
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            androidx.compose.material3.Text(
                "SHARED WHILE A SEARCH WAS RUNNING",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = com.osint.unified.ui.theme.Intel.TextLow
            )
            androidx.compose.material3.Text(
                target,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = com.osint.unified.ui.theme.Intel.TextHi,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            androidx.compose.material3.Text(
                "Investigating it now would stop the search in progress and " +
                    "close the open dossier.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = com.osint.unified.ui.theme.Intel.TextLow,
                modifier = Modifier.padding(top = 2.dp)
            )
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.material3.Text(
                    "INVESTIGATE",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = com.osint.unified.ui.theme.Intel.Base,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(com.osint.unified.ui.theme.Intel.Cyan)
                        .clickable(onClick = onInvestigate)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                androidx.compose.material3.Text(
                    "NOT NOW",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = com.osint.unified.ui.theme.Intel.TextMid,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Shown for as long as it takes to open the database.
 *
 * Usually nothing: the warm-up thread starts in Application.onCreate
 * and is normally finished before the first frame. It is visible on
 * the one launch that matters — the first after the encryption
 * upgrade, where every case is copied into an encrypted file — and
 * says so, because an app that appears frozen for ten seconds while
 * it silently moves someone's work is an app they will force-close
 * halfway through.
 */
@Composable
private fun PreparingScreen() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(com.osint.unified.ui.theme.Intel.Base),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                "PREPARING",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = com.osint.unified.ui.theme.Intel.TextMid
            )
            androidx.compose.material3.Text(
                "Opening your cases. The first run after an update can take " +
                    "a few seconds while they are re-encrypted.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = com.osint.unified.ui.theme.Intel.TextLow,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, start = 40.dp, end = 40.dp)
            )
        }
    }
}
