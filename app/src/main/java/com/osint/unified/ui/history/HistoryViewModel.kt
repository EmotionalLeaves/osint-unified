package com.osint.unified.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.Investigation
import com.osint.unified.data.db.Result as ResultRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: InvestigationRepository
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

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteInvestigation(id)
            if (_selectedId.value == id) _selectedId.value = null
        }
    }

    class Factory(private val repository: InvestigationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository) as T
    }
}
