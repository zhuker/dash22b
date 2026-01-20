package com.example.dash22b.data

data class ValueWithUnit(val value: Float, val unit: DisplayUnit) {
    fun to(toUnit: DisplayUnit): ValueWithUnit {
        return ValueWithUnit(UnitConverter.convert(value, unit, toUnit), toUnit)
    }
}

data class EngineData(
        val timestamp: Long = 0L, // Epoch millis

        // Generic Map for all diagnostic values
        val values: Map<String, ValueWithUnit> = emptyMap(),

        // History (storing raw values for now)
        val rpmHistory: List<Float> = emptyList(),
        val boostHistory: List<Float> = emptyList(),

        // TPMS Data
        val tpms: Map<String, TpmsState> = emptyMap()
)

data class TpmsState(
        val pressure: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.BAR),
        val temp: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.C),
        val batteryLow: Boolean = false,
        val leaking: Boolean = false,
        val timestamp: Long = 0L,
        val isStale: Boolean = true,
        val rssi: Int = 0
)
