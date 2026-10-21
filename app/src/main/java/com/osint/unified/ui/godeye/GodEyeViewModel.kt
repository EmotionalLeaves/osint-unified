package com.osint.unified.ui.godeye

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Result as ResultRow
import java.io.File
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import kotlinx.coroutines.Job
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
    val results: List<ResultRow> = emptyList(),
    val runningToolIds: Set<String> = emptySet(),
    val backendUrl: String = "",
    val tar1090Url: String = "",
    val aisUrl: String = ""
)

class GodEyeViewModel(
    private val router: ObservableRouter,
    private val repository: InvestigationRepository,
    private val aircraftDataSource: AircraftDataSource,
    private val aisDataSource: AisDataSource,
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
        _state.update {
            it.copy(
                observable = observable,
                suggestedTools = router.suggestTools(observable),
                results = emptyList(),
                runningToolIds = emptySet()
            )
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

    private suspend fun ensureInvestigation(observable: Observable): Long {
        currentInvestigationId?.let { return it }
        val id = repository.createInvestigation(observable)
        currentInvestigationId = id
        resultsJob?.cancel()
        resultsJob = viewModelScope.launch {
            repository.observeResults(id).collect { rows ->
                _state.update { it.copy(results = rows) }
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
        private val settings: AppSettings,
        private val exporter: InvestigationExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GodEyeViewModel(router, repository, aircraftDataSource, aisDataSource, settings, exporter) as T
    }
}
