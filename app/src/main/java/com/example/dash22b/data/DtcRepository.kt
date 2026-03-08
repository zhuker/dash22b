package com.example.dash22b.data

import com.example.dash22b.obd.SsmDtcCode
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class DtcState {
    object Idle : DtcState()
    object Loading : DtcState()
    data class Loaded(val codes: List<SsmDtcCode>) : DtcState()
    data class Error(val message: String) : DtcState()
}

class DtcRepository {
    private val _dtcState = MutableStateFlow<DtcState>(DtcState.Idle)
    val dtcState: StateFlow<DtcState> = _dtcState.asStateFlow()

    private val _milActive = MutableStateFlow(false)
    val milActive: StateFlow<Boolean> = _milActive.asStateFlow()

    // Channel buffers the request until the service collects it
    private val _readRequests = Channel<Unit>(Channel.BUFFERED)
    val readRequests = _readRequests.receiveAsFlow()

    fun requestDtcRead() {
        Timber.i("DtcRepository: requestDtcRead called, trySend result=${_readRequests.trySend(Unit)}")
        _dtcState.value = DtcState.Loading
    }

    fun updateDtcState(state: DtcState) {
        _dtcState.value = state
    }

    fun updateMilStatus(active: Boolean) {
        _milActive.value = active
    }

    fun resetToIdle() {
        _dtcState.value = DtcState.Idle
    }
}
