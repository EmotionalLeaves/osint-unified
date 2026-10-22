package com.osint.unified.ui.godeye

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.EarthquakeDataSource
import com.osint.unified.data.NaturalEventsDataSource
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
import kotlinx.coroutines.Job
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
    val findings: List<FindingPin> = emptyList(),
    val mapFocus: Pair<Double, Double>? = null,
    val results: List<ResultRow> = emptyList(),
    val runningToolIds: Set<String> = emptySet(),
    val workflowName: String? = null,
    val workflowRunning: Boolean = false,
    val navSignal: Long = 0,
    val backendUrl: String = "",
    val tar1090Url: String = "",
    val aisUrl: String = ""
)

class GodEyeViewModel(
    private val router: ObservableRouter,
    private val repository: InvestigationRepository,
    private val aircraftDataSource: AircraftDataSource,
    private val aisDataSource: AisDataSource,
    private val earthquakeDataSource: EarthquakeDataSource,
    private val naturalEventsDataSource: NaturalEventsDataSource,
    private val settings: AppSettings,
    private val exporter: InvestigationExporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        GodEyeUiState(
            backendUrl = settings.backendBaseUrl,
            tar1090Url = settings.tar1090Url,
            aisUrl = settings.aisUrl
        )
    )
    val state: StateFlow<GodEyeUiState> = _state.asStateFlow()

    private var currentInvestigationId: Long? = null
    private var resultsJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(aircraft = aircraftDataSource.fetch()) }
                delay(5_000)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(vessels = aisDataSource.fetch()) }
                delay(10_000)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(quakes = earthquakeDataSource.fetch()) }
                delay(300_000)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(naturalEvents = naturalEventsDataSource.fetch()) }
                delay(600_000)
            }
        }
    }

    fun onMapClick(lat: Double, lon: Double) {
        onQueryChange("$lat, $lon")
        onSearch()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun onSearch() {
        val observable = router.classify(_state.value.query)
        // New search = fresh investigation context.
        currentInvestigationId = null
        resultsJob?.cancel()
        // A coordinate search focuses the globe immediately; other types get a
        // focus once a tool returns a location (see the results collector).
        val focus = (observable as? Observable.Coordinate)?.let { it.lat to it.lon }
        _state.update {
            it.copy(
                observable = observable,
                suggestedTools = router.suggestTools(observable),
                workflowName = Workflows.forObservable(observable)?.name,
                results = emptyList(),
                findings = emptyList(),
                mapFocus = focus,
                runningToolIds = emptySet(),
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
        runToolsInParallel(tools)
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

    /** Runs every suggested tool for the current observable, in parallel. */
    fun onRunAll() {
        runToolsInParallel(_state.value.suggestedTools)
    }

    /** Runs the given tools concurrently so findings appear as fast as each
     *  tool returns, rather than trickling in one at a time. */
    private fun runToolsInParallel(tools: List<Tool>) {
        val observable = _state.value.observable ?: return
        if (tools.isEmpty()) return
        viewModelScope.launch {
            val investigationId = ensureInvestigation(observable)
            _state.update { it.copy(runningToolIds = it.runningToolIds + tools.map { t -> t.id }) }
            coroutineScope {
                tools.forEach { tool ->
                    launch {
                        try {
                            repository.runTool(investigationId, tool, observable.raw)
                        } finally {
                            _state.update { it.copy(runningToolIds = it.runningToolIds - tool.id) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureInvestigation(observable: Observable): Long {
        currentInvestigationId?.let { return it }
        val id = repository.createInvestigation(observable)
        currentInvestigationId = id
        resultsJob?.cancel()
        resultsJob = viewModelScope.launch {
            repository.observeResults(id).collect { rows ->
                // Plot any result that carries a location, and focus the globe
                // on the first one if the search didn't already set a focus.
                val findings = rows.mapNotNull { r ->
                    if (r.status != "success") return@mapNotNull null
                    val c = GeoExtractor.firstCoordinate(r.rawOutput) ?: return@mapNotNull null
                    FindingPin(c.first, c.second, r.toolName)
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

    fun saveSettings(backendUrl: String, tar1090Url: String, aisUrl: String) {
        settings.backendBaseUrl = backendUrl
        settings.tar1090Url = tar1090Url
        settings.aisUrl = aisUrl
        _state.update {
            it.copy(
                backendUrl = settings.backendBaseUrl,
                tar1090Url = settings.tar1090Url,
                aisUrl = settings.aisUrl
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
        private val settings: AppSettings,
        private val exporter: InvestigationExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GodEyeViewModel(router, repository, aircraftDataSource, aisDataSource, earthquakeDataSource, naturalEventsDataSource, settings, exporter) as T
    }
}
