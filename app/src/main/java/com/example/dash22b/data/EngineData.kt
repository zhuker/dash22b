package com.example.dash22b.data

data class ValueWithUnit(
    val value: Float,
    val unit: DisplayUnit
) {
    fun to(toUnit: DisplayUnit): ValueWithUnit {
        return ValueWithUnit(UnitConverter.convert(value, unit, toUnit), toUnit)
    }
}

data class EngineData(
    // Common Dashboard Fields
    val timestamp: Long = 0L, // Epoch millis

    // Explicit fields now use ValueWithUnit
    // Primitives like Int are wrapped too if they have units (RPM, Speed)
    // Though RPM is arguably dimensionless or unit is "rpm".
    val rpm: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.RPM),
    val boost: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.BAR),
    val batteryVoltage: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.VOLTS),
    val pulseWidth: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.MILLISECONDS),
    val coolantTemp: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.C),
    val sparkLines: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.DEGREES),
    val dutyCycle: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.PERCENT),
    val speed: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.KMH),
    val iat: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.C),
    val afr: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.AFR),
    val maf: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.GRAMS_PER_SEC),

    // Extended Log Fields
    val afCorrection: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.PERCENT),
    val afLearning: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.PERCENT),
    val afSens1Curr: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.MILLIAMPS),
    val afSens3Volts: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.VOLTS),
    val calculatedLoad: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.GRAMS_PER_REV),
    val commFuelFinal: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.AFR),
    val dam: ValueWithUnit = ValueWithUnit(1f, DisplayUnit.DAM),
    val feedbackKnock: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.DEGREES),
    val fineKnockLearn: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.DEGREES),
    val mafVolts: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.VOLTS),
    val tdBoostError: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.UNKNOWN),
    val throttlePos: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.PERCENT),
    val wastegateDuty: ValueWithUnit = ValueWithUnit(0f, DisplayUnit.PERCENT),

    // Generic Map
    val values: Map<String, ValueWithUnit> = emptyMap(),

    // History (storing raw values for now, assuming unit matches the main field)
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
