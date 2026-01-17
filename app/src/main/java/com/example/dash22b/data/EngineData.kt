package com.example.dash22b.data

data class ValueWithUnit(
    val value: Float,
    val unit: String
)

data class EngineData(
    // Common Dashboard Fields
    val timestamp: Long = 0L, // Epoch millis
    
    // Explicit fields now use ValueWithUnit
    // Primitives like Int are wrapped too if they have units (RPM, Speed)
    // Though RPM is arguably dimensionless or unit is "rpm".
    val rpm: ValueWithUnit = ValueWithUnit(0f, "rpm"),
    val boost: ValueWithUnit = ValueWithUnit(0f, "bar"),
    val batteryVoltage: ValueWithUnit = ValueWithUnit(0f, "V"),
    val pulseWidth: ValueWithUnit = ValueWithUnit(0f, "ms"),
    val coolantTemp: ValueWithUnit = ValueWithUnit(0f, "C"),
    val sparkLines: ValueWithUnit = ValueWithUnit(0f, "deg"),
    val dutyCycle: ValueWithUnit = ValueWithUnit(0f, "%"),
    val speed: ValueWithUnit = ValueWithUnit(0f, "km/h"),
    val iat: ValueWithUnit = ValueWithUnit(0f, "C"),
    val afr: ValueWithUnit = ValueWithUnit(0f, "AFR"),
    val maf: ValueWithUnit = ValueWithUnit(0f, "g/s"),

    // Extended Log Fields
    val afCorrection: ValueWithUnit = ValueWithUnit(0f, "%"),
    val afLearning: ValueWithUnit = ValueWithUnit(0f, "%"),
    val afSens1Curr: ValueWithUnit = ValueWithUnit(0f, "mA"),
    val afSens3Volts: ValueWithUnit = ValueWithUnit(0f, "V"),
    val calculatedLoad: ValueWithUnit = ValueWithUnit(0f, "g/rev"),
    val commFuelFinal: ValueWithUnit = ValueWithUnit(0f, "AFR"),
    val dam: ValueWithUnit = ValueWithUnit(1f, ""),
    val feedbackKnock: ValueWithUnit = ValueWithUnit(0f, "deg"),
    val fineKnockLearn: ValueWithUnit = ValueWithUnit(0f, "deg"),
    val mafVolts: ValueWithUnit = ValueWithUnit(0f, "V"),
    val tdBoostError: ValueWithUnit = ValueWithUnit(0f, ""),
    val throttlePos: ValueWithUnit = ValueWithUnit(0f, "%"),
    val wastegateDuty: ValueWithUnit = ValueWithUnit(0f, "%"),
    
    // Generic Map
    val values: Map<String, ValueWithUnit> = emptyMap(),

    // History (storing raw values for now, assuming unit matches the main field)
    val rpmHistory: List<Float> = emptyList(),
    val boostHistory: List<Float> = emptyList(),

    // TPMS Data
    val tpms: Map<String, TpmsState> = emptyMap()
)

data class TpmsState(
    val pressure: ValueWithUnit = ValueWithUnit(0f, "bar"),
    val temp: ValueWithUnit = ValueWithUnit(0f, "C"),
    val batteryLow: Boolean = false,
    val leaking: Boolean = false,
    val timestamp: Long = 0L,
    val isStale: Boolean = true
)
