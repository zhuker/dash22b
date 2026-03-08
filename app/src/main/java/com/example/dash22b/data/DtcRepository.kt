package com.example.dash22b.data

import com.example.dash22b.obd.SsmDtcCode
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val dtcCodes: List<SsmDtcCode> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ServiceRequest {
    object ReadDtc : ServiceRequest()
    object ClearCodes : ServiceRequest()
}

class DtcRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _milActive = MutableStateFlow(false)
    val milActive: StateFlow<Boolean> = _milActive.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Channel buffers the request until the service collects it
    private val _serviceRequests = Channel<ServiceRequest>(Channel.BUFFERED)
    val serviceRequests = _serviceRequests.receiveAsFlow()

    fun addUserMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(text = text, isFromUser = true)
    }

    fun addCarMessage(text: String, dtcCodes: List<SsmDtcCode> = emptyList()) {
        _messages.value = _messages.value + ChatMessage(text = text, isFromUser = false, dtcCodes = dtcCodes)
    }

    fun requestDtcRead() {
        _isLoading.value = true
        Timber.i("DtcRepository: requestDtcRead called, trySend result=${_serviceRequests.trySend(ServiceRequest.ReadDtc)}")
    }

    fun requestClearCodes() {
        _isLoading.value = true
        Timber.i("DtcRepository: requestClearCodes called, trySend result=${_serviceRequests.trySend(ServiceRequest.ClearCodes)}")
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun updateMilStatus(active: Boolean) {
        _milActive.value = active
    }
}
