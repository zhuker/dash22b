package com.example.dash22b.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Manages preset state and coordinates between UI and persistence.
 * Provides reactive state flows for UI observation and parameter subscription.
 */
class PresetManager(private val repository: PresetRepository) {

    companion object {
        private const val TAG = "PresetManager"
        const val GAUGE_DISABLED_PARAM = "None"
    }

    // Current preset state (Saved or Untitled)
    private val _presetState = MutableStateFlow<PresetState>(PresetState.Untitled(DefaultPreset.configs))
    val presetState: StateFlow<PresetState> = _presetState.asStateFlow()

    // All available presets
    private val _allPresets = MutableStateFlow<List<Preset>>(emptyList())
    val allPresets: StateFlow<List<Preset>> = _allPresets.asStateFlow()

    // Current gauge configs (derived from presetState for convenience)
    private val _currentConfigs = MutableStateFlow<List<GaugeConfig>>(DefaultPreset.configs)
    val currentConfigs: StateFlow<List<GaugeConfig>> = _currentConfigs.asStateFlow()

    // Set of parameter names currently needed (for SsmDataSource subscription)
    private val _subscribedParameters = MutableStateFlow<Set<String>>(emptySet())
    val subscribedParameters: StateFlow<Set<String>> = _subscribedParameters.asStateFlow()

    init {
        initialize()
    }

    /**
     * Initializes the manager by loading state from the repository.
     */
    private fun initialize() {
        // Load all presets
        _allPresets.value = repository.getAllPresets()

        // Restore previous state
        val savedConfigs = repository.getCurrentConfigs()
        val lastActivePresetId = repository.getLastActivePresetId()

        when {
            // If there are saved "dirty" configs, restore as Untitled
            savedConfigs != null -> {
                Timber.tag(TAG).d("Restoring Untitled state with ${savedConfigs.size} configs")
                _presetState.value = PresetState.Untitled(savedConfigs, lastActivePresetId)
                _currentConfigs.value = savedConfigs
            }
            // If there's a last active preset, load it
            lastActivePresetId != null -> {
                val preset = repository.getPreset(lastActivePresetId)
                if (preset != null) {
                    Timber.tag(TAG).d("Restoring preset: ${preset.name}")
                    _presetState.value = PresetState.Saved(preset)
                    _currentConfigs.value = preset.gaugeConfigs
                } else {
                    loadDefaultState()
                }
            }
            // Otherwise, use default
            else -> {
                loadDefaultState()
            }
        }

        updateSubscribedParameters()
    }

    private fun loadDefaultState() {
        val presets = _allPresets.value
        val defaultPreset = presets.firstOrNull() ?: DefaultPreset.create()
        _presetState.value = PresetState.Saved(defaultPreset)
        _currentConfigs.value = defaultPreset.gaugeConfigs
        repository.setLastActivePresetId(defaultPreset.id)
    }

    /**
     * Loads a preset by ID.
     */
    fun loadPreset(id: String) {
        val preset = repository.getPreset(id) ?: run {
            Timber.tag(TAG).w("Preset not found: $id")
            return
        }

        _presetState.value = PresetState.Saved(preset)
        _currentConfigs.value = preset.gaugeConfigs
        repository.setLastActivePresetId(id)
        repository.clearCurrentConfigs()
        updateSubscribedParameters()

        Timber.tag(TAG).d("Loaded preset: ${preset.name}")
    }

    /**
     * Updates a gauge configuration. Transitions to Untitled state if currently Saved.
     */
    fun updateGaugeConfig(gaugeId: Int, parameterName: String, displayUnitName: String? = null) {
        val newConfigs = _currentConfigs.value.map { config ->
            if (config.id == gaugeId) {
                config.copy(parameterName = parameterName, displayUnitName = displayUnitName)
            } else {
                config
            }
        }

        _currentConfigs.value = newConfigs

        // Transition to Untitled if we were in Saved state
        val currentState = _presetState.value
        val basePresetId = when (currentState) {
            is PresetState.Saved -> currentState.preset.id
            is PresetState.Untitled -> currentState.basePresetId
        }

        _presetState.value = PresetState.Untitled(newConfigs, basePresetId)
        repository.saveCurrentConfigs(newConfigs)
        updateSubscribedParameters()

        Timber.tag(TAG).d("Updated gauge $gaugeId to $parameterName, state is now Untitled")
    }

    /**
     * Saves current configuration as a new preset.
     * @return The newly created preset
     */
    fun saveCurrentAsPreset(name: String): Preset {
        val preset = Preset(
            name = name,
            gaugeConfigs = _currentConfigs.value
        )

        repository.savePreset(preset)
        repository.setLastActivePresetId(preset.id)
        repository.clearCurrentConfigs()

        _presetState.value = PresetState.Saved(preset)
        _allPresets.value = repository.getAllPresets()

        Timber.tag(TAG).d("Saved new preset: $name")
        return preset
    }

    /**
     * Renames a preset.
     */
    fun renamePreset(id: String, newName: String) {
        repository.renamePreset(id, newName)
        _allPresets.value = repository.getAllPresets()

        // Update current state if this preset is active
        val currentState = _presetState.value
        if (currentState is PresetState.Saved && currentState.preset.id == id) {
            val updatedPreset = repository.getPreset(id)
            if (updatedPreset != null) {
                _presetState.value = PresetState.Saved(updatedPreset)
            }
        }

        Timber.tag(TAG).d("Renamed preset $id to $newName")
    }

    /**
     * Deletes a preset.
     * @return true if deleted successfully
     */
    fun deletePreset(id: String): Boolean {
        val deleted = repository.deletePreset(id)
        if (deleted) {
            _allPresets.value = repository.getAllPresets()

            // If the deleted preset was active, load the first available preset
            val currentState = _presetState.value
            val wasActive = when (currentState) {
                is PresetState.Saved -> currentState.preset.id == id
                is PresetState.Untitled -> currentState.basePresetId == id
            }

            if (wasActive) {
                val firstPreset = _allPresets.value.firstOrNull()
                if (firstPreset != null) {
                    loadPreset(firstPreset.id)
                }
            }

            Timber.tag(TAG).d("Deleted preset: $id")
        }
        return deleted
    }

    /**
     * Reverts to the base preset (discards Untitled changes).
     */
    fun revertToBasePreset() {
        val currentState = _presetState.value
        if (currentState is PresetState.Untitled) {
            val baseId = currentState.basePresetId
            if (baseId != null) {
                loadPreset(baseId)
            } else {
                // No base preset, load first available
                val firstPreset = _allPresets.value.firstOrNull()
                if (firstPreset != null) {
                    loadPreset(firstPreset.id)
                }
            }
        }
    }

    /**
     * Updates the set of subscribed parameters based on current configs.
     * Filters out disabled gauges (parameterName == "None").
     */
    private fun updateSubscribedParameters() {
        val params = _currentConfigs.value
            .map { it.parameterName }
            .filter { it != GAUGE_DISABLED_PARAM }
            .toSet()
        _subscribedParameters.value = params
        Timber.tag(TAG).d("Subscribed parameters (${params.size}): $params")
    }

    /**
     * Returns the display name for the current state.
     */
    fun getCurrentDisplayName(): String {
        return when (val state = _presetState.value) {
            is PresetState.Saved -> state.preset.name
            is PresetState.Untitled -> "Untitled"
        }
    }

    /**
     * Returns whether the current state is Untitled (modified).
     */
    fun isUntitled(): Boolean {
        return _presetState.value is PresetState.Untitled
    }
}
