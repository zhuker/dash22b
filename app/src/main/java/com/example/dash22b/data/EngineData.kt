package com.example.dash22b.data

data class EngineData(
    val rpm: Int = 0,
    val boost: Float = 0f, // bar
    val batteryVoltage: Float = 0f, // V
    val pulseWidth: Float = 0f, // ms?
    val coolantTemp: Int = 0, // C
    val sparkLines: Float = 0f, // degrees? Using 29.6 from screenshot, seems float
    val dutyCycle: Float = 0f, // %
    val speed: Int = 0, // km/h
    val iat: Int = 0, // Intake Air Temp C
    val afr: Float = 0f, // Air Fuel Ratio
    val maf: Float = 0f, // Mass Air Flow g/s
    
    // History for graphs (simplified for now, ideally this would be a separate structure)
    val rpmHistory: List<Float> = emptyList(),
    val boostHistory: List<Float> = emptyList(),
    // ... other histories as needed
)
