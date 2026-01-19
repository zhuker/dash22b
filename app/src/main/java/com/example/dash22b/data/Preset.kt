package com.example.dash22b.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a saved dashboard preset configuration.
 */
@Serializable
data class Preset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val gaugeConfigs: List<GaugeConfig>,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

/**
 * Configuration for a single gauge slot.
 * Note: displayUnit stored as String to avoid serialization issues with Unit enum name collision.
 */
@Serializable
data class GaugeConfig(
    val id: Int,                       // Gauge slot ID (0-10)
    val parameterName: String,         // Parameter name (e.g., "Engine Speed")
    val displayUnitName: String? = null // Optional unit override as string (null = use parameter default)
) {
    /** Gets the displayUnit as a Unit enum, or null if not set */
    fun getDisplayUnit(): com.example.dash22b.data.DisplayUnit? {
        return displayUnitName?.let {
            try {
                com.example.dash22b.data.DisplayUnit.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Represents the current state of the preset system.
 */
sealed class PresetState {
    /**
     * A saved preset is loaded and unmodified.
     */
    data class Saved(val preset: Preset) : PresetState()

    /**
     * The current configuration has been modified from a saved preset (or is new).
     */
    data class Untitled(
        val configs: List<GaugeConfig>,
        val basePresetId: String? = null  // The preset this was modified from, if any
    ) : PresetState()
}

/**
 * Default gauge configurations matching the current hardcoded setup.
 */
object DefaultPreset {
    val configs = listOf(
        GaugeConfig(0, "Engine Speed"),
        GaugeConfig(1, "Vehicle Speed"),
        GaugeConfig(2, "Boost"),
        GaugeConfig(3, "Battery Voltage"),
        GaugeConfig(4, "Throttle"),
        GaugeConfig(5, "Coolant Temp"),
        GaugeConfig(6, "Ignition Timing"),
        GaugeConfig(7, "Knock Correction"),
        GaugeConfig(8, "Intake Air Temp"),
        GaugeConfig(9, "MAP"),
        GaugeConfig(10, "Mass Airflow")
    )

    fun create(): Preset = Preset(
        id = "default",
        name = "Default View",
        gaugeConfigs = configs
    )
}
