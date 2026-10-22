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
import com.osint.unified.data.WeatherDataSource
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.GeoExtractor
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Result as ResultRow
import java.io.File
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import com.osint.unified.domain.ToolRegistry
import com.osint.unified.domain.workflow.Workflows
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
    /** Layer key -> when it last answered, for "stale 12m" on the pill. */
    val lastSuccess: Map<String, Long> = emptyMap(),
    val findings: List<FindingPin> = emptyList(),
    val mapFocus: Pair<Double, Double>? = null,
    val results: List<ResultRow> = emptyList(),
    val runningToolIds: Set<String> = emptySet(),
    val workflowName: String? = null,
    val workflowRunning: Boolean = false,
    /** The second pass is chasing what the first pass turned up. */
    val enrichmentRunning: Boolean = false,
    /** Identifiers the first pass discovered and the second pass is looking
     *  up in their own right — shown so the derivation is never a mystery. */
    val enriching: List<String> = emptyList(),
    val navSignal: Long = 0,
    val backendUrl: String = "",
    val tar1090Url: String = "",
    val aisUrl: String = "",
    val overlayUrl: String = "",
    val searchType: String = "auto"
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

    /** Everything the current search has in flight, so a new search can
     *  stop it rather than letting two searches write to one dossier. */
    private val searchJobs = mutableListOf<Job>()

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

    private val _state = MutableStateFlow(
        GodEyeUiState(
            backendUrl = settings.backendBaseUrl,
            tar1090Url = settings.tar1090Url,
            aisUrl = settings.aisUrl,
            overlayUrl = settings.overlayUrl
        )
    )
    val state: StateFlow<GodEyeUiState> = _state.asStateFlow()

    private var currentInvestigationId: Long? = null
    private var resultsJob: Job? = null

    init {
        // Staggered start-up so launch doesn't fire every feed at once (which
        // spiked memory/CPU). Each poll is exception-guarded so one bad fetch
        // can't kill its loop, and updates stay atomic per field.
        poll(0, 15_000, "aircraft", { aircraftDataSource.fetch(focus, wideView) }) { s, v -> s.copy(aircraft = v) }
        poll(100, 30_000, "vessels", { aisDataSource.fetch() }) { s, v -> s.copy(vessels = v) }
        poll(200, 15_000, "satellites", { satellitesDataSource.fetch() }) { s, v -> s.copy(satellites = v) }
        poll(375, 300_000, "quakes", { earthquakeDataSource.fetch() }) { s, v -> s.copy(quakes = v) }
        poll(625, 600_000, "naturalEvents", { naturalEventsDataSource.fetch() }) { s, v -> s.copy(naturalEvents = v) }
        poll(875, 60_000, "overlayPoints", { overlayDataSource.fetch() }) { s, v -> s.copy(overlayPoints = v) }
        poll(1125, 600_000, "fires", { firesDataSource.fetch() }) { s, v -> s.copy(fires = v) }
        poll(1375, 600_000, "volcanoes", { volcanoDataSource.fetch() }) { s, v -> s.copy(volcanoes = v) }
        poll(1625, 600_000, "gdacs", { gdacsDataSource.fetch() }) { s, v -> s.copy(gdacs = v) }
        poll(1875, 300_000, "weather", { weatherDataSource.fetch() }) { s, v -> s.copy(weather = v) }
        poll(2125, 300_000, "aurora", { auroraDataSource.fetch() }) { s, v -> s.copy(aurora = v) }
        poll(2375, 300_000, "news", { newsDataSource.fetch() }) { s, v -> s.copy(news = v) }
        poll(2750, 600_000, "buoys", { buoyDataSource.fetch() }) { s, v -> s.copy(buoys = v) }
        poll(3250, 600_000, "metar", { metarDataSource.fetch() }) { s, v -> s.copy(metar = v) }
        poll(500, 20_000, "milAircraft", { milAircraftDataSource.fetch() }) { s, v -> s.copy(milAircraft = v) }
        poll(3500, 900_000, "airQuality", { airQualityDataSource.fetch() }) { s, v -> s.copy(airQuality = v) }
        poll(3750, 21_600_000, "stations", { seismicStationDataSource.fetch() }) { s, v -> s.copy(stations = v) }
        poll(4000, 1_800_000, "conflicts", { conflictDataSource.fetch() }) { s, v -> s.copy(conflicts = v) }
        poll(300, 3_600_000, "airports", { airportDataSource.fetch() }) { s, v -> s.copy(airports = v) }
        poll(450, 3_600_000, "powerPlants", { powerPlantDataSource.fetch() }) { s, v -> s.copy(powerPlants = v) }
        poll(900, 900_000, "spaceWeather", { spaceWeatherDataSource.fetch() }) { s, v -> s.copy(spaceWeather = v) }
        poll(1200, 86_400_000, "cables", { cableLandingDataSource.fetch() }) { s, v -> s.copy(cables = v) }
        poll(700, 600_000, "cyclones", { cycloneDataSource.fetch() }) { s, v -> s.copy(cyclones = v) }
        poll(800, 600_000, "tsunamis", { tsunamiDataSource.fetch() }) { s, v -> s.copy(tsunamis = v) }
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
        apply: (GodEyeUiState, T) -> GodEyeUiState
    ) {
        viewModelScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            while (isActive) {
                val attempt = runCatching { fetch() }
                attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val result = attempt.getOrNull()
                // "loaded" means this feed has answered — only then does a
                // count of zero mean "nothing out there", which is what the
                // pills and the world brief go on to assert. A failed fetch
                // records the trouble and keeps whatever it last had, so
                // neither of them claims an all-clear during an outage.
                _state.update { state ->
                    if (result == null) {
                        val why = attempt.exceptionOrNull()?.message?.take(90)
                            ?: "no answer"
                        state.copy(feedTrouble = state.feedTrouble + (key to why))
                    } else {
                        apply(
                            state.copy(
                                loaded = state.loaded + key,
                                feedTrouble = state.feedTrouble - key,
                                lastSuccess = state.lastSuccess +
                                    (key to System.currentTimeMillis())
                            ),
                            result
                        )
                    }
                }
                delay(intervalMs)
            }
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
    }

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
    fun ensureCameras() {
        if (camerasJob?.isActive == true || _state.value.cameras.isNotEmpty()) return
        camerasJob = viewModelScope.launch {
            val v = runCatching { cctvDataSource.fetch() }.getOrNull()
            _state.update {
                if (v == null) it else it.copy(cameras = v, loaded = it.loaded + "cameras")
            }
            camerasJob = null
        }
    }

    /** Likewise the FAA airspace set, which pages through thousands of polygons. */
    fun ensureAirspace() {
        if (airspaceJob?.isActive == true || _state.value.airspace.isNotEmpty()) return
        airspaceJob = viewModelScope.launch {
            val v = runCatching { airspaceDataSource.fetch() }.getOrNull()
            _state.update {
                if (v == null) it else it.copy(airspace = v, loaded = it.loaded + "airspace")
            }
            airspaceJob = null
        }
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
            val v = runCatching { radiationDataSource.fetch(lat, lon, zoom) }.getOrNull()
            if (v != null) {
                radiationCentre = lat to lon
                radiationZoomBand = zoomBand(zoom)
                _state.update { it.copy(radiation = v, loaded = it.loaded + "radiation") }
            }
            radiationJob = null
        }
    }

    fun onMapClick(lat: Double, lon: Double) {
        onQueryChange("$lat, $lon")
        onSearch()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun onTypeSelected(type: String) {
        _state.update { it.copy(searchType = type) }
    }

    fun onSearch() {
        // Tapping search with an empty box used to navigate to the dossier's
        // "enter something" placeholder, which is the app answering a
        // question nobody asked.
        if (_state.value.query.isBlank()) return
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
        searchJobs.forEach { it.cancel() }
        searchJobs.clear()
        resultsJob?.cancel()
        dispatched.clear()
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
                runningToolIds = emptySet(),
                enrichmentRunning = false,
                enriching = emptyList(),
                navSignal = it.navSignal + 1
            )
        }
        autoRun()
    }

    /**
     * Fans out automatically: runs every suggested tool that needs no backend
     * (DIRECT + on-device LOCAL), so a search immediately returns a broad
     * picture. Cloud tools and redundant username checks are left to tap.
     */
    private fun autoRun() {
        val redundant = setOf("maigret", "blackbird")
        val tools = _state.value.suggestedTools.filter {
            it.executionMode != ExecutionMode.CLOUD && it.id !in redundant
        }
        runToolsInParallel(tools, thenEnrich = true)
    }

    /** Runs the chained workflow for the current observable, piping each
     *  step's hosts into the next, persisting every step. */
    fun onRunWorkflow() {
        val observable = _state.value.observable ?: return
        val workflow = Workflows.forObservable(observable) ?: return
        viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            _state.update { it.copy(workflowRunning = true) }
            try {
                var previousHosts = emptyList<String>()
                for (step in workflow.steps) {
                    val tool = ToolRegistry.byId(step.toolId) ?: continue
                    val input = if (step.fromPreviousHosts) previousHosts.joinToString("\n") else observable.raw
                    if (step.fromPreviousHosts && previousHosts.isEmpty()) continue
                    _state.update { it.copy(runningToolIds = it.runningToolIds + tool.id) }
                    val row = repository.runTool(investigationId, tool, input)
                    _state.update { it.copy(runningToolIds = it.runningToolIds - tool.id) }
                    if (row.status == "success") {
                        previousHosts = Workflows.extractHosts(tool.id, row.rawOutput)
                    }
                }
            } finally {
                _state.update { it.copy(workflowRunning = false) }
            }
        }
    }

    fun onToolClick(tool: Tool) {
        val observable = _state.value.observable ?: return
        viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            _state.update { it.copy(runningToolIds = it.runningToolIds + tool.id) }
            try {
                repository.runTool(investigationId, tool, observable.raw)
            } finally {
                _state.update { it.copy(runningToolIds = it.runningToolIds - tool.id) }
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
        runToolsInParallel(_state.value.suggestedTools, force = true)
    }

    /** Runs the given tools concurrently so findings appear as fast as each
     *  tool returns, rather than trickling in one at a time. */
    private fun runToolsInParallel(
        tools: List<Tool>,
        thenEnrich: Boolean = false,
        force: Boolean = false
    ) {
        val observable = _state.value.observable ?: return
        val fresh = if (force) {
            tools.onEach { dispatched.add("${it.id}|${observable.raw}") }
        } else {
            tools.filter { dispatched.add("${it.id}|${observable.raw}") }
        }
        if (fresh.isEmpty()) {
            if (thenEnrich) searchJobs += viewModelScope.launch { enrich(observable) }
            return
        }
        searchJobs += viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            _state.update { it.copy(runningToolIds = it.runningToolIds + fresh.map { t -> t.id }) }
            coroutineScope {
                fresh.forEach { tool ->
                    launch {
                        try {
                            repository.runTool(investigationId, tool, observable.raw)
                        } finally {
                            _state.update { it.copy(runningToolIds = it.runningToolIds - tool.id) }
                        }
                    }
                }
            }
            if (thenEnrich) enrich(observable)
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
    private suspend fun enrich(seed: Observable) {
        // Results arrive through the Room flow, so give the last insert a
        // moment to land before reading what the pass produced.
        delay(400)
        val rows = _state.value.results
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
                val tools = runnable(router.suggestTools(observable))
                    .filter { it.executionMode == ExecutionMode.DIRECT }
                    .take(TOOLS_PER_PIVOT)
                    .filter { dispatched.add("${it.id}|${pivot.value}") }
                if (tools.isEmpty()) continue
                _state.update { it.copy(runningToolIds = it.runningToolIds + tools.map { t -> t.id }) }
                coroutineScope {
                    tools.forEach { tool ->
                        launch {
                            try {
                                repository.runTool(investigationId, tool, pivot.value)
                            } finally {
                                _state.update { st ->
                                    st.copy(runningToolIds = st.runningToolIds - tool.id)
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
        val id = repository.createInvestigation(observable)
        currentInvestigationId = id
        resultsJob?.cancel()
        resultsJob = viewModelScope.launch {
            repository.observeResults(id).collect { rows ->
                // Plot any result that carries a location, and focus the globe
                // on the first one if the search didn't already set a focus.
                val findings = rows.flatMap { r ->
                    if (r.status != "success") emptyList()
                    else GeoExtractor.allCoordinates(r.rawOutput).take(30)
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
        return id
    }

    /** Builds an export file for the current observable + results, or null. */
    suspend fun buildExport(format: ExportFormat): File? {
        val observable = _state.value.observable ?: return null
        val results = _state.value.results
        if (results.isEmpty()) return null
        return exporter.export(observable, results, format)
    }

    fun saveSettings(backendUrl: String, tar1090Url: String, aisUrl: String, overlayUrl: String) {
        settings.backendBaseUrl = backendUrl
        settings.tar1090Url = tar1090Url
        settings.aisUrl = aisUrl
        settings.overlayUrl = overlayUrl
        _state.update {
            it.copy(
                backendUrl = settings.backendBaseUrl,
                tar1090Url = settings.tar1090Url,
                aisUrl = settings.aisUrl,
                overlayUrl = settings.overlayUrl
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
            GodEyeViewModel(router, repository, aircraftDataSource, aisDataSource, earthquakeDataSource, naturalEventsDataSource, overlayDataSource, weatherDataSource, firesDataSource, satellitesDataSource, newsDataSource, gdacsDataSource, volcanoDataSource, auroraDataSource, buoyDataSource, metarDataSource, milAircraftDataSource, airQualityDataSource, seismicStationDataSource, conflictDataSource, airportDataSource, powerPlantDataSource, cctvDataSource, airspaceDataSource, spaceWeatherDataSource, cableLandingDataSource, cycloneDataSource, tsunamiDataSource, radiationDataSource, airportStatusDataSource, availableLocalTools, settings, exporter) as T
    }

    private companion object {
        /** Per discovered identifier, in the second pass. Enough for a real
         *  profile of it; few enough that eleven of them stay affordable. */
        const val TOOLS_PER_PIVOT = 6
    }

}
