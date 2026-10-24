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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
            // A real Flow over the row, not a one-shot read dressed as
            // one. `flatMapLatest { flowOf(id).map { byId(it) } }` has
            // the shape of a query that watches the table and is a
            // single suspend call: it re-emitted when the id changed
            // and never again, so every control that writes to this row
            // — filing it under a case, saving its tags, its notes —
            // left the header above showing what the row used to say
            // while the list below showed what it now says.
            .flatMapLatest { id ->
                if (id == null) flowOf(null) else repository.observeById(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Writes the selected case out in [format].
     *
     * A case you cannot get out of the app is a case that stays in the
     * app. The same exporter the live dossier uses, so a report built
     * from history and one built at the time are the same document.
     */
    /**
     * An export that has finished and not yet been handed to the share
     * sheet.
     */
    data class Exported(val file: File, val format: ExportFormat, val at: Long)

    private val _exported = MutableStateFlow<Exported?>(null)
    val exported: StateFlow<Exported?> = _exported.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _exportTrouble = MutableStateFlow<String?>(null)
    val exportTrouble: StateFlow<String?> = _exportTrouble.asStateFlow()

    fun consumeExport() { _exported.value = null }

    /**
     * Dropped when the screen that asked for it has gone.
     *
     * An export that finished after the user left HISTORY stayed in
     * `_exported`, and the share sheet then appeared the next time
     * they opened the screen — which reads as the app doing something
     * on its own. The file is still in the cache and the purge takes
     * it within the day.
     */
    fun forgetUnhandledExport() {
        if (!_exporting.value) _exported.value = null
    }

    fun clearExportTrouble() { _exportTrouble.value = null }

    /**
     * Run in the view model's scope, not the screen's.
     *
     * Both export buttons used to launch into rememberCoroutineScope.
     * A rotation mid-PDF cancelled the job and reset the busy flag, and
     * nothing was said: the button came back enabled, no file appeared,
     * and the only clue was that a dossier of any size is exactly when
     * someone is most likely to turn the phone to read it.
     */
    fun exportTarget(format: ExportFormat) = runExport { export(format) }

    fun exportOpenCase(format: ExportFormat) = runExport { exportCase(format) }

    private fun runExport(work: suspend () -> File?) {
        if (_exporting.value) {
            // Said, rather than dropped. The button greys but still
            // responds, so a second tap looked like it had worked.
            _exportTrouble.value = "One export is already running — " +
                "it will offer the file when it finishes."
            return
        }
        _exporting.value = true
        viewModelScope.launch {
            val file = runCatching { work() }
            _exporting.value = false
            val made = file.getOrNull()
            when {
                made != null -> _exported.value =
                    Exported(made, lastFormat, System.currentTimeMillis())
                file.isFailure -> _exportTrouble.value =
                    "The report could not be written: " +
                        (file.exceptionOrNull()?.message ?: "unknown reason")
                else -> _exportTrouble.value =
                    "There is nothing to export here yet \u2014 no source has " +
                        "answered for this target."
            }
        }
    }

    /** Set by the two wrappers above, read when the file lands. */
    private var lastFormat: ExportFormat = ExportFormat.PDF

    suspend fun export(format: ExportFormat): File? {
        lastFormat = format
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
        // Thrown, not swallowed.
        //
        // runCatching{...}.getOrNull() here meant no exception ever
        // reached runExport, so its failure branch was dead and every
        // real failure — a full disk, a PDF page that throws, a
        // misconfigured provider path — came out as "there is nothing
        // to export here yet", which is a statement about the case
        // rather than about what went wrong.
        return exporter.export(
            router.classify(investigation.seedObservable),
            rows,
            format,
            investigation.notes,
            repository.dismissalsFor(investigation.id)
        )
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
     * The links the pass has finished, tagged with the case they were
     * worked out for.
     */
    private val computedLinks: StateFlow<Triple<Long, List<CaseLinks.Link>, Int>?> =
        _openCaseId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf<Triple<Long, List<CaseLinks.Link>, Int>?>(null)
                } else {
                    repository.observeTargets(id)
                        // Keyed on the targets' identity, not on the
                        // table. observeTargets is a Room Flow over
                        // `investigations`, and Room invalidates per
                        // table — so setNotes on an unrelated target,
                        // setTags, fileUnder, or the updatedAt touch
                        // that every tool run causes, all re-emitted
                        // here. Each re-emission re-read every result
                        // row of every target and ran the whole
                        // corroboration pass again.
                        .distinctUntilChanged { old, new ->
                            old.map { it.id } == new.map { it.id }
                        }
                        .mapLatest { targets ->
                            val byTarget = entitiesByTarget(targets)
                            Triple(
                                id,
                                CaseLinks.find(byTarget),
                                // How many of them have anything to
                                // compare. A target filed and never
                                // run has an empty dossier, and the
                                // header used to count it as one of
                                // the ones found to share nothing.
                                CaseLinks.compared(byTarget)
                            )
                        }
                        // And off the main thread. mapLatest runs in
                        // the stateIn scope, which is viewModelScope —
                        // Dispatchers.Main.immediate — so the regex
                        // extraction over every row of every target in
                        // the case ran on the thread drawing the screen
                        // that was waiting for it.
                        .flowOn(Dispatchers.Default)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What the targets of the open case have in common, or null while
     * that is still being worked out.
     *
     * The one piece of analysis nobody can do by reading the dossiers
     * one at a time, because the identifier that matters is
     * unremarkable in each of them.
     *
     * Paired with the case it was computed for, and discarded if that
     * is not the case now open. The heading swaps synchronously —
     * openCase is a plain combine over a list already in memory —
     * while this goes through flatMapLatest, mapLatest, a dispatcher
     * hop and a re-read of every result row of every target, and
     * stateIn holds the previous value throughout. So for as long as
     * the pass took, case A's shared identifiers were drawn under case
     * B's name, naming case A's targets in "shared by", with nothing
     * to tell the reader this was the wrong case rather than the
     * answer. Null is "not worked out yet", which is a different thing
     * from an empty list, and the screen says so.
     */
    val caseLinks: StateFlow<List<CaseLinks.Link>?> =
        combine(_openCaseId, computedLinks) { id, computed ->
            when {
                id == null -> emptyList()
                computed != null && computed.first == id -> computed.second
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many of the open case's targets have a dossier to compare. */
    val caseCompared: StateFlow<Int?> =
        combine(_openCaseId, computedLinks) { id, computed ->
            if (id != null && computed != null && computed.first == id) computed.third
            else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun entitiesByTarget(
        targets: List<Investigation>
    ): Map<String, List<com.osint.unified.domain.Corroboration.Entity>> =
        // Keyed by id and then pooled by observable, which is what the
        // exporter does and what this did not.
        //
        // associate keeps the last value for a repeated key, and the
        // key was the seed observable — so two investigations of the
        // same thing filed in one case (the exporter's own comments
        // say that is ordinary) left only the later one feeding the
        // corroboration pass. A link that only the earlier one
        // supported was absent from the screen and present in the
        // exported report, and the two disagreed with no way to tell
        // which was right.
        targets.map { t ->
            val rows = runCatching { repository.resultsFor(t.id) }.getOrDefault(emptyList())
            val marked = runCatching { repository.dismissalsFor(t.id) }.getOrDefault(emptyMap())
            val observable = router.classify(t.seedObservable)
            t.seedObservable to com.osint.unified.domain.Corroboration.merge(
                rows,
                t.seedObservable,
                seedIsPlace = observable is com.osint.unified.domain.Observable.Coordinate,
                dismissed = marked
            )
        }.groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten() }

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
        lastFormat = format
        val case = repository.caseById(_openCaseId.value ?: return null) ?: return null
        val targets = repository.targetsOf(case.id)
        if (targets.isEmpty()) return null
        // Not swallowed; see export() above.
        return exporter.exportCase(
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
