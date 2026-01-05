package com.example.dash22b.data

data class EngineData(
    // Common Dashboard Fields
    val rpm: Int = 0,
    val boost: Float = 0f, // bar
    val batteryVoltage: Float = 0f, // V
    val pulseWidth: Float = 0f, // ms
    val coolantTemp: Int = 0, // C
    val sparkLines: Float = 0f, // Ignition Timing (degrees)
    val dutyCycle: Float = 0f, // %
    val speed: Int = 0, // km/h
    val iat: Int = 0, // Intake Air Temp C
    val afr: Float = 0f, // Air Fuel Ratio
    val maf: Float = 0f, // g/s

    // Extended Log Fields
    val afCorrection: Float = 0f, // %
    val afLearning: Float = 0f, // %
    val afSens1Curr: Float = 0f, // mA
    val afSens3Volts: Float = 0f, // V
    val calculatedLoad: Float = 0f, // g/rev
    val commFuelFinal: Float = 0f, // AFR
    val dam: Float = 1f, // Dyn Adv Mult
    val feedbackKnock: Float = 0f, // degrees
    val fineKnockLearn: Float = 0f, // degrees
    val mafVolts: Float = 0f, // V
    val tdBoostError: Float = 0f, // psi/kPa (normalized to bar maybe? or kept raw? let's keep raw or converting to bar for consistency if we knew unit. Storing as is for now implies unit ambiguity, but likely we won't graph it immediately)
    val throttlePos: Float = 0f, // %
    val wastegateDuty: Float = 0f, // %
    
    // History (for graphs)
    val rpmHistory: List<Float> = emptyList(),
    val boostHistory: List<Float> = emptyList(),
)
