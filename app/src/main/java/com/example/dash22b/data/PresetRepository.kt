package com.example.dash22b.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Repository for persisting dashboard presets using SharedPreferences.
 */
class PresetRepository(context: Context) {

    companion object {
        private const val TAG = "PresetRepository"
        private const val PREFS_NAME = "dashboard_presets"
        private const val KEY_PRESETS = "presets"
        private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
        private const val KEY_CURRENT_CONFIGS = "current_configs"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Returns all saved presets, sorted by modification time (newest first).
     * Creates default preset on first run.
     */
    fun getAllPresets(): List<Preset> {
        val presetsJson = prefs.getString(KEY_PRESETS, null)
        if (presetsJson == null) {
            // First run - create default preset
            val defaultPreset = DefaultPreset.create()
            savePresetInternal(listOf(defaultPreset))
            return listOf(defaultPreset)
        }

        return try {
            json.decodeFromString<List<Preset>>(presetsJson)
                .sortedByDescending { it.modifiedAt }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse presets")
            emptyList()
        }
    }

    /**
     * Saves or updates a preset.
     */
    fun savePreset(preset: Preset) {
        val presets = getAllPresets().toMutableList()
        val existingIndex = presets.indexOfFirst { it.id == preset.id }

        if (existingIndex >= 0) {
            presets[existingIndex] = preset.copy(modifiedAt = System.currentTimeMillis())
        } else {
            presets.add(preset)
        }

        savePresetInternal(presets)
        Timber.tag(TAG).d("Saved preset: ${preset.name}")
    }

    /**
     * Internal method to save presets list directly without fetching.
     */
    private fun savePresetInternal(presets: List<Preset>) {
        val presetsJson = json.encodeToString(presets)
        prefs.edit().putString(KEY_PRESETS, presetsJson).apply()
    }

    /**
     * Deletes a preset by ID. Cannot delete the last preset.
     * @return true if deleted, false if not found or is last preset
     */
    fun deletePreset(id: String): Boolean {
        val presets = getAllPresets().toMutableList()
        if (presets.size <= 1) {
            Timber.tag(TAG).w("Cannot delete last preset")
            return false
        }

        val removed = presets.removeAll { it.id == id }
        if (removed) {
            val presetsJson = json.encodeToString(presets)
            prefs.edit().putString(KEY_PRESETS, presetsJson).apply()
            Timber.tag(TAG).d("Deleted preset: $id")

            // If active preset was deleted, clear it
            if (getLastActivePresetId() == id) {
                setLastActivePresetId(null)
            }
        }
        return removed
    }

    /**
     * Renames a preset.
     */
    fun renamePreset(id: String, newName: String) {
        val presets = getAllPresets()
        val preset = presets.find { it.id == id } ?: return
        savePreset(preset.copy(name = newName))
    }

    /**
     * Gets a preset by ID.
     */
    fun getPreset(id: String): Preset? {
        return getAllPresets().find { it.id == id }
    }

    /**
     * Returns the ID of the last active preset, or null if none.
     */
    fun getLastActivePresetId(): String? {
        return prefs.getString(KEY_ACTIVE_PRESET_ID, null)
    }

    /**
     * Sets the last active preset ID.
     */
    fun setLastActivePresetId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_ID, id).apply()
    }

    /**
     * Saves the current gauge configurations (for crash recovery / "Untitled" state).
     */
    fun saveCurrentConfigs(configs: List<GaugeConfig>) {
        val configsJson = json.encodeToString(configs)
        prefs.edit().putString(KEY_CURRENT_CONFIGS, configsJson).apply()
    }

    /**
     * Gets the saved current configs, or null if none saved.
     */
    fun getCurrentConfigs(): List<GaugeConfig>? {
        val configsJson = prefs.getString(KEY_CURRENT_CONFIGS, null) ?: return null
        return try {
            json.decodeFromString(configsJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse current configs")
            null
        }
    }

    /**
     * Clears the saved current configs (called when a preset is loaded).
     */
    fun clearCurrentConfigs() {
        prefs.edit().remove(KEY_CURRENT_CONFIGS).apply()
    }
}
