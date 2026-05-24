package com.example.dash22b.data

/**
 * Per-parameter sensor calibrations that don't fit the generic unit conversions
 * in [UnitConverter]. These are tied to a specific physical sensor on a specific
 * vehicle, so they live keyed by parameter name rather than by source unit alone.
 *
 * Current entries:
 * - "Fuel Level" on a 2001 Subaru Impreza 2.5RS (GC8): cubic fit from
 *   /Users/zhukov/Documents/22b/fuel_sensor_calibration.md
 */
object ParameterCalibration {

    private const val FUEL_LEVEL_PARAM = "Fuel Level"
    private const val FUEL_TANK_CAPACITY_GAL = 15.9f

    private fun voltageToGallonsToFill(v: Float): Float {
        val raw = 0.238f * v * v * v - 0.891f * v * v + 3.004f * v - 0.401f
        return raw.coerceIn(0f, FUEL_TANK_CAPACITY_GAL)
    }

    private fun voltageToGallonsRemaining(v: Float): Float =
        FUEL_TANK_CAPACITY_GAL - voltageToGallonsToFill(v)

    private fun voltageToPercentFuel(v: Float): Float =
        100f * voltageToGallonsRemaining(v) / FUEL_TANK_CAPACITY_GAL

    /**
     * Convert a sensor reading using a parameter-specific calibration.
     * Returns null if no calibration applies — caller should fall back to [UnitConverter].
     */
    fun convert(paramName: String?, value: Float, from: DisplayUnit, to: DisplayUnit): Float? {
        if (paramName != FUEL_LEVEL_PARAM || from != DisplayUnit.VOLTS) return null
        return when (to) {
            DisplayUnit.GALLONS -> voltageToGallonsRemaining(value)
            DisplayUnit.GALLONS_TO_FILL -> voltageToGallonsToFill(value)
            DisplayUnit.PERCENT -> voltageToPercentFuel(value)
            else -> null
        }
    }

    /**
     * Extra display units this parameter supports beyond its native unit.
     * Used by the bottom sheet to gate which units appear in the dropdown.
     */
    fun getExtraUnits(paramName: String?, baseUnit: DisplayUnit): List<DisplayUnit> {
        if (paramName == FUEL_LEVEL_PARAM && baseUnit == DisplayUnit.VOLTS) {
            return listOf(DisplayUnit.GALLONS, DisplayUnit.GALLONS_TO_FILL, DisplayUnit.PERCENT)
        }
        return emptyList()
    }

    /**
     * Display range for a calibrated parameter at a given target unit.
     * Returns null to defer to [ParameterRegistry]'s default range logic.
     */
    fun getRange(paramName: String?, targetUnit: DisplayUnit): Pair<Float, Float>? {
        if (paramName != FUEL_LEVEL_PARAM) return null
        return when (targetUnit) {
            DisplayUnit.VOLTS -> 0.3f to 4.3f
            DisplayUnit.GALLONS -> 0f to FUEL_TANK_CAPACITY_GAL
            DisplayUnit.GALLONS_TO_FILL -> 0f to FUEL_TANK_CAPACITY_GAL
            DisplayUnit.PERCENT -> 0f to 100f
            else -> null
        }
    }
}
