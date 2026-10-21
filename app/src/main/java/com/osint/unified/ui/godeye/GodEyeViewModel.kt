package com.osint.unified.ui.godeye

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class GodEyeUiState(
    val query: String = "",
    val observable: Observable? = null,
    val suggestedTools: List<Tool> = emptyList(),
    val aircraft: List<Aircraft> = emptyList()
)

/**
 * Drives the God's Eye View: live aircraft stream (mock in Phase 1) and
 * observable classification via [ObservableRouter].
 *
 * Phase 2 will replace [generateMockAircraft] with a real tar1090 feed:
 * GET http://<host>:30000/data/aircraft.json parsed into [Aircraft].
 */
class GodEyeViewModel(
    private val router: ObservableRouter
) : ViewModel() {

    private val _state = MutableStateFlow(GodEyeUiState())
    val state: StateFlow<GodEyeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(aircraft = generateMockAircraft()) }
                delay(5_000)
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun onSearch() {
        val input = _state.value.query
        val observable = router.classify(input)
        _state.update {
            it.copy(
                observable = observable,
                suggestedTools = router.suggestTools(observable)
            )
        }
    }

    /** Placeholder data so the globe is populated before the live feed lands. */
    private fun generateMockAircraft(): List<Aircraft> {
        val hubs = listOf(
            Triple("KLAX", 33.94, -118.40),
            Triple("KJFK", 40.64, -73.78),
            Triple("EGLL", 51.47, -0.45),
            Triple("RJTT", 35.55, 139.78),
            Triple("OMDB", 25.25, 55.36)
        )
        return hubs.flatMap { (code, lat, lon) ->
            List(6) { i ->
                Aircraft(
                    icao = "%06X".format(Random.nextInt(0xFFFFFF)),
                    callsign = "$code${Random.nextInt(100, 999)}",
                    lat = lat + Random.nextDouble(-4.0, 4.0),
                    lon = lon + Random.nextDouble(-4.0, 4.0),
                    altitude = Random.nextDouble(2_000.0, 39_000.0),
                    track = Random.nextDouble(0.0, 360.0),
                    speed = Random.nextDouble(180.0, 540.0)
                )
            }
        }
    }

    class Factory(private val router: ObservableRouter) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GodEyeViewModel(router) as T
    }
}
