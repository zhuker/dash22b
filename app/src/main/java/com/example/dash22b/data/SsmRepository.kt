package com.example.dash22b.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for SSM ECU data.
 * The service writes data here, the UI observes it.
 */
class SsmRepository {
    private val _engineData = MutableStateFlow(EngineData())
    val engineData: StateFlow<EngineData> = _engineData.asStateFlow()

    private val _history = MutableStateFlow(EngineDataHistory())
    val history: StateFlow<EngineDataHistory> = _history.asStateFlow()

    private val _subscribedParams = MutableStateFlow<Set<String>>(emptySet())
    val subscribedParams: StateFlow<Set<String>> = _subscribedParams.asStateFlow()

    fun updateEngineData(data: EngineData) {
        _engineData.value = data
        _history.value = _history.value.append(data)
    }

    fun subscribeToParameters(params: Set<String>) {
        _subscribedParams.value = params
    }
}
