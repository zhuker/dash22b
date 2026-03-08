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

        // TPMS Data
        val tpms: Map<String, TpmsState> = emptyMap()
)

data class EngineDataHistory(
        val snapshots: List<EngineData> = emptyList(),
        val maxSize: Int = 50
) {
    fun append(data: EngineData): EngineDataHistory {
        return copy(snapshots = (snapshots + data).takeLast(maxSize))
    }

    fun getHistory(fieldName: String): List<Float> {
        return snapshots.mapNotNull { it.values[fieldName]?.value }
    }
}

data class TpmsState(
        val pressure: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.BAR),
        val temp: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.C),
        val batteryLow: Boolean = false,
        val leaking: Boolean = false,
        val timestamp: Long = 0L,
        val isStale: Boolean = true,
        val rssi: Int = 0
)
