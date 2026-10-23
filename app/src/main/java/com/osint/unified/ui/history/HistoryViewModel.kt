package com.osint.unified.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.ExportFormat
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Case
import com.osint.unified.data.db.Investigation
import com.osint.unified.domain.CaseLinks
import com.osint.unified.domain.ObservableRouter
import java.io.File
import com.osint.unified.data.db.Result as ResultRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
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
        // Read for this case, not taken from the flow.
        //
        // The selected id changes the instant it is set; the flow
        // behind `results` only catches up when Room re-emits. Export
        // in that window — which is what tapping a case and then
        // export immediately does — and the previous case's rows,
        // hashes and timestamps were written out under this case's
        // name, observable and notes.
        val rows = repository.resultsFor(investigation.id)
        if (rows.isEmpty()) return null
        return runCatching {
            exporter.export(
                router.classify(investigation.seedObservable),
                rows,
                format,
                investigation.notes,
                repository.dismissalsFor(investigation.id)
            )
        }.getOrNull()
    }

    // ---- Cases: several targets held together as one enquiry ----

    val cases: StateFlow<List<Case>> = repository.observeCases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Case id to how many targets are filed under it. */
    val caseCounts: StateFlow<Map<Long, Int>> = repository.observeCaseCounts()
        .map { rows -> rows.associate { it.caseId to it.targets } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _openCaseId = MutableStateFlow<Long?>(null)
    val openCaseId: StateFlow<Long?> = _openCaseId.asStateFlow()

    val openCase: StateFlow<Case?> =
        combine(_openCaseId, cases) { id, all -> all.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val caseTargets: StateFlow<List<Investigation>> =
        _openCaseId
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else repository.observeTargets(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What the targets of the open case have in common.
     *
     * The one piece of analysis nobody can do by reading the dossiers
     * one at a time, because the identifier that matters is
     * unremarkable in each of them.
     */
    val caseLinks: StateFlow<List<CaseLinks.Link>> =
        caseTargets
            .mapLatest { targets -> CaseLinks.find(entitiesByTarget(targets)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun entitiesByTarget(
        targets: List<Investigation>
    ): Map<String, List<com.osint.unified.domain.Corroboration.Entity>> =
        targets.associate { t ->
            val rows = runCatching { repository.resultsFor(t.id) }.getOrDefault(emptyList())
            val marked = runCatching { repository.dismissalsFor(t.id) }.getOrDefault(emptyMap())
            val observable = router.classify(t.seedObservable)
            t.seedObservable to com.osint.unified.domain.Corroboration.merge(
                rows,
                t.seedObservable,
                seedIsPlace = observable is com.osint.unified.domain.Observable.Coordinate,
                dismissed = marked
            )
        }

    fun openCase(id: Long?) { _openCaseId.value = id }

    fun newCase(name: String, withInvestigation: Long? = null) {
        viewModelScope.launch {
            val id = repository.createCase(name)
            if (withInvestigation != null) repository.fileUnder(withInvestigation, id)
        }
    }

    fun renameCase(id: Long, name: String) {
        viewModelScope.launch { repository.renameCase(id, name) }
    }

    fun setCaseSummary(id: Long, summary: String) {
        viewModelScope.launch { repository.setCaseSummary(id, summary) }
    }

    fun fileUnder(investigationId: Long, caseId: Long?) {
        viewModelScope.launch { repository.fileUnder(investigationId, caseId) }
    }

    fun setTags(investigationId: Long, tags: String) {
        viewModelScope.launch { repository.setTags(investigationId, tags) }
    }

    fun deleteCase(id: Long) {
        viewModelScope.launch {
            repository.deleteCase(id)
            if (_openCaseId.value == id) _openCaseId.value = null
        }
    }

    /**
     * The whole case as one report.
     *
     * Exporting target by target hands the reader the piece of analysis
     * the app can do and they cannot — which identifier turns up under
     * more than one of them — so this puts that first and the dossiers
     * after it.
     */
    suspend fun exportCase(format: ExportFormat): File? {
        val case = repository.caseById(_openCaseId.value ?: return null) ?: return null
        val targets = repository.targetsOf(case.id)
        if (targets.isEmpty()) return null
        return runCatching {
            exporter.exportCase(
                caseName = case.name,
                summary = case.summary,
                targets = targets.map { t ->
                    InvestigationExporter.CaseTarget(
                        id = t.id,
                        name = t.name,
                        type = t.observableType,
                        observable = router.classify(t.seedObservable),
                        results = repository.resultsFor(t.id),
                        notes = t.notes,
                        tags = CaseLinks.split(t.tags),
                        dismissals = repository.dismissalsFor(t.id)
                    )
                },
                format = format
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
