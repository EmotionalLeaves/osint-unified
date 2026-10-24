package com.osint.unified.ui.godeye

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AirQualityDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.AuroraDataSource
import com.osint.unified.data.BundledPointsDataSource
import com.osint.unified.data.AirspaceDataSource
import com.osint.unified.data.BuoyDataSource
import com.osint.unified.data.AirportStatusDataSource
import com.osint.unified.data.CableLandingDataSource
import com.osint.unified.data.CycloneDataSource
import com.osint.unified.data.RadiationDataSource
import com.osint.unified.data.TsunamiDataSource
import com.osint.unified.data.CctvDataSource
import com.osint.unified.data.SpaceWeather
import com.osint.unified.data.SpaceWeatherDataSource
import com.osint.unified.data.ConflictDataSource
import com.osint.unified.data.SeismicStationDataSource
import com.osint.unified.data.EarthquakeDataSource
import com.osint.unified.data.FiresDataSource
import com.osint.unified.data.GdacsDataSource
import com.osint.unified.data.MetarDataSource
import com.osint.unified.data.MilAircraftDataSource
import com.osint.unified.data.NaturalEventsDataSource
import com.osint.unified.data.NewsDataSource
import com.osint.unified.data.OverlayDataSource
import com.osint.unified.data.SatellitesDataSource
import com.osint.unified.data.VolcanoDataSource
import com.osint.unified.data.LaunchDataSource
import com.osint.unified.data.OutageDataSource
import com.osint.unified.data.SeaLevelDataSource
import com.osint.unified.data.SitesDataSource
import com.osint.unified.data.WorldWarningsDataSource
import com.osint.unified.data.WeatherDataSource
import com.osint.unified.network.DataMeter
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.GeoExtractor
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Result as ResultRow
import java.io.File
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Findings
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.domain.workflow.Workflows
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GodEyeUiState(
    val query: String = "",
    val observable: Observable? = null,
    val suggestedTools: List<Tool> = emptyList(),
    val aircraft: List<Aircraft> = emptyList(),
    val vessels: List<Vessel> = emptyList(),
    val quakes: List<Quake> = emptyList(),
    val naturalEvents: List<NaturalEvent> = emptyList(),
    val overlayPoints: List<OverlayPoint> = emptyList(),
    val weather: List<GeoBlip> = emptyList(),
    val fires: List<GeoBlip> = emptyList(),
    val satellites: List<GeoBlip> = emptyList(),
    val news: List<GeoBlip> = emptyList(),
    val gdacs: List<GeoBlip> = emptyList(),
    val volcanoes: List<GeoBlip> = emptyList(),
    /** Official warnings from every WMO member, one marker per service. */
    val worldWarnings: List<GeoBlip> = emptyList(),
    /** Tide gauges reporting now, worldwide. */
    val seaLevel: List<GeoBlip> = emptyList(),
    /** Countries whose connectivity has fallen over. */
    val outages: List<GeoBlip> = emptyList(),
    /** Every port in the world. */
    val ports: List<GeoBlip> = emptyList(),
    /** Launches in the next three weeks, at the pads they leave from. */
    val launches: List<GeoBlip> = emptyList(),
    /** Controlled places around the view: crossings, missions, prisons. */
    val sites: List<GeoBlip> = emptyList(),
    val aurora: List<GeoBlip> = emptyList(),
    val buoys: List<GeoBlip> = emptyList(),
    val metar: List<GeoBlip> = emptyList(),
    val milAircraft: List<GeoBlip> = emptyList(),
    val airQuality: List<GeoBlip> = emptyList(),
    val stations: List<GeoBlip> = emptyList(),
    val conflicts: List<GeoBlip> = emptyList(),
    val airports: List<GeoBlip> = emptyList(),
    val powerPlants: List<GeoBlip> = emptyList(),
    val cameras: List<GeoBlip> = emptyList(),
    val airspace: List<GeoBlip> = emptyList(),
    val cables: List<GeoBlip> = emptyList(),
    val cyclones: List<GeoBlip> = emptyList(),
    val tsunamis: List<GeoBlip> = emptyList(),
    val radiation: List<GeoBlip> = emptyList(),
    val airportStatus: List<GeoBlip> = emptyList(),
    val spaceWeather: SpaceWeather? = null,
    /** Which feeds have completed a first fetch. A layer that has not run
     *  yet is not the same as a layer that ran and found nothing, and the
     *  pills say which is which instead of both reading "0". */
    val loaded: Set<String> = emptySet(),
    /** Layer key -> why its last fetch failed. A layer in here is showing
     *  the last good data it had, or nothing at all, and in neither case
     *  may its count be read as "there is nothing out there". */
    val feedTrouble: Map<String, String> = emptyMap(),
    /**
     * Layer key -> who was missing from the last otherwise-successful
     * answer.
     *
     * [feedTrouble] is one boolean per layer, which is the right shape
     * for a feed with one source and the wrong shape for the six that
     * merge agencies: two of three seismic agencies down, or one of
     * twenty-four METAR regions answering, read as a clean layer with
     * a confident count. A layer in here has real data and incomplete
     * coverage, which is a third state and needs a third word.
     */
    val feedPartial: Map<String, String> = emptyMap(),
    /** Layer key -> when it last answered, for "stale 12m" on the pill. */
    val lastSuccess: Map<String, Long> = emptyMap(),
    val findings: List<FindingPin> = emptyList(),
    val mapFocus: Pair<Double, Double>? = null,
    val results: List<ResultRow> = emptyList(),
    /**
     * What is in flight, counted per tool rather than held as a set.
     *
     * The second pass runs the same tool against several identifiers at
     * once. With a set, the first to finish removed the tool and the UI
     * showed it idle while two more were still running — and a ghost entry
     * left RUN ALL disabled for the next dossier.
     */
    val runningTools: Map<String, Int> = emptyMap(),
    val workflowName: String? = null,
    val workflowRunning: Boolean = false,
    /** Steps of the last chain that did not run, and why. */
    val workflowSkipped: List<String> = emptyList(),
    /** Why the last tap on the search button did nothing, if it did
     *  nothing. An empty box used to be answered with silence. */
    val queryTrouble: String? = null,
    /**
     * How many sources this search will actually dispatch.
     *
     * The progress line divided by suggestedTools.size, which is
     * every tool the router named — including the ones autoRun
     * drops: the active-recon set and the redundant sweeps. So a
     * domain search counted to 15 out of 21 and stopped, username to
     * 11 of 16, an image to 3 of 5, and the bar disappeared with the
     * number unfinished, which reads as the scan giving up.
     */
    val plannedTools: Int = 0,
    /** And how many of the dispatched tools only hand over links.
     *  Published, so the ratio is visible in the app rather than
     *  only in a review file. */
    val plannedLinkLists: Int = 0,
    /**
     * Bumped when a feed's contents actually change.
     *
     * The globe and the map both recompute their marker plans from
     * `layers`, which is a data class holding thirty lists — so
     * using it as a produceState key means comparing every point in
     * every layer, element by element, on the main thread, on every
     * recomposition. With the fire detections alone in six figures
     * that is the cost of the thinning again, paid in the place
     * where it hurts most.
     *
     * Worse, most feeds put how old the reading is into the marker's
     * own label, so the lists differ at every minute boundary even
     * when nothing has moved — and the thinning deliberately ignores
     * that age when deciding identity, so it recomputed a set it
     * knew would come out the same.
     *
     * One integer instead, raised only when a poll returns something
     * genuinely different from what it returned last. The list is
     * still written every time, so the ages on screen stay current;
     * it is the recompute that stops.
     */
    val layerVersion: Int = 0,
    /** The second pass is chasing what the first pass turned up. */
    /**
     * Bytes off the wire since this search began.
     *
     * Mobile data is the one budget this app's user named as real,
     * and the dossier header counted tools. Forty sources and then
     * eleven pivots at six each is not a number anybody can turn into
     * megabytes in their head.
     */
    val dataSpent: Long = 0,
    /** What a search of this type has cost before, and over how many. */
    val dataTypical: Pair<Long, Int>? = null,
    val enrichmentRunning: Boolean = false,
    /** Identifiers the first pass discovered and the second pass is looking
     *  up in their own right — shown so the derivation is never a mystery. */
    val enriching: List<String> = emptyList(),
    /** The chain back to the first thing searched: "jdoe → jdoe@x.com → 1.2.3.4". */
    val breadcrumb: List<String> = emptyList(),
    /** The same chain as case ids, so going back opens the stored case
     *  rather than searching its target all over again. */
    val breadcrumbIds: List<Long> = emptyList(),
    val navSignal: Long = 0,
    val backendUrl: String = "",
    val tar1090Url: String = "",
    val aisUrl: String = "",
    val overlayUrl: String = "",
    val secContactEmail: String = "",
    val backendToken: String = "",
    val searchType: String = "auto",
    /** Off by default: a search must not contact the target without asking. */
    val activeRecon: Boolean = false,
    /** Off by default: the second pass reaches things nobody named. */
    val followUp: Boolean = false,
    /** What the investigator wrote about this target. */
    val notes: String = "",
    /**
     * Findings this case has been told are wrong, as kind to lowercased
     * value, mapped to the reason given.
     *
     * The app grades what the sources returned; it has no way of knowing
     * whether the J. Doe a sweep found is the right J. Doe. The person
     * looking does, and until this existed had nowhere to put it, so the
     * same false lead came back at the same confidence every time the
     * case was reopened.
     */
    val dismissals: Map<Pair<String, String>, String> = emptyMap()
)

class GodEyeViewModel(
    private val router: ObservableRouter,
    private val repository: InvestigationRepository,
    private val aircraftDataSource: AircraftDataSource,
    private val aisDataSource: AisDataSource,
    private val earthquakeDataSource: EarthquakeDataSource,
    private val naturalEventsDataSource: NaturalEventsDataSource,
    private val overlayDataSource: OverlayDataSource,
    private val weatherDataSource: WeatherDataSource,
    private val firesDataSource: FiresDataSource,
    private val satellitesDataSource: SatellitesDataSource,
    private val newsDataSource: NewsDataSource,
    private val gdacsDataSource: GdacsDataSource,
    private val volcanoDataSource: VolcanoDataSource,
    private val worldWarningsDataSource: WorldWarningsDataSource,
    private val seaLevelDataSource: SeaLevelDataSource,
    private val outageDataSource: OutageDataSource,
    private val portDataSource: BundledPointsDataSource,
    private val launchDataSource: LaunchDataSource,
    private val sitesDataSource: SitesDataSource,
    private val auroraDataSource: AuroraDataSource,
    private val buoyDataSource: BuoyDataSource,
    private val metarDataSource: MetarDataSource,
    private val milAircraftDataSource: MilAircraftDataSource,
    private val airQualityDataSource: AirQualityDataSource,
    private val seismicStationDataSource: SeismicStationDataSource,
    private val conflictDataSource: ConflictDataSource,
    private val airportDataSource: BundledPointsDataSource,
    private val powerPlantDataSource: BundledPointsDataSource,
    private val cctvDataSource: CctvDataSource,
    private val airspaceDataSource: AirspaceDataSource,
    private val spaceWeatherDataSource: SpaceWeatherDataSource,
    private val cableLandingDataSource: CableLandingDataSource,
    private val cycloneDataSource: CycloneDataSource,
    private val tsunamiDataSource: TsunamiDataSource,
    private val radiationDataSource: RadiationDataSource,
    private val airportStatusDataSource: AirportStatusDataSource,
    private val availableLocalTools: Set<String>,
    private val settings: AppSettings,
    private val exporter: InvestigationExporter
) : ViewModel() {

    /** Keeps only tools that can actually run in this build/config: DIRECT
     *  always, LOCAL only if its binary shipped, CLOUD only with a backend. */
    /** toolId|input pairs already dispatched for the current investigation,
     *  so the enrichment pass never repeats a lookup the first pass did. */
    private val dispatched = HashSet<String>()

    /**
     * Which fan-out is the current one.
     *
     * A cancelled tool job does not reach its `finally` straight
     * away: the repository first writes a "cancelled" row under
     * NonCancellable, which is a round trip to the database, and
     * only then rethrows. So the decrement commonly landed after the
     * NEXT search had already added its own counts — and since two
     * searches of the same type dispatch almost the same tool ids,
     * it decremented the new run's counters. The progress bar
     * vanished while the tools were still going and the dossier
     * printed "No source returned anything on this target" over a
     * scan in flight.
     *
     * Every launch carries the token it was started under, and a
     * decrement from an older one is ignored.
     */
    private var searchRun = 0

    /**
     * The meter reading when this search started, so the dossier can
     * say what it cost rather than what the whole process has.
     */
    private var dataBaseline = DataMeter.toolBytes()

    /** Recorded once per search, so the next one of this type can be estimated. */
    private var costRecorded = false

    private fun noteDataSpent() {
        val spent = (DataMeter.toolBytes() - dataBaseline).coerceAtLeast(0)
        _state.update { it.copy(dataSpent = spent) }
    }

    /** Everything the current search has in flight, so a new search can
     *  stop it rather than letting two searches write to one dossier. */
    // Copy-on-write, because `invokeOnCompletion` runs on whichever
    // thread finished the job while SEARCH and STOP iterate this list
    // on the main thread. A plain MutableList threw
    // ConcurrentModificationException out of the app's primary action
    // whenever a fan-out happened to finish mid-press.
    private val searchJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

    /** ensureInvestigation reads a field, suspends to insert a row, then
     *  writes the field. Two callers racing through that gap create two
     *  investigations, and the second one's collector cancels the first —
     *  so rows written against the first id are never shown. */
    private val investigationMutex = Mutex()

    private fun runnable(tools: List<Tool>): List<Tool> = tools.filter { t ->
        when (t.executionMode) {
            ExecutionMode.DIRECT -> true
            ExecutionMode.LOCAL -> t.id in availableLocalTools
            ExecutionMode.CLOUD -> settings.backendBaseUrl.isNotBlank()
        }
    }

    /**
     * Which layers the screen is actually showing, and therefore which
     * feeds are worth fetching.
     *
     * Empty until the screen says otherwise, which is the opposite of
     * what it used to be. "Null means everything" meant the polls that
     * start in `init` ran wide before the first composition narrowed
     * them — one whole-planet aircraft sweep every launch — and if the
     * app lock or the first-run terms screen was up, this screen never
     * composed at all and every feed polled at full rate behind it.
     * Nothing is fetched now until something on screen has asked for
     * it.
     */
    @Volatile private var visibleLayers: Set<String>? = emptySet()

    /** False while the app is in the background, where no feed is worth
     *  fetching no matter which layers are on. */
    @Volatile private var foreground: Boolean = true

    /**
     * False while the map screen itself is not in the composition.
     *
     * The app can be in the foreground with the globe nowhere near
     * it: reading a dossier, browsing the directory, looking at a
     * case. Nothing stopped the feeds in that state, so a session
     * spent reading one investigation went on polling aircraft every
     * twenty-five seconds for a map that was not on screen — against
     * the promise the layer panel makes in so many words, and against
     * the only resource this app is supposed to be careful with.
     *
     * Separate from [setVisibleLayers] on purpose: this must not
     * clear what the layers last said, because the screen is coming
     * back and an empty globe for one poll interval is worse than a
     * slightly old one.
     */
    @Volatile private var onScreen: Boolean = true

    fun setMapOnScreen(on: Boolean) { onScreen = on }

    fun setVisibleLayers(keys: Set<String>) {
        val before = visibleLayers
        visibleLayers = keys
        if (before == null) return
        val gone = before - keys
        if (gone.isEmpty()) return
        // A layer that is no longer being fetched must stop being
        // quoted.
        //
        // Switching a layer off only stopped the polling; everything
        // it had last said stayed in the state and went on being read
        // by the header counts, the world brief and the area report —
        // none of which filter by what is switched on. Turn cyclones
        // off with three storms in the water and a week later the
        // brief still names them, at the top of the list, as current.
        // The mirror case is worse: a layer that had loaded and found
        // nothing keeps its place in `loaded`, which is what licenses
        // the brief to print "reported nothing anywhere" — an
        // all-clear for a feed nobody has contacted since.
        _state.update { st -> clear(st, gone) }
    }

    /**
     * Forget everything these layers said.
     *
     * Written out one key at a time rather than reflected over,
     * because a layer added later with no line here would silently
     * keep the old behaviour, and the old behaviour is the bug.
     */
    private fun clear(st: GodEyeUiState, gone: Set<String>): GodEyeUiState {
        var s = st
        for (key in gone) {
            s = when (key) {
                "aircraft" -> s.copy(aircraft = emptyList())
                "vessels" -> s.copy(vessels = emptyList())
                "quakes" -> s.copy(quakes = emptyList())
                "naturalEvents" -> s.copy(naturalEvents = emptyList())
                "overlayPoints" -> s.copy(overlayPoints = emptyList())
                "weather" -> s.copy(weather = emptyList())
                "fires" -> s.copy(fires = emptyList())
                "satellites" -> s.copy(satellites = emptyList())
                "news" -> s.copy(news = emptyList())
                "gdacs" -> s.copy(gdacs = emptyList())
                "volcanoes" -> s.copy(volcanoes = emptyList())
                "worldWarnings" -> s.copy(worldWarnings = emptyList())
                "seaLevel" -> s.copy(seaLevel = emptyList())
                "outages" -> s.copy(outages = emptyList())
                "ports" -> s.copy(ports = emptyList())
                "launches" -> s.copy(launches = emptyList())
                "sites" -> s.copy(sites = emptyList())
                "aurora" -> s.copy(aurora = emptyList())
                "buoys" -> s.copy(buoys = emptyList())
                "metar" -> s.copy(metar = emptyList())
                "milAircraft" -> s.copy(milAircraft = emptyList())
                "airQuality" -> s.copy(airQuality = emptyList())
                "stations" -> s.copy(stations = emptyList())
                "conflicts" -> s.copy(conflicts = emptyList())
                "airports" -> s.copy(airports = emptyList())
                "powerPlants" -> s.copy(powerPlants = emptyList())
                "cameras" -> s.copy(cameras = emptyList())
                "airspace" -> s.copy(airspace = emptyList())
                "cables" -> s.copy(cables = emptyList())
                "cyclones" -> s.copy(cyclones = emptyList())
                "tsunamis" -> s.copy(tsunamis = emptyList())
                "radiation" -> s.copy(radiation = emptyList())
                "airportStatus" -> s.copy(airportStatus = emptyList())
                "spaceWeather" -> s.copy(spaceWeather = null)
                else -> s
            }
        }
        // And the on-demand layers' memory of where they asked.
        //
        // This is the other half of `*KeyAnswered`, and leaving it
        // out broke the feature the guard protects. The guard used
        // to be `key == sitesKey && sites.isNotEmpty()`, and that
        // second term was the only thing invalidating it when the
        // data was thrown away here — so once the key alone decided,
        // switching MIL GROUND off and on again without panning left
        // the view model certain it had already answered for a cell
        // whose polygons it had just deleted. The pill read "…" for
        // ever and no request was made. Escape was panning 78 km.
        //
        // Not by restoring `isNotEmpty()`, which is the pan storm
        // coming back: an honestly empty answer has to stay valid.
        // The key is forgotten when the data behind it is.
        if ("cameras" in gone) {
            cameraKey = null
            cameraKeyAnswered = null
        }
        if ("sites" in gone) {
            sitesKey = null
            sitesKeyAnswered = null
        }
        if ("airspace" in gone) {
            airspaceKey = null
            airspaceKeyAnswered = null
        }
        // Radiation already had this: `ensureRadiation(false)` is
        // called from every place that disarms it and resets its
        // centre and zoom band. Belt and braces, for the paths that
        // disarm it by changing the visible set instead.
        if ("radiation" in gone) {
            radiationCentre = null
            radiationZoomBand = -1
        }
        // And the bookkeeping, or the brief still believes these
        // answered.
        return s.copy(
            loaded = s.loaded - gone,
            feedTrouble = s.feedTrouble - gone,
            feedPartial = s.feedPartial - gone,
            lastSuccess = s.lastSuccess - gone
        )
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    private fun shouldFetch(key: String): Boolean {
        if (!foreground || !onScreen) return false
        val visible = visibleLayers ?: return true
        return key in visible
    }

    private val _state = MutableStateFlow(
        GodEyeUiState(
            backendUrl = settings.backendBaseUrl,
            tar1090Url = settings.tar1090Url,
            aisUrl = settings.aisUrl,
            overlayUrl = settings.overlayUrl,
            secContactEmail = settings.secContactEmail,
            backendToken = settings.backendToken,
            activeRecon = settings.activeReconEnabled,
            followUp = settings.followUpEnabled
        )
    )
    val state: StateFlow<GodEyeUiState> = _state.asStateFlow()

    private var currentInvestigationId: Long? = null

    /** Set by [onPivot] so the next investigation records its parent. */
    private var pendingParentId: Long? = null
    private var pendingPivot: String? = null
    private var resultsJob: Job? = null
    private var dismissalsJob: Job? = null

    /**
     * Keep a running job, and forget it once it has finished.
     *
     * The list is only cleared when a new search starts, so without
     * this every job a case ever ran stayed in it for the life of the
     * view model — a slow accumulation across a long session, for
     * nothing: a completed job cannot be cancelled.
     */
    private infix fun MutableList<Job>.addAndForget(job: Job) {
        add(job)
        job.invokeOnCompletion { remove(job) }
    }

    /** The two reads that follow a case being opened. Held so that
     *  opening another one, or searching, can stop them before they
     *  write the old case's notes over the new case's. */
    private var attachNotesJob: Job? = null
    private var attachLineageJob: Job? = null

    init {
        // Staggered start-up so launch doesn't fire every feed at once (which
        // spiked memory/CPU). Each poll is exception-guarded so one bad fetch
        // can't kill its loop, and updates stay atomic per field.
        poll(0, 25_000, "aircraft", { aircraftDataSource.fetch(focus, wideView) }) { s, v -> s.copy(aircraft = v) }
        poll(100, 30_000, "vessels", { aisDataSource.fetch(focus) }, gaps = { aisDataSource.lastGaps }) { s, v -> s.copy(vessels = v) }
        poll(200, 15_000, "satellites", { satellitesDataSource.fetch() }) { s, v -> s.copy(satellites = v) }
        poll(375, 300_000, "quakes", { earthquakeDataSource.fetch() }, gaps = { earthquakeDataSource.lastGaps }) { s, v -> s.copy(quakes = v) }
        poll(625, 600_000, "naturalEvents", { naturalEventsDataSource.fetch() }) { s, v -> s.copy(naturalEvents = v) }
        poll(875, 60_000, "overlayPoints", { overlayDataSource.fetch() }) { s, v -> s.copy(overlayPoints = v) }
        poll(1125, 600_000, "fires", { firesDataSource.fetch() }, gaps = { firesDataSource.lastGaps }) { s, v -> s.copy(fires = v) }
        poll(1375, 600_000, "volcanoes", { volcanoDataSource.fetch() }) { s, v -> s.copy(volcanoes = v) }
        poll(1510, 600_000, "worldWarnings", { worldWarningsDataSource.fetch() }) { s, v -> s.copy(worldWarnings = v) }
        poll(1640, 600_000, "seaLevel", { seaLevelDataSource.fetch() }) { s, v -> s.copy(seaLevel = v) }
        poll(1770, 600_000, "outages", { outageDataSource.fetch() }) { s, v -> s.copy(outages = v) }
        poll(1900, 24 * 3600_000, "ports", { portDataSource.fetch() }) { s, v -> s.copy(ports = v) }
        poll(2030, 3600_000, "launches", { launchDataSource.fetch() }) { s, v -> s.copy(launches = v) }
        poll(1625, 600_000, "gdacs", { gdacsDataSource.fetch() }) { s, v -> s.copy(gdacs = v) }
        poll(1875, 300_000, "weather", { weatherDataSource.fetch() }) { s, v -> s.copy(weather = v) }
        poll(2125, 300_000, "aurora", { auroraDataSource.fetch() }) { s, v -> s.copy(aurora = v) }
        poll(2375, 300_000, "news", { newsDataSource.fetch() }) { s, v -> s.copy(news = v) }
        poll(2750, 600_000, "buoys", { buoyDataSource.fetch() }) { s, v -> s.copy(buoys = v) }
        poll(3250, 600_000, "metar", { metarDataSource.fetch() }, gaps = { metarDataSource.lastGaps }) { s, v -> s.copy(metar = v) }
        // Sixty seconds, and started well clear of the aircraft
        // layer rather than half a second after it.
        //
        // This was twenty — the fastest poll in the app, for about a
        // hundred and twenty aircraft on a map of the whole world,
        // against the same host the general aircraft layer uses
        // every twenty-five. The two drifted in and out of phase and
        // collided, adsb.fi answered 429, and the layer spent the
        // session empty. Reported as "military does not work".
        poll(9_000, 60_000, "milAircraft", { milAircraftDataSource.fetch() }) { s, v -> s.copy(milAircraft = v) }
        poll(3500, 900_000, "airQuality", { airQualityDataSource.fetch() }, gaps = { airQualityDataSource.lastGaps }) { s, v -> s.copy(airQuality = v) }
        poll(3750, 21_600_000, "stations", { seismicStationDataSource.fetch() }) { s, v -> s.copy(stations = v) }
        // Six hours: the list is edited by hand and its counts move by the
        // week, and the page is a few hundred kilobytes before gzip.
        poll(4000, 21_600_000, "conflicts", { conflictDataSource.fetch() }, gaps = { conflictDataSource.lastGaps }) { s, v -> s.copy(conflicts = v) }
        poll(300, 3_600_000, "airports", { airportDataSource.fetch() }) { s, v -> s.copy(airports = v) }
        poll(450, 3_600_000, "powerPlants", { powerPlantDataSource.fetch() }) { s, v -> s.copy(powerPlants = v) }
        poll(900, 900_000, "spaceWeather", { spaceWeatherDataSource.fetch() }, gaps = { spaceWeatherDataSource.lastGaps }) { s, v -> s.copy(spaceWeather = v) }
        poll(1200, 86_400_000, "cables", { cableLandingDataSource.fetch() }) { s, v -> s.copy(cables = v) }
        poll(700, 600_000, "cyclones", { cycloneDataSource.fetch() }, gaps = { cycloneDataSource.lastGaps }) { s, v -> s.copy(cyclones = v) }
        poll(800, 600_000, "tsunamis", { tsunamiDataSource.fetch() }, gaps = { tsunamiDataSource.lastGaps }) { s, v -> s.copy(tsunamis = v) }
        poll(1500, 180_000, "airportStatus", { airportStatusDataSource.fetch() }) { s, v -> s.copy(airportStatus = v) }
    }

    // Startup delays only exist so twenty requests do not begin on the same
    // millisecond. They used to spread over sixteen seconds, which left half
    // the layers reading "0" long enough to look broken.
    //
    // Intervals are set by how often the data actually changes, not by how
    // live it would be nice to look. The NWS alert feed is nearly two
    // megabytes and alerts do not change by the minute; AIS positions for
    // most vessels update on the order of minutes. Polling faster than the
    // source changes just spends someone's mobile data.
    private fun <T> poll(
        initialDelayMs: Long,
        intervalMs: Long,
        key: String,
        fetch: suspend () -> T,
        /**
         * For a feed merged from several agencies: what was missing
         * from the fetch that just succeeded, or null if nothing was.
         * Read immediately after [fetch] returns, which is the only
         * moment it describes this answer rather than the one before.
         */
        gaps: (() -> String?)? = null,
        apply: (GodEyeUiState, T) -> GodEyeUiState
    ) {
        viewModelScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            while (isActive) {
                // A feed nobody is looking at is not fetched, and the loop
                // wakes often enough to notice when that changes.
                if (!shouldFetch(key)) {
                    // Three seconds while the app is in front, so a
                    // layer switched on starts almost at once. A great
                    // deal longer behind, where thirty loops waking
                    // every three seconds all night is a flat battery
                    // for no possible benefit — nothing can be
                    // switched on from there.
                    delay(if (foreground) POLL_IDLE_MS else BACKGROUND_IDLE_MS)
                    continue
                }
                val attempt = runCatching { fetch() }
                attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val result = attempt.getOrNull()
                val missing = if (result != null) gaps?.invoke() else null
                // "loaded" means this feed has answered — only then does a
                // count of zero mean "nothing out there", which is what the
                // pills and the world brief go on to assert. A failed fetch
                // records the trouble and keeps whatever it last had, so
                // neither of them claims an all-clear during an outage.
                // Did anything actually change, ignoring the clock.
                val moved = result != null && !sameAsLast(key, result)
                _state.update { state ->
                    if (result == null) {
                        val why = attempt.exceptionOrNull()?.message?.take(90)
                            ?: "no answer"
                        state.copy(
                            feedTrouble = state.feedTrouble + (key to why),
                            // Or the layer is listed twice — once as
                            // not reporting and once as partly
                            // covered by a fetch ten minutes old —
                            // and the area report counts it as two
                            // layers that could not say.
                            feedPartial = state.feedPartial - key
                        )
                    } else {
                        apply(
                            state.copy(
                                loaded = state.loaded + key,
                                feedTrouble = state.feedTrouble - key,
                                feedPartial =
                                    if (missing == null) state.feedPartial - key
                                    else state.feedPartial + (key to missing),
                                lastSuccess = state.lastSuccess +
                                    (key to System.currentTimeMillis()),
                                layerVersion =
                                    if (moved) state.layerVersion + 1 else state.layerVersion
                            ),
                            result
                        )
                    }
                }
                // A failure does not earn the full interval.
                //
                // Cables and ports are on a twenty-four hour timer and
                // stations on six. Switching one of those on while the
                // phone happens to be between networks marked it "not
                // reporting" and then left it that way until tomorrow.
                val wait = if (result == null) minOf(intervalMs, RETRY_MS) else intervalMs
                waitWatching(key, wait)
            }
        }
    }

    /** What each feed last returned, for the comparison above. */
    private val lastPolled = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Whether this poll said the same thing as the last one.
     *
     * For a list of markers the comparison ignores the age suffix
     * in each label, because "· 40m ago" becoming "· 41m ago" is
     * the clock moving and not the world — and the thinning that
     * this guards already treats those two as the same marker.
     * Anything else falls back to ordinary equality.
     */
    private fun sameAsLast(key: String, result: Any): Boolean {
        val previous = lastPolled.put(key, result)
        if (previous == null) return false
        if (previous === result) return true
        if (previous is List<*> && result is List<*>) {
            if (previous.size != result.size) return false
            val a = previous.firstOrNull()
            if (a is GeoBlip) {
                for (i in result.indices) {
                    val old = previous[i] as? GeoBlip ?: return false
                    val new = result[i] as? GeoBlip ?: return false
                    if (old.lat != new.lat || old.lon != new.lon ||
                        old.url != new.url ||
                        withoutAge(old.label) != withoutAge(new.label)
                    ) {
                        return false
                    }
                }
                return true
            }
        }
        return previous == result
    }

    /**
     * Wait out an interval, but notice if the layer goes away.
     *
     * A plain delay parked the loop for the whole interval whatever
     * happened to the layer meanwhile, and switching a layer off now
     * discards what it had said — so a layer toggled off and on again
     * during a long interval came back empty and stayed empty until
     * the timer finally came round. For cables that is a day.
     *
     * Breaking out early lands the loop in the idle path, which is
     * checked every few seconds, so switching the layer back on
     * fetches it at once. The slice is a fraction of the interval and
     * never more than half a minute, so a feed on a short timer is not
     * woken any more often than it already was and one on a long timer
     * costs two wakes a minute while it is on screen.
     */
    private suspend fun waitWatching(key: String, total: Long) {
        if (total <= 0L) return
        val slice = total.coerceAtMost(WAIT_SLICE_MS).coerceAtLeast(POLL_IDLE_MS)
        var waited = 0L
        while (waited < total) {
            val step = minOf(slice, total - waited)
            delay(step)
            waited += step
            if (!shouldFetch(key)) return
        }
    }

    /** Where the map is pointed, so feeds that cost bandwidth per region
     *  can follow it rather than sweeping the planet every refresh. */
    @Volatile private var focus: Pair<Double, Double>? = null
    @Volatile private var wideView: Boolean = true

    fun onViewportChanged(lat: Double, lon: Double, zoom: Double) {
        focus = lat to lon
        wideView = zoom < 5.0
        zoomLevel = zoom
        // Radiation is queried around where you are looking, so moving the
        // map is what asks for it — but only once the layer is on, and only
        // when the view has moved far enough that the last answer no longer
        // covers it. Without that test every pan gesture fired a fresh
        // query on someone's mobile data.
        if (radiationOn && movedBeyond(lat, lon, zoom)) ensureRadiation()
        // And so do the other three — on their own terms.
        //
        // These used to sit behind movedBeyond as well, which is
        // radiation's freshness test: it measures against the centre
        // radiation was last fetched for, and against
        // AreaReport.radiusForZoom, which is a function of zoom and
        // nothing to do with how far any of these three actually
        // reach. Cameras fetch a 25 km circle, sites 120 km, ground
        // airspace 150 km. At zoom 5 the gate's limit is 200 km, so
        // a 100 km pan took the viewport clean off the camera circle
        // and did not re-ask. And with the radiation layer off the
        // gate had no centre to compare against and passed
        // everything, so the same code either refused every refresh
        // or allowed every one depending on an unrelated layer.
        //
        // Each of the three already keeps its own cell key. That key
        // is the test; it just has to be sized to the layer's own
        // radius rather than all being 0.1 degrees.
        //
        // Cameras, controlled sites and airspace are all queried
        // around wherever the view is pointed, and all three said so
        // in their own comments — "this has to be asked again when the
        // view moves somewhere else" — while nothing ever called them
        // again. Turn CAMERAS on over Tokyo, pan to Paris: no markers,
        // no request, and the pill still reading the Tokyo count. The
        // key machinery that exists to decide whether a re-ask is
        // needed had no caller at all.
        val on = visibleLayers
        if (on == null || "cameras" in on) ensureCameras()
        if (on == null || "sites" in on) ensureSites()
        if (on == null || "airspace" in on) ensureAirspace()
    }

    private var sitesJob: Job? = null
    @Volatile private var sitesKey: String? = null

    /** As with the cameras: answered, not non-empty. Overpass finding
     *  no embassy within 120 km of a stretch of Siberia is an answer,
     *  and it was re-asked on every pan. */
    @Volatile private var sitesKeyAnswered: String? = null

    /**
     * Controlled places around the view, when the layer is on.
     *
     * Asked again when the view moves somewhere the last answer does
     * not cover, like the cameras — and not at all until somebody
     * turns it on, because every one of these is a request to a
     * volunteer-run server.
     */
    /**
     * Run an on-demand layer's fetch, and say so when it does not work.
     *
     * All three of these used to end `runCatching { ... }.getOrNull()`
     * followed by `if (v != null)`. So an Overpass failure — a 429
     * from a volunteer-run server, a gateway timeout, no network —
     * wrote nothing anywhere: no data, no `loaded`, no `feedTrouble`.
     * The pill sat on its marker for ever and the layer simply never
     * worked, with the app holding the reason and saying none of it.
     *
     * The sites layer was worse again: it opened `val where = focus ?:
     * return`, and the view never reported where it was looking unless
     * you had been to the flat map and panned it. On the globe, which
     * is the default, switching the layer on did precisely nothing —
     * no request, no error, no mark.
     */
    private fun onDemand(
        key: String,
        job: () -> Job?,
        setJob: (Job?) -> Unit,
        fetch: suspend () -> List<GeoBlip>,
        /** As in [poll]: who was missing from the fetch that just
         *  succeeded. Cameras and airspace each merge two or more
         *  sources and can lose one without losing the layer. */
        gaps: (() -> String?)? = null,
        apply: (GodEyeUiState, List<GeoBlip>) -> GodEyeUiState
    ) {
        if (job()?.isActive == true) return
        setJob(
            viewModelScope.launch {
                val attempt = runCatching { fetch() }
                attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val v = attempt.getOrNull()
                val missing = if (v != null) gaps?.invoke() else null
                _state.update { st ->
                    if (v == null) {
                        st.copy(
                            feedTrouble = st.feedTrouble +
                                (key to (attempt.exceptionOrNull()?.message?.take(90)
                                    ?: "no answer")),
                            // A layer that has just failed outright
                            // must not also still be described as
                            // partially covered by an older fetch.
                            feedPartial = st.feedPartial - key
                        )
                    } else {
                        apply(
                            st.copy(
                                loaded = st.loaded + key,
                                feedTrouble = st.feedTrouble - key,
                                feedPartial =
                                    if (missing == null) st.feedPartial - key
                                    else st.feedPartial + (key to missing),
                                // The marker plans are keyed on this.
                                //
                                // The thirty polled feeds bump it when
                                // their contents move; these four wrote
                                // their results and did not, so a layer
                                // armed on its own loaded its data, lit
                                // its pill with a count and a tick, and
                                // was never drawn — on the flat map
                                // until the next pan, and on the globe,
                                // which is the default view, until
                                // something unrelated happened to
                                // change.
                                layerVersion = st.layerVersion + 1
                            ),
                            v
                        )
                    }
                }
                setJob(null)
            }
        )
    }

    /** Recorded rather than returned from in silence. */
    private fun needsAView(key: String) {
        _state.update {
            it.copy(
                feedTrouble = it.feedTrouble +
                    (key to "waiting for the view — move the map or globe")
            )
        }
    }

    fun ensureSites() {
        val where = focus
        if (where == null) {
            needsAView("sites")
            return
        }
        // The job is checked before the key is recorded.
        //
        // It was the other way round, and onDemand returns without
        // doing anything if a fetch is already running — so moving
        // the view while an Overpass query was in flight recorded the
        // new region's key against the old region's data, and every
        // later call then saw a key hit and returned too. The layer
        // froze on where you had been, with no request outstanding
        // and nothing said. Cameras and airspace both check the job
        // first; this was the one with the order wrong.
        if (sitesJob?.isActive == true) return
        // Half the 120 km this layer fetches. A tenth of a degree is
        // eleven kilometres, which re-asked Overpass for a 120 km
        // circle after a pan that had barely left the middle of it.
        val key = cellKey(where, SITES_CELL)
        if (key == sitesKey && key == sitesKeyAnswered) return
        sitesKey = key
        onDemand(
            key = "sites",
            job = { sitesJob },
            setJob = { sitesJob = it },
            fetch = {
                val v = sitesDataSource.near(where.first, where.second)
                sitesKeyAnswered = key
                v
            },
            apply = { st, v -> st.copy(sites = v) }
        )
    }

    /** Half of what each of these actually fetches, in degrees of
     *  latitude: cameras 25 km, sites 120 km, ground airspace 150 km. */
    private val CAMERA_CELL = 0.1
    private val SITES_CELL = 0.5
    private val AIRSPACE_CELL = 0.7

    private var camerasJob: Job? = null
    private var airspaceJob: Job? = null
    private var radiationJob: Job? = null
    private var zoomLevel: Double = 2.5
    private var radiationOn: Boolean = false
    @Volatile private var radiationCentre: Pair<Double, Double>? = null
    @Volatile private var radiationZoomBand: Int = -1

    /**
     * The camera inventory is several megabytes, so it is only fetched when
     * the layer is turned on, and then kept for the session.
     *
     * The job handle is cleared when the fetch ends. It used to be left set,
     * which meant one failed fetch locked the layer out permanently: the
     * guard below saw a non-null job, returned, and the pill sat on its
     * loading marker forever with no way to retry short of restarting.
     */
    /** Where the cameras were last asked about, so panning a street does
     *  not re-query a volunteer-run service on every frame. */
    @Volatile private var cameraKey: String? = null

    /**
     * The cells that have been asked and answered, whatever they
     * answered.
     *
     * The guard used to be `key == cameraKey && cameras.isNotEmpty()`,
     * which an honestly-empty answer fails every single time: over
     * open water, where OpenStreetMap has no mapped cameras and no
     * operator publishes any, every settled viewport change re-fired
     * the whole fetch. Twelve parallel Caltrans requests per pan was
     * the visible half of that; this is the half that made it repeat.
     */
    @Volatile private var cameraKeyAnswered: String? = null

    fun ensureCameras() {
        if (camerasJob?.isActive == true) return
        // Mapped cameras are a worldwide set queried around wherever the
        // globe is pointed, so unlike the old Californian list this has
        // to be asked again when the view moves somewhere else.
        val where = focus
        val key = where?.let { cellKey(it, CAMERA_CELL) } ?: "none"
        if (key == cameraKey && key == cameraKeyAnswered) return
        cameraKey = key
        onDemand(
            key = "cameras",
            job = { camerasJob },
            setJob = { camerasJob = it },
            fetch = {
                val v =
                    if (where == null) cctvDataSource.fetch()
                    else cctvDataSource.near(where.first, where.second)
                cameraKeyAnswered = key
                v
            },
            gaps = { if (where == null) null else cctvDataSource.lastGaps },
            apply = { st, v -> st.copy(cameras = v) }
        )
    }

    /**
     * Restricted airspace and military ground around the view.
     *
     * Re-asked when the view moves, because half of it is a worldwide
     * set queried by position rather than a fixed national inventory.
     */
    @Volatile private var airspaceKey: String? = null

    /** As with the cameras: answered, not non-empty. */
    @Volatile private var airspaceKeyAnswered: String? = null

    fun ensureAirspace() {
        if (airspaceJob?.isActive == true) return
        val where = focus
        val key = where?.let { cellKey(it, AIRSPACE_CELL) } ?: "none"
        if (key == airspaceKey && key == airspaceKeyAnswered) return
        airspaceKey = key
        onDemand(
            key = "airspace",
            job = { airspaceJob },
            setJob = { airspaceJob = it },
            fetch = {
                val v =
                    if (where == null) airspaceDataSource.fetch()
                    else airspaceDataSource.near(where.first, where.second)
                airspaceKeyAnswered = key
                v
            },
            gaps = { if (where == null) null else airspaceDataSource.lastGaps },
            apply = { st, v -> st.copy(airspace = v) }
        )
    }

    /**
     * Radiation readings around the current view.
     *
     * Safecast holds tens of millions of points, so there is no global
     * fetch to make — the query is the viewport. It is only asked for while
     * the layer is on, and re-asked when the view moves far enough that the
     * previous answer no longer covers it.
     */
    /** True when the view has left the area the last radiation query covered. */
    /**
     * Which cell of its own grid a point falls in.
     *
     * One grid per on-demand layer, sized to roughly half what that
     * layer fetches, so the view has to leave the middle of the last
     * answer before another request goes out. Latitude only: a cell
     * that is a fixed number of degrees of longitude is a different
     * width at every latitude, but the layer's radius is a circle, so
     * erring small near the poles costs an occasional extra request
     * and never a stale one.
     */
    private fun cellKey(where: Pair<Double, Double>, cellDegrees: Double): String {
        val la = kotlin.math.floor(where.first / cellDegrees).toInt()
        val lo = kotlin.math.floor(where.second / cellDegrees).toInt()
        return "$la,$lo@$cellDegrees"
    }

    private fun movedBeyond(lat: Double, lon: Double, zoom: Double): Boolean {
        val last = radiationCentre ?: return true
        if (radiationZoomBand != zoomBand(zoom)) return true
        // Half the sweep radius: far enough that the previous answer has
        // stopped describing what is on screen.
        val limit = AreaReport.radiusForZoom(zoom) / 2
        return AreaReport.distanceKm(last.first, last.second, lat, lon) > limit
    }

    private fun zoomBand(zoom: Double): Int = when {
        zoom >= 11 -> 3
        zoom >= 8 -> 2
        zoom >= 5 -> 1
        else -> 0
    }

    fun ensureRadiation(on: Boolean? = null) {
        on?.let { radiationOn = it }
        if (!radiationOn) {
            radiationJob?.cancel()
            radiationJob = null
            radiationCentre = null
            radiationZoomBand = -1
            return
        }
        if (radiationJob?.isActive == true) return
        val (lat, lon) = focus ?: (20.0 to 0.0)
        val zoom = zoomLevel
        radiationJob = viewModelScope.launch {
            // Says so when it does not work, like the other three.
            //
            // This was the one on-demand layer still ending in
            // `.getOrNull()` with no else: a Safecast failure wrote no
            // data, no `loaded` and no `feedTrouble`, so the pill
            // showed "…" — still fetching — for the rest of the
            // session, with the app holding the reason and saying none
            // of it.
            val attempt = runCatching { radiationDataSource.fetch(lat, lon, zoom) }
            attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val v = attempt.getOrNull()
            _state.update { st ->
                if (v == null) {
                    st.copy(
                        feedTrouble = st.feedTrouble +
                            ("radiation" to (attempt.exceptionOrNull()?.message?.take(90)
                                ?: "no answer"))
                    )
                } else {
                    radiationCentre = lat to lon
                    radiationZoomBand = zoomBand(zoom)
                    st.copy(
                        radiation = v,
                        loaded = st.loaded + "radiation",
                        feedTrouble = st.feedTrouble - "radiation",
                        // As in `onDemand`: without this the markers
                        // are held and never planned.
                        layerVersion = st.layerVersion + 1
                    )
                }
            }
            radiationJob = null
        }
    }

    fun onMapClick(lat: Double, lon: Double) {
        pendingParentId = null
        pendingPivot = null
        onQueryChange("$lat, $lon")
        onSearch()
    }

    /** Back to the investigation this one came out of. */
    fun onOpenParent() {
        val ids = _state.value.breadcrumbIds
        if (ids.size >= 2) {
            openStored(ids[ids.size - 2])
            return
        }
        // No stored lineage — an older case, or one whose chain could not
        // be read. Searching is then the only way back, and losing the
        // chain is better than going nowhere.
        val chain = _state.value.breadcrumb
        if (chain.size < 2) return
        pendingParentId = null
        pendingPivot = null
        onQueryChange(chain[chain.size - 2])
        onSearch()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, queryTrouble = null) }
    }

    private var notesJob: Job? = null

    /**
     * What the investigator concluded, which no source can supply.
     *
     * The column has existed since the first version and nothing could
     * write to it, so an exported case carried every machine answer and
     * none of the reasoning. Kept in state as it is typed and written to
     * the database a moment after typing stops, rather than on every
     * keystroke; the investigation is created on the first character if
     * no tool has run yet, so a note taken before any search survives.
     */
    fun onNotesChange(text: String) {
        _state.update { it.copy(notes = text) }
        val observable = _state.value.observable ?: return
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            delay(NOTES_DEBOUNCE_MS)
            // The target is checked again after the wait. Between the
            // keystroke and the write the person may have searched
            // something else, and a note about one target written into
            // another case is evidence in the wrong file.
            if (_state.value.observable?.raw != observable.raw) return@launch
            runCatching {
                repository.setNotes(ensureInvestigation(observable), text)
            }
        }
    }

    /**
     * The investigator says a finding is wrong — or takes it back.
     *
     * This is the only place in the app where a person's judgement
     * outranks a source's, and it is the piece every OSINT handbook
     * assumes exists: the analyst correcting the collection. Beyond
     * striking the row it also counts against the source that produced
     * it, so a sweep that keeps being wrong about this target is read
     * one grade lower for the rest of the case.
     */
    fun setDismissed(
        kind: String,
        value: String,
        dismissed: Boolean,
        reason: String = ""
    ) {
        val observable = _state.value.observable ?: return
        val key = kind to value.lowercase()
        // Optimistic, so the grade moves under the finger rather than a
        // database round-trip later; the flow confirms it a moment after.
        _state.update { st ->
            st.copy(
                dismissals = if (dismissed) st.dismissals + (key to reason)
                else st.dismissals - key
            )
        }
        viewModelScope.launch {
            runCatching {
                repository.setDismissed(
                    ensureInvestigation(observable), kind, value, dismissed, reason
                )
            }
        }
    }

    /**
     * Why it was wrong, which is worth more in a report than the fact.
     *
     * Separate from [setDismissed] because marking something wrong has
     * to be one tap or nobody does it; the reason is offered afterwards
     * and most of the time will be left empty, which is fine.
     */
    fun setDismissalReason(kind: String, value: String, reason: String) {
        val observable = _state.value.observable ?: return
        val key = kind to value.lowercase()
        if (key !in _state.value.dismissals) return
        _state.update { it.copy(dismissals = it.dismissals + (key to reason.trim())) }
        viewModelScope.launch {
            runCatching {
                repository.setDismissalReason(
                    ensureInvestigation(observable), kind, value, reason
                )
            }
        }
    }

    fun setActiveRecon(on: Boolean) {
        settings.activeReconEnabled = on
        _state.update { it.copy(activeRecon = on) }
    }

    fun setFollowUp(on: Boolean) {
        settings.followUpEnabled = on
        _state.update { it.copy(followUp = on) }
    }

    fun onTypeSelected(type: String) {
        _state.update { it.copy(searchType = type) }
    }

    /**
     * A search that came out of the current one.
     *
     * Tapping a pivot used to start an unrelated investigation, so an
     * enquiry that actually was a chain came apart into disconnected
     * seeds. This records the edge.
     */
    fun onPivot(value: String) {
        pendingParentId = currentInvestigationId
        pendingPivot = value
        onQueryChange(value)
        onSearch()
    }

    /**
     * [fanOut] false prepares the dossier without contacting anybody.
     *
     * Used by the share handler: see MainActivity. The target is
     * classified, the tool list is worked out and the screen opens,
     * but nothing goes out until somebody presses RUN ALL.
     */
    fun onSearch(fanOut: Boolean = true) {
        // Tapping search with an empty box used to navigate to the dossier's
        // "enter something" placeholder, which is the app answering a
        // question nobody asked. The fix for that was to return, which
        // is the app not answering a question somebody did ask: no
        // toast, no error, no focus, nothing at all. From a tester's
        // seat the search button is simply broken, and that is one of
        // the things this app was reported as.
        if (_state.value.query.isBlank()) {
            _state.update {
                it.copy(queryTrouble = "Type a name, address, domain, handle or coordinate first.")
            }
            return
        }
        // Long enough to be a question rather than a target.
        //
        // Nothing capped this, and a box labelled "query any target"
        // invites a sentence. A sentence of five words or more is
        // read as an organisation and goes to the company registries
        // and the sanctions lists; a few thousand characters becomes
        // a dozen concurrent 414s, every one of them reported as the
        // source being unreachable rather than the question being
        // impossible.
        if (_state.value.query.trim().length > MAX_QUERY) {
            _state.update {
                it.copy(
                    queryTrouble = "That is longer than any target. Give the one " +
                        "thing you are looking for, not a sentence about it."
                )
            }
            return
        }
        val type = _state.value.searchType
        val observable = if (type == "auto") router.classify(_state.value.query)
            else router.classifyAs(_state.value.query, type)
        // New search = fresh investigation context.
        // Everything the previous search still had in flight. Left running,
        // its enrichment pass would call ensureInvestigation with the OLD
        // target after currentInvestigationId had been nulled, create a
        // second investigation for it, and redirect the results collector —
        // so the header would say the new target while the intel below it
        // was the old one.
        currentInvestigationId = null
        // What this search costs, counted from here. Every wave —
        // the first pass, the host of a URL, enrichment, a tool run
        // by hand — adds to the same bill, so the baseline belongs
        // where the search begins and nowhere else.
        dataBaseline = DataMeter.toolBytes()
        costRecorded = false
        // Anything still unwinding from the last search belongs to
        // the last search, including its counter decrements.
        searchRun++
        searchJobs.forEach { it.cancel() }
        searchJobs.clear()
        resultsJob?.cancel()
        dispatched.clear()
        // The denominator belongs to the target, like everything
        // else cleared here. Without this a share-handled search —
        // which does not call autoRun — and a case opened from
        // history both inherited the last live search's counts and
        // its "· 4 link lists, not counted" footnote.
        planned.clear()
        plannedLinks.clear()
        // The notes belong to the case that was open, not to the next one.
        // Left in place they appeared under the new target, were saved
        // into its row and were printed in its exported report — one
        // target's analyst conclusions filed as evidence about another.
        // The pending write is cancelled for the same reason: it would
        // have resolved the investigation id after this line and written
        // the old notes into the new case.
        notesJob?.cancel()
        notesJob = null
        // Marks belong to the case they were made in, for the same reason.
        dismissalsJob?.cancel()
        dismissalsJob = null
        // And the two reads started when the last case was opened,
        // which would otherwise land in this one.
        attachNotesJob?.cancel()
        attachNotesJob = null
        attachLineageJob?.cancel()
        attachLineageJob = null
        // A coordinate search focuses the globe immediately; other types get a
        // focus once a tool returns a location (see the results collector).
        val focus = (observable as? Observable.Coordinate)?.let { it.lat to it.lon }
        _state.update {
            it.copy(
                observable = observable,
                suggestedTools = runnable(router.suggestTools(observable)),
                workflowName = Workflows.forObservable(observable)?.name,
                results = emptyList(),
                findings = emptyList(),
                mapFocus = focus,
                runningTools = emptyMap(),
                plannedTools = 0,
                plannedLinkLists = 0,
                dataSpent = 0,
                dataTypical = settings.searchCost(observable.typeLabel),

                enrichmentRunning = false,
                enriching = emptyList(),
                breadcrumb = emptyList(),
                // And the ids behind it. Only the labels were cleared,
                // so until some tool happened to create the new case —
                // which may never happen, since every tool can be
                // filtered out — the back arrow still held the
                // previous case's chain and opened its parent, under
                // the new target's name.
                breadcrumbIds = emptyList(),
                notes = "",
                dismissals = emptyMap(),
                navSignal = it.navSignal + 1
            )
        }
        if (fanOut) autoRun()
    }

    /**
     * Fans out automatically — but only over sources that do not announce
     * you to the person you are investigating.
     *
     * Pressing search used to connect to the target's own servers, resolve
     * their hosts, fetch their files and request nine thousand profile URLs,
     * all from the investigator's own address, with no warning and nothing
     * asked. That is the first thing an investigation tool must not do. The
     * active tools are still one tap away, behind a switch that says what
     * they will touch.
     */
    private fun autoRun() {
        // An image search that does not read the image.
        //
        // exif is marked touchesTarget, which is right for every
        // other observable — reading a file off somebody's server
        // because their domain came up in a search is exactly what
        // the passive default exists to prevent. But when the
        // observable IS the image, the file is the thing the user
        // pasted and asked about, and fetching that one URL is the
        // minimum action that answers the question. Without it an
        // image search is two link builders and a Wayback call: the
        // one tool that extracts GPS and camera metadata was held
        // back from the one search that exists to run it.
        val isTheImage = _state.value.observable is Observable.ImageUrl
        val tools = _state.value.suggestedTools.filter {
            it.executionMode != ExecutionMode.CLOUD &&
                it.id !in REDUNDANT_SWEEPS &&
                (!it.touchesTarget || settings.activeReconEnabled ||
                    (isTheImage && it.id in IMAGE_ITSELF))
        }
        // Counted as sources. The link lists still run — their
        // output is a useful section — but a Google dork is not a
        // source that looked, and counting it as one is how "15 of
        // 21 sources" came to include four lists of places to go.
        planned.clear()
        notePlanned(tools)
        runToolsInParallel(tools, thenEnrich = settings.followUpEnabled)
        alsoTheHost()
    }

    /**
     * A URL is a domain plus a path, and the domain was thrown away.
     *
     * Searching a URL asked two questions — is it in the Wayback
     * index, and what is the latest capture — and asked nothing at all
     * about the host: not DNS, not the certificate log, not the
     * registration, not the security headers, none of the eighteen a
     * bare domain gets. That is the most common entry point on a
     * phone, because a link shared in from another app lands exactly
     * here.
     *
     * Dispatched as its own wave rather than merged into the URL's
     * tool list, because those tools take a domain and a path would
     * break them. Same gates as the first pass: no cloud tools, no
     * active recon unless it is switched on, and the rows land in the
     * same dossier marked one step from the seed — a fact about the
     * host is not a fact about the page.
     *
     * It multiplies the requests a URL search makes, from about two to
     * about twenty. That is the bill a domain search already pays, and
     * the dossier prints the planned count before anything fires.
     */
    private fun alsoTheHost() {
        // An image URL is a URL too. Observable.ImageUrl is a sibling
        // type rather than a subtype, so a shared image link got none
        // of the domain tools either.
        val raw = when (val o = _state.value.observable) {
            is Observable.Url -> o.raw
            is Observable.ImageUrl -> o.raw
            else -> return
        }
        val host = ObservableRouter.hostOf(raw) ?: return
        val asDomain = router.classify(host)
        if (asDomain !is Observable.Domain) return
        val tools = runnable(router.suggestTools(asDomain))
            .filter { it.executionMode == ExecutionMode.DIRECT }
            .filter { it.id !in REDUNDANT_SWEEPS }
            .filter { !it.touchesTarget || settings.activeReconEnabled }
        if (tools.isEmpty()) return
        notePlanned(tools)
        runToolsInParallel(tools, againstInput = host)
    }

    /** Runs the active tools for the current target, on request. */
    fun onRunActive() {
        val tools = _state.value.suggestedTools.filter {
            it.executionMode != ExecutionMode.CLOUD && it.touchesTarget
        }
        notePlanned(tools)
        runToolsInParallel(tools, force = true)
    }

    /** Stops everything in flight. Rows already dispatched are marked
     *  cancelled by the repository rather than left spinning. */
    fun onStop() {
        searchJobs.forEach { it.cancel() }
        searchJobs.clear()
        _state.update {
            it.copy(
                runningTools = emptyMap(),
                enrichmentRunning = false,
                enriching = emptyList(),
                workflowRunning = false
            )
        }
    }

    /** Runs the chained workflow for the current observable, piping each
     *  step's hosts into the next, persisting every step. */
    fun onRunWorkflow() {
        val observable = _state.value.observable ?: return
        val workflow = Workflows.forObservable(observable) ?: return
        val run = searchRun
        // Tracked, so STOP stops it. It was launched straight into the
        // view model's scope, outside the list STOP cancels — the button
        // set the state to "stopped" while the workflow carried on firing
        // sources at the target behind it.
        searchJobs addAndForget viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            _state.update { it.copy(workflowRunning = true, workflowSkipped = emptyList()) }
            // What did not run, and why.
            //
            // A chain is a sequence of promises and this kept none of
            // them. A step whose tool id is not in the registry was
            // skipped in silence; a step that needed the previous
            // step's hosts and had none was skipped in silence; and
            // because `previousHosts` was only overwritten on
            // success, a three-step chain whose middle step failed
            // ran step three on step ONE's hosts and presented the
            // result as step two's. The screen showed the workflow
            // finishing normally either way, indistinguishable from
            // a domain that genuinely has no subdomains.
            val skipped = mutableListOf<String>()
            try {
                var previousHosts = emptyList<String>()
                var brokenAt: String? = null
                for (step in workflow.steps) {
                    val tool = ToolRegistry.byId(step.toolId)
                    if (tool == null) {
                        skipped += "${step.toolId} (no such tool in this build)"
                        continue
                    }
                    if (step.fromPreviousHosts && brokenAt != null) {
                        skipped += "${tool.name} (needed what $brokenAt did not return)"
                        continue
                    }
                    if (step.fromPreviousHosts && previousHosts.isEmpty()) {
                        skipped += "${tool.name} (the step before it found no hosts)"
                        continue
                    }
                    val input =
                        if (step.fromPreviousHosts) previousHosts.joinToString("\n")
                        else observable.raw
                    if (run != searchRun) return@launch
                    _state.update { it.copy(runningTools = it.runningTools.plusRunning(listOf(tool.id))) }
                    val row = repository.runTool(investigationId, tool, input)
                    if (run == searchRun) {
                        _state.update { it.copy(runningTools = it.runningTools.minusRunning(tool.id)) }
                        noteDataSpent()
                    }
                    // A found answer, fragment or whole, not the literal
                    // status: a subdomain scan cut off at sixty seconds
                    // with forty hosts is "partial", and the chain threw
                    // those forty away and reported the step as broken.
                    if (Findings.kindOf(row) == Findings.Kind.HIT) {
                        previousHosts = Workflows.extractHosts(tool.id, row.rawOutput)
                        brokenAt = null
                    } else {
                        // Stale hosts are worse than none: the next
                        // step would otherwise run on the output of
                        // the step before last, under this one's name.
                        previousHosts = emptyList()
                        brokenAt = tool.name
                        skipped += "${tool.name} (${row.errorMessage?.take(60) ?: "did not finish"})"
                    }
                }
            } finally {
                _state.update {
                    it.copy(
                        workflowRunning = false,
                        workflowSkipped = skipped.toList()
                    )
                }
            }
        }
    }

    fun onToolClick(tool: Tool) {
        val observable = _state.value.observable ?: return
        // Also tracked: a single source run by hand is still a request in
        // flight, and STOP meant all of them.
        val run = searchRun
        searchJobs addAndForget viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            if (run != searchRun) return@launch
            _state.update { it.copy(runningTools = it.runningTools.plusRunning(listOf(tool.id))) }
            try {
                repository.runTool(investigationId, tool, observable.raw)
            } finally {
                if (run == searchRun) {
                    _state.update { it.copy(runningTools = it.runningTools.minusRunning(tool.id)) }
                    noteDataSpent()
                }
            }
        }
    }

    /**
     * Runs every suggested tool again.
     *
     * Pressing a button labelled RUN ALL and seeing nothing happen because
     * the automatic pass already ran those tools would be the app
     * second-guessing a deliberate instruction. The de-duplication exists
     * to stop the second pass repeating the first, not to overrule the
     * person, so this bypasses it.
     */
    fun onRunAll() {
        // This never touched the denominator at all, and it runs
        // everything the router named — including the tools autoRun
        // filtered out of it. Press RUN ALL after a passive scan and
        // the header read "scanning · 23/12 sources".
        notePlanned(_state.value.suggestedTools)
        runToolsInParallel(_state.value.suggestedTools, force = true)
        // And the host, for the entry point the host wave was written
        // for.
        //
        // alsoTheHost was called only from autoRun, and a link shared
        // in from another app arrives with fanOut = false and is run
        // by the person pressing RUN ALL — so the one case the
        // feature exists for was the one case it never fired in.
        alsoTheHost()
    }

    /**
     * Which sources this case has been told to run, counted once
     * each.
     *
     * Three callers dispatch tools and each of them used to do
     * something different with the denominator: autoRun set it,
     * onRunActive added to it — so pressing twice doubled it while
     * the numerator could not move, because force re-runs the same
     * rows — and onRunAll ignored it. A set of ids, cleared when the
     * target changes, is the only version of this that cannot drift.
     */
    private val planned = HashSet<String>()
    private val plannedLinks = HashSet<String>()

    private fun notePlanned(tools: List<Tool>) {
        tools.forEach {
            if (it.id in ToolRegistry.LINK_LISTS) plannedLinks.add(it.id) else planned.add(it.id)
        }
        _state.update {
            it.copy(plannedTools = planned.size, plannedLinkLists = plannedLinks.size)
        }
    }

    /**
     * A tool that asks thousands of hosts or runs for a minute: the
     * username sweeps, and anything given more than the default time.
     */
    private fun isSweep(tool: Tool): Boolean =
        tool.id in SWEEP_IDS || tool.timeoutMs > SWEEP_THRESHOLD_MS

    /** Runs the given tools concurrently so findings appear as fast as each
     *  tool returns, rather than trickling in one at a time. */
    private fun runToolsInParallel(
        tools: List<Tool>,
        thenEnrich: Boolean = false,
        force: Boolean = false,
        /**
         * What to run them against, when that is not the seed itself.
         *
         * Used for a URL's host: the same dossier, the same run, a
         * different question.
         */
        againstInput: String? = null
    ) {
        val observable = _state.value.observable ?: return
        val input = againstInput ?: observable.raw
        val fresh = if (force) {
            tools.onEach { dispatched.add("${it.id}|$input") }
        } else {
            tools.filter { dispatched.add("${it.id}|$input") }
        }
        if (fresh.isEmpty()) {
            if (thenEnrich) searchJobs addAndForget viewModelScope.launch { enrich(observable) }
            return
        }
        val run = searchRun
        searchJobs addAndForget viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            if (run != searchRun) return@launch
            _state.update { it.copy(runningTools = it.runningTools.plusRunning(fresh.map { t -> t.id })) }
            // The sweeps wait for the quick lookups.
            //
            // Everything used to start in the same instant, so a username
            // sweep asking 2,800 sites began alongside lookups that have
            // thirty seconds to answer — and in a real case five of them
            // (the infostealer check, the keyserver, email-to-GitHub, mail
            // security, the search pivots), on five unrelated hosts, all
            // died at the thirty-second cap during the sweep's window.
            // What they share with it is the phone: one radio and one
            // resolver, with thousands of lookups queued in front of
            // theirs. The quick tools get the network first; a sweep
            // starts when they are done, or after a fixed hold if one of
            // them hangs. A sweep runs for a minute anyway, so the hold
            // costs little and the thirty-second tools stop timing out.
            val quick = fresh.filterNot { isSweep(it) }
            val quickDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            val produced = coroutineScope {
                // Cancelled below once every tool is done: coroutineScope
                // waits for its children, and a timer left running would
                // hold the follow-up pass for the whole hold.
                val hold = launch {
                    kotlinx.coroutines.delay(SWEEP_HOLD_MS)
                    quickDone.complete(Unit)
                }
                val remaining = java.util.concurrent.atomic.AtomicInteger(quick.size)
                if (quick.isEmpty()) quickDone.complete(Unit)
                fresh.map { tool ->
                    async {
                        val sweep = isSweep(tool)
                        try {
                            // Inside the try, so a search cancelled during
                            // the hold still reaches the finally and stops
                            // listing this tool as running.
                            if (sweep) quickDone.await()
                            repository.runTool(investigationId, tool, input)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Not the tool failing — runTool turns that
                            // into an error row itself. This is the
                            // database refusing, so the row inserted as
                            // "running" is never updated and the dossier
                            // says "N still running" for the rest of the
                            // session. Say so once, rather than leaving
                            // a spinner that will never stop.
                            _state.update {
                                it.copy(
                                    feedTrouble = it.feedTrouble +
                                        ("${tool.id} result" to
                                            (e.message ?: "could not be recorded"))
                                )
                            }
                            null
                        } finally {
                            if (!sweep && remaining.decrementAndGet() == 0) quickDone.complete(Unit)
                            if (run == searchRun) {
                                _state.update {
                                    it.copy(runningTools = it.runningTools.minusRunning(tool.id))
                                }
                                noteDataSpent()
                            }
                        }
                    }
                }.map { it.await() }.also {
                    quickDone.complete(Unit)
                    hold.cancel()
                }
            }
            if (thenEnrich) enrich(observable, produced.filterNotNull())
            // Recorded once, after the pass that carries the follow-up.
            //
            // The estimate shown before the next search of this type
            // is only worth having if it covers the whole bill, and
            // the second pass is most of it.
            if (run == searchRun && !costRecorded) {
                costRecorded = true
                val spent = (DataMeter.toolBytes() - dataBaseline).coerceAtLeast(0)
                if (spent > 0) settings.recordSearchCost(observable.typeLabel, spent)
            }
        }
    }

    /**
     * The second pass.
     *
     * The first pass can only look up what you typed. Its output, though,
     * names things you could not have known to ask for: the IPs a domain
     * resolves to, the hosts a certificate covers, the address a registration
     * lists, the handle a profile links. An analyst's next move is to look
     * those up too, and that is the difference between a page of lookups and
     * a dossier — so the app does it rather than waiting to be asked.
     *
     * One round only, and with quotas. Pivots corroborate each other, so
     * chasing everything would fan out without bound and spend the whole
     * budget on the noisiest kind of identifier.
     */
    private suspend fun enrich(seed: Observable, firstPass: List<ResultRow> = emptyList()) {
        if (!settings.followUpEnabled) return
        val run = searchRun
        // The rows from the pass that just finished, handed over directly.
        // This used to sleep 400ms and then read UI state, hoping the Room
        // flow had caught up — on a slow phone it read a partial set and
        // missed pivots.
        val rows = (firstPass + _state.value.results).distinctBy { it.id }
        if (rows.isEmpty()) return

        val quota = mapOf("IP" to 3, "HOST" to 3, "EMAIL" to 2, "HANDLE" to 2, "WALLET" to 1)
        val spent = HashMap<String, Int>()
        val chase = EntityExtractor.extract(
            rows,
            seed.raw,
            limitPerKind = 8,
            seedIsPlace = seed is Observable.Coordinate
        ).filter { p ->
            val cap = quota[p.kind] ?: return@filter false
            val n = spent[p.kind] ?: 0
            if (n >= cap) false else { spent[p.kind] = n + 1; true }
        }
        if (chase.isEmpty()) return

        val investigationId = ensureInvestigation(seed)
        _state.update { it.copy(enrichmentRunning = true, enriching = chase.map { p -> p.value }) }
        try {
            for (pivot in chase) {
                val observable = router.classify(pivot.value)
                // take() first, then mark dispatched — otherwise tools trimmed
                // off the end get recorded as run when they never were.
                // The same two filters the first pass uses, and for the
                // same reasons. This pass had neither.
                //
                // Without the active-recon gate the follow-up walked
                // straight through the switch the first-run screen
                // promises: a discovered host pivot ran tlscert, which
                // opens a connection to the target's own server from
                // the investigator's address, and a discovered email
                // pivot ran the full on-device username sweep —
                // thousands of requests at sixty-four at a time, to
                // every site in the ruleset, unannounced. A person who
                // left that switch off had been told that would not
                // happen.
                //
                // And without the redundancy filter a handle pivot ran
                // the same sweep three times over, because sherlock,
                // maigret and blackbird all resolve to one checker —
                // three times the traffic to every one of those sites,
                // and three identical rows that the corroboration pass
                // then counted as three sources agreeing.
                val tools = runnable(router.suggestTools(observable))
                    .filter { it.executionMode == ExecutionMode.DIRECT }
                    .filter { it.id !in REDUNDANT_SWEEPS }
                    .filter { !it.touchesTarget || settings.activeReconEnabled }
                    .take(TOOLS_PER_PIVOT)
                    .filter { dispatched.add("${it.id}|${pivot.value}") }
                if (tools.isEmpty()) continue
                if (run != searchRun) return
                _state.update { it.copy(runningTools = it.runningTools.plusRunning(tools.map { t -> t.id })) }
                coroutineScope {
                    tools.forEach { tool ->
                        launch {
                            try {
                                repository.runTool(investigationId, tool, pivot.value)
                            } finally {
                                // The same guard the first pass
                                // carries. A cancelled job writes a
                                // cancelled row under NonCancellable
                                // before it rethrows, so this lands
                                // after the next search has added
                                // its own counts — and the first
                                // pass and the enrichment set share
                                // most of their tool ids, so it
                                // removed counts belonging to the
                                // run in flight. Where the count was
                                // one, the progress bar stopped and
                                // the dossier said there was nothing
                                // while the scan was still going.
                                if (run == searchRun) {
                                    _state.update { st ->
                                        st.copy(runningTools = st.runningTools.minusRunning(tool.id))
                                    }
                                    noteDataSpent()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            _state.update { it.copy(enrichmentRunning = false, enriching = emptyList()) }
        }
    }

    private suspend fun ensureInvestigation(observable: Observable): Long =
        investigationMutex.withLock { ensureInvestigationLocked(observable) }

    private suspend fun ensureInvestigationLocked(observable: Observable): Long {
        currentInvestigationId?.let { return it }
        val id = repository.createInvestigation(observable, pendingParentId, pendingPivot)
        pendingParentId = null
        pendingPivot = null
        currentInvestigationId = id
        attachTo(id)
        return id
    }

    /**
     * Points the screen at a stored case: its notes, its lineage and a
     * live view of its results. Runs nothing.
     */
    private fun attachTo(id: Long) {
        dismissalsJob?.cancel()
        dismissalsJob = viewModelScope.launch {
            repository.observeDismissals(id).collect { marked ->
                _state.update { it.copy(dismissals = marked) }
            }
        }
        // Both of these are held and cancelled, for the same reason
        // the notes debounce is.
        //
        // They were not, so neither a new search nor opening another
        // case could stop them: open case A, start a search for B
        // while A's notes are still being read, and A's notes land in
        // state after B has cleared it. Type one character and the
        // debounce persists that merged text against B's row. The
        // lineage launch did the same to the breadcrumb, which is how
        // the back arrow ended up opening the previous case's parent.
        attachNotesJob?.cancel()
        attachNotesJob = viewModelScope.launch {
            // Re-opening a case brings its notes back with it.
            val saved = runCatching { repository.notesFor(id) }.getOrNull().orEmpty()
            _state.update { it.copy(notes = saved) }
        }
        attachLineageJob?.cancel()
        attachLineageJob = viewModelScope.launch {
            val chain = runCatching { repository.lineage(id) }.getOrDefault(emptyList())
            _state.update { st ->
                st.copy(
                    breadcrumb = chain.map { it.seedObservable },
                    breadcrumbIds = chain.map { it.id }
                )
            }
        }
        resultsJob?.cancel()
        resultsJob = viewModelScope.launch {
            repository.observeResults(id).collect { rows ->
                // Plot any result that carries a location, and focus the globe
                // on the first one if the search didn't already set a focus.
                val findings = rows.flatMap { r ->
                    // Found, not merely "success": an absence that names
                    // the place it looked ("no cameras within 25 km of
                    // 51.5, -0.1") was pinned as though something was
                    // there. And a fragment's locations are real.
                    if (Findings.kindOf(r) != Findings.Kind.HIT) emptyList()
                    else GeoExtractor.allCoordinates(r.rawOutput)
                        .take(perToolPinCap(r.toolId))
                        .map { FindingPin(it.first, it.second, r.toolName) }
                }
                _state.update { st ->
                    st.copy(
                        results = rows,
                        findings = findings,
                        mapFocus = st.mapFocus ?: findings.firstOrNull()?.let { it.lat to it.lon }
                    )
                }
            }
        }
    }

    /**
     * Tools whose coordinates describe the surroundings, not the
     * target.
     *
     * Every coordinate any tool printed became a magenta FINDING pin
     * at the top of the draw order, described in the UI as "the
     * investigation's own findings", and the globe flew to the first
     * of them. Searching a coordinate runs `wikiNearby`, which lists
     * twenty-five notable places within ten kilometres — so
     * Wikipedia's list of landmarks in the area was presented as
     * twenty-five findings about the target, and counted as such in
     * the area report.
     *
     * These tools answer "what is around here", which is useful and
     * is not a finding. The layers already draw that kind of thing.
     */
    private fun perToolPinCap(toolId: String): Int = when (toolId) {
        "wikinearby", "cctvnear", "photosnear", "osmrecon", "milsites",
        "overpass_turbo", "ukcrime", "sunterrain", "histweather" -> 0
        // A geocoder's answer is the first candidate; the rest are
        // the alternatives it is offering.
        "geocode", "revgeo" -> 1
        else -> 8
    }

    /**
     * Opens a case that already exists, without asking anything again.
     *
     * Going back up a pivot chain used to re-run the parent search: every
     * automatic source fired at the parent for a second time, a fresh
     * investigation was created with no parent, and the chain above it
     * became unreachable. Going back is navigation, not an enquiry.
     */
    fun openStored(id: Long) {
        viewModelScope.launch {
            val investigation = runCatching { repository.byId(id) }.getOrNull() ?: return@launch
            searchJobs.forEach { it.cancel() }
            searchJobs.clear()
            notesJob?.cancel()
            notesJob = null
            dispatched.clear()
            // And its counts, which were the last live search's.
            searchRun++
            planned.clear()
            plannedLinks.clear()
            pendingParentId = null
            pendingPivot = null
            currentInvestigationId = id
            val observable = router.classify(investigation.seedObservable)
            _state.update {
                it.copy(
                    query = investigation.seedObservable,
                    observable = observable,
                    suggestedTools = runnable(router.suggestTools(observable)),
                    workflowName = Workflows.forObservable(observable)?.name,
                    results = emptyList(),
                    findings = emptyList(),
                    runningTools = emptyMap(),
                    plannedTools = 0,
                    plannedLinkLists = 0,
                    enrichmentRunning = false,
                    enriching = emptyList(),
                    notes = "",
                    // And the marks, which belong to the case they
                    // were made in.
                    //
                    // These were left holding the previous case's map
                    // until the new case's flow first emitted. In that
                    // window the corroboration pass ran with the wrong
                    // dismissals, so findings in the case just opened
                    // were shown struck through, their grades pulled
                    // down to doubtful, and an export taken then wrote
                    // them into this case's ruled-out list. A search
                    // clears this; opening a stored case did not.
                    dismissals = emptyMap(),
                    breadcrumb = emptyList(),
                    breadcrumbIds = emptyList(),
                    navSignal = it.navSignal + 1
                )
            }
            attachTo(id)
        }
    }

    /**
     * Keeps a page from the isolated browser in the case.
     *
     * If no case is open, the page's own host becomes one — capturing
     * evidence should not require having first thought to start an
     * investigation.
     */
    fun onSnapshot(url: String, title: String, html: String) {
        viewModelScope.launch {
            val existing = _state.value.observable
            val observable = existing ?: router.classify(hostOf(url)).also { fresh ->
                _state.update {
                    it.copy(
                        query = fresh.raw,
                        observable = fresh,
                        suggestedTools = runnable(router.suggestTools(fresh)),
                        navSignal = it.navSignal + 1
                    )
                }
            }
            val saved = runCatching {
                repository.recordSnapshot(ensureInvestigation(observable), url, title, html)
            }
            saved.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                // Said out loud. A bare runCatching here meant that
                // a page the person had deliberately kept — a
                // CursorWindow too small for it, a full disk —
                // vanished with no error and no row, and the browser
                // carried on as though it had been saved.
                _state.update {
                    it.copy(
                        feedTrouble = it.feedTrouble +
                            ("snapshot" to (e.message?.take(90) ?: "could not be saved"))
                    )
                }
            }
        }
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty().ifBlank { url }
    }.getOrDefault(url)

    /** Builds an export file for the current observable + results, or null. */
    suspend fun buildExport(format: ExportFormat): File? {
        val observable = _state.value.observable ?: return null
        val results = _state.value.results
        if (results.isEmpty()) return null
        // Read for this case, not taken from the flow.
        //
        // openStored clears dismissals to empty and then starts a
        // collector that refills them, and the results flow refills
        // separately — so there is a window where the dossier has
        // every result and none of the marks. Exporting in it
        // produced a report with every struck finding printed as a
        // live, graded lead, no RULED OUT section, and no demotion of
        // the sources behind them. A dossier that found the wrong
        // person and says so is useful; one that found the wrong
        // person and does not is the failure this app's own notes
        // call worse than finding nothing. HistoryViewModel.export
        // already re-reads for exactly this reason.
        val marks = currentInvestigationId?.let { id ->
            runCatching { repository.dismissalsFor(id) }.getOrNull()
        } ?: _state.value.dismissals
        return exporter.export(
            observable, results, format, _state.value.notes, marks
        )
    }

    fun saveSettings(
        backendUrl: String,
        tar1090Url: String,
        aisUrl: String,
        overlayUrl: String,
        secContactEmail: String,
        backendToken: String
    ) {
        settings.backendBaseUrl = backendUrl
        settings.tar1090Url = tar1090Url
        settings.aisUrl = aisUrl
        settings.overlayUrl = overlayUrl
        settings.secContactEmail = secContactEmail
        settings.backendToken = backendToken
        _state.update {
            it.copy(
                backendUrl = settings.backendBaseUrl,
                tar1090Url = settings.tar1090Url,
                aisUrl = settings.aisUrl,
                overlayUrl = settings.overlayUrl,
                secContactEmail = settings.secContactEmail,
                backendToken = settings.backendToken
            )
        }
    }

    class Factory(
        private val router: ObservableRouter,
        private val repository: InvestigationRepository,
        private val aircraftDataSource: AircraftDataSource,
        private val aisDataSource: AisDataSource,
        private val earthquakeDataSource: EarthquakeDataSource,
        private val naturalEventsDataSource: NaturalEventsDataSource,
        private val overlayDataSource: OverlayDataSource,
        private val weatherDataSource: WeatherDataSource,
        private val firesDataSource: FiresDataSource,
        private val satellitesDataSource: SatellitesDataSource,
        private val newsDataSource: NewsDataSource,
        private val gdacsDataSource: GdacsDataSource,
        private val volcanoDataSource: VolcanoDataSource,
        private val worldWarningsDataSource: WorldWarningsDataSource,
        private val seaLevelDataSource: SeaLevelDataSource,
        private val outageDataSource: OutageDataSource,
        private val portDataSource: BundledPointsDataSource,
        private val launchDataSource: LaunchDataSource,
        private val sitesDataSource: SitesDataSource,
        private val auroraDataSource: AuroraDataSource,
        private val buoyDataSource: BuoyDataSource,
        private val metarDataSource: MetarDataSource,
        private val milAircraftDataSource: MilAircraftDataSource,
        private val airQualityDataSource: AirQualityDataSource,
        private val seismicStationDataSource: SeismicStationDataSource,
        private val conflictDataSource: ConflictDataSource,
        private val airportDataSource: BundledPointsDataSource,
        private val powerPlantDataSource: BundledPointsDataSource,
        private val cctvDataSource: CctvDataSource,
        private val airspaceDataSource: AirspaceDataSource,
        private val spaceWeatherDataSource: SpaceWeatherDataSource,
        private val cableLandingDataSource: CableLandingDataSource,
        private val cycloneDataSource: CycloneDataSource,
        private val tsunamiDataSource: TsunamiDataSource,
        private val radiationDataSource: RadiationDataSource,
        private val airportStatusDataSource: AirportStatusDataSource,
        private val availableLocalTools: Set<String>,
        private val settings: AppSettings,
        private val exporter: InvestigationExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GodEyeViewModel(router, repository, aircraftDataSource, aisDataSource, earthquakeDataSource, naturalEventsDataSource, overlayDataSource, weatherDataSource, firesDataSource, satellitesDataSource, newsDataSource, gdacsDataSource, volcanoDataSource, worldWarningsDataSource, seaLevelDataSource, outageDataSource, portDataSource, launchDataSource, sitesDataSource, auroraDataSource, buoyDataSource, metarDataSource, milAircraftDataSource, airQualityDataSource, seismicStationDataSource, conflictDataSource, airportDataSource, powerPlantDataSource, cctvDataSource, airspaceDataSource, spaceWeatherDataSource, cableLandingDataSource, cycloneDataSource, tsunamiDataSource, radiationDataSource, airportStatusDataSource, availableLocalTools, settings, exporter) as T
    }

    /**
     * A tool can be running against more than one identifier at once.
     *
     * The second pass runs the same tool against each discovered
     * identifier, and when these were tracked as a set the first of those
     * to finish marked the tool idle while the others were still going —
     * so the spinner stopped and RUN ALL came back while work was in
     * flight. Counting them keeps the state honest, and reaching zero is
     * the only thing that removes the entry.
     */
    private fun Map<String, Int>.plusRunning(ids: List<String>): Map<String, Int> {
        if (ids.isEmpty()) return this
        val next = HashMap(this)
        ids.forEach { next[it] = (next[it] ?: 0) + 1 }
        return next
    }

    private fun Map<String, Int>.minusRunning(id: String): Map<String, Int> {
        val count = this[id] ?: return this
        val next = HashMap(this)
        if (count <= 1) next.remove(id) else next[id] = count - 1
        return next
    }

    private companion object {
        /** The username sweeps, by id: the heaviest thing a dossier runs. */
        val SWEEP_IDS = setOf("sherlock", "maigret", "blackbird", "socialsweep", "emailaccounts")

        /** Longer than the default thirty seconds marks a long-running tool. */
        const val SWEEP_THRESHOLD_MS = 45_000L

        /** The longest a sweep waits for the quick lookups to clear. */
        const val SWEEP_HOLD_MS = 20_000L

        /** How often a sleeping feed checks whether its layer came back
         *  on. Short enough to feel immediate, long enough to cost nothing. */
        /** Tool ids that all resolve to the same username sweep, so
         *  running more than one of them is the same several thousand
         *  requests over again for the same answer. */
        val REDUNDANT_SWEEPS = setOf("maigret", "blackbird")

        /** The longest thing that is still a target and not a
         *  question about one. A domain tops out at 253 characters
         *  and nothing else here comes close. */
        const val MAX_QUERY = 256

        /** Tools that touch the target only by reading the one file
         *  the user pasted, which is the thing they asked about. */
        val IMAGE_ITSELF = setOf("exif", "c2pa", "imagetext", "imagehead")

        const val POLL_IDLE_MS = 3_000L

        /** The same wait, for an app nobody is looking at. */
        const val BACKGROUND_IDLE_MS = 60_000L

        /** The longest a poll sleeps without checking whether its layer
         *  is still on. */
        const val WAIT_SLICE_MS = 30_000L

        /** The longest a feed waits after a failure before trying
         *  again, whatever its own interval is. */
        const val RETRY_MS = 2 * 60_000L

        /** Per discovered identifier, in the second pass. Enough for a real
         *  profile of it; few enough that eleven of them stay affordable. */
        const val TOOLS_PER_PIVOT = 6

        /** Long enough that typing a sentence is one write, short enough
         *  that leaving the screen cannot lose it. */
        const val NOTES_DEBOUNCE_MS = 700L
    }

}
