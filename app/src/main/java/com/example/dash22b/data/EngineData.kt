package com.example.dash22b.data

data class ValueWithUnit(
    val value: Float,
    val unit: Unit
) {
    fun to(toUnit: Unit): ValueWithUnit {
        return ValueWithUnit(UnitConverter.convert(value, unit, toUnit), toUnit)
    }
}

data class EngineData(
    // Common Dashboard Fields
    val timestamp: Long = 0L, // Epoch millis

    // Explicit fields now use ValueWithUnit
    // Primitives like Int are wrapped too if they have units (RPM, Speed)
    // Though RPM is arguably dimensionless or unit is "rpm".
    val rpm: ValueWithUnit = ValueWithUnit(0f, Unit.RPM),
    val boost: ValueWithUnit = ValueWithUnit(0f, Unit.BAR),
    val batteryVoltage: ValueWithUnit = ValueWithUnit(0f, Unit.VOLTS),
    val pulseWidth: ValueWithUnit = ValueWithUnit(0f, Unit.MILLISECONDS),
    val coolantTemp: ValueWithUnit = ValueWithUnit(0f, Unit.C),
    val sparkLines: ValueWithUnit = ValueWithUnit(0f, Unit.DEGREES),
    val dutyCycle: ValueWithUnit = ValueWithUnit(0f, Unit.PERCENT),
    val speed: ValueWithUnit = ValueWithUnit(0f, Unit.KMH),
    val iat: ValueWithUnit = ValueWithUnit(0f, Unit.C),
    val afr: ValueWithUnit = ValueWithUnit(0f, Unit.AFR),
    val maf: ValueWithUnit = ValueWithUnit(0f, Unit.GRAMS_PER_SEC),

    // Extended Log Fields
    val afCorrection: ValueWithUnit = ValueWithUnit(0f, Unit.PERCENT),
    val afLearning: ValueWithUnit = ValueWithUnit(0f, Unit.PERCENT),
    val afSens1Curr: ValueWithUnit = ValueWithUnit(0f, Unit.MILLIAMPS),
    val afSens3Volts: ValueWithUnit = ValueWithUnit(0f, Unit.VOLTS),
    val calculatedLoad: ValueWithUnit = ValueWithUnit(0f, Unit.GRAMS_PER_REV),
    val commFuelFinal: ValueWithUnit = ValueWithUnit(0f, Unit.AFR),
    val dam: ValueWithUnit = ValueWithUnit(1f, Unit.DAM),
    val feedbackKnock: ValueWithUnit = ValueWithUnit(0f, Unit.DEGREES),
    val fineKnockLearn: ValueWithUnit = ValueWithUnit(0f, Unit.DEGREES),
    val mafVolts: ValueWithUnit = ValueWithUnit(0f, Unit.VOLTS),
    val tdBoostError: ValueWithUnit = ValueWithUnit(0f, Unit.UNKNOWN),
    val throttlePos: ValueWithUnit = ValueWithUnit(0f, Unit.PERCENT),
    val wastegateDuty: ValueWithUnit = ValueWithUnit(0f, Unit.PERCENT),

    // Generic Map
    val values: Map<String, ValueWithUnit> = emptyMap(),

    // History (storing raw values for now, assuming unit matches the main field)
    val rpmHistory: List<Float> = emptyList(),
    val boostHistory: List<Float> = emptyList(),

    // TPMS Data
    val tpms: Map<String, TpmsState> = emptyMap()
)

data class TpmsState(
    val pressure: ValueWithUnit = ValueWithUnit(0f, Unit.BAR),
    val temp: ValueWithUnit = ValueWithUnit(0f, Unit.C),
    val batteryLow: Boolean = false,
    val leaking: Boolean = false,
    val timestamp: Long = 0L,
    val isStale: Boolean = true,
    val rssi: Int = 0
)
