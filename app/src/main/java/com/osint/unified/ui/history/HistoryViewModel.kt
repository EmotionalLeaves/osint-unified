package com.osint.unified.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Investigation
import com.osint.unified.domain.ObservableRouter
import java.io.File
import com.osint.unified.data.db.Result as ResultRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: InvestigationRepository,
    private val router: ObservableRouter,
    private val exporter: InvestigationExporter
) : ViewModel() {

    val investigations: StateFlow<List<Investigation>> =
        repository.observeInvestigations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    val results: StateFlow<List<ResultRow>> =
        _selectedId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeResults(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(id: Long?) { _selectedId.value = id }

    /** The case being looked at, for its notes and its heading. */
    val selected: StateFlow<Investigation?> =
        _selectedId
            .flatMapLatest { id ->
                flowOf(id).map { wanted ->
                    if (wanted == null) null
                    else repository.byId(wanted)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Writes the selected case out in [format].
     *
     * A case you cannot get out of the app is a case that stays in the
     * app. The same exporter the live dossier uses, so a report built
     * from history and one built at the time are the same document.
     */
    suspend fun export(format: ExportFormat): File? {
        val investigation = repository.byId(_selectedId.value ?: return null) ?: return null
        val rows = results.value
        if (rows.isEmpty()) return null
        return runCatching {
            exporter.export(
                router.classify(investigation.seedObservable),
                rows,
                format,
                investigation.notes
            )
        }.getOrNull()
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteInvestigation(id)
            if (_selectedId.value == id) _selectedId.value = null
        }
    }

    class Factory(
        private val repository: InvestigationRepository,
        private val router: ObservableRouter,
        private val exporter: InvestigationExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository, router, exporter) as T
    }
}
