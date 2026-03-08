package com.example.dash22b.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TpmsRepository {
    private val _tpmsState = MutableStateFlow<Map<String, TpmsState>>(
        mapOf(
            "FL" to TpmsState(),
            "FR" to TpmsState(),
            "RL" to TpmsState(),
            "RR" to TpmsState()
        )
    )
    val tpmsState: StateFlow<Map<String, TpmsState>> = _tpmsState.asStateFlow()

    fun updateState(newState: Map<String, TpmsState>) {
        _tpmsState.value = newState
    }
}
