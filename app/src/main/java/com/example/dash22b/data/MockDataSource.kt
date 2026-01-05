package com.example.dash22b.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class MockDataSource {
    
    // Simulate data stream
    fun getEngineData(): Flow<EngineData> = flow {
        var currentData = EngineData()
        
        while (true) {
            // Helper to update value
            fun update(current: ValueWithUnit, delta: Float, min: Float, max: Float): ValueWithUnit {
                val newValue = (current.value + delta).coerceIn(min, max)
                return current.copy(value = newValue)
            }
            
            // Simulate random variations
            val newRpm = update(currentData.rpm, Random.nextInt(-100, 100).toFloat(), 800f, 7000f)
            val newBoost = update(currentData.boost, Random.nextFloat() * 0.1f - 0.05f, 0f, 2.0f)

            currentData = currentData.copy(
                rpm = newRpm,
                boost = newBoost,
                batteryVoltage = update(currentData.batteryVoltage, Random.nextFloat() * 0.1f - 0.05f, 12.0f, 14.5f),
                pulseWidth = update(currentData.pulseWidth, Random.nextFloat() * 0.5f - 0.25f, 0f, 20f),
                coolantTemp = update(currentData.coolantTemp, Random.nextInt(-1, 2).toFloat(), 70f, 110f),
                sparkLines = update(currentData.sparkLines, Random.nextFloat() * 0.5f - 0.25f, 10f, 40f),
                dutyCycle = update(currentData.dutyCycle, Random.nextFloat() * 1f - 0.5f, 0f, 100f),
                speed = update(currentData.speed, Random.nextInt(-2, 3).toFloat(), 0f, 240f),
                iat = update(currentData.iat, Random.nextInt(-1, 2).toFloat(), 20f, 60f),
                afr = update(currentData.afr, Random.nextFloat() * 0.1f - 0.05f, 10f, 20f),
                maf = update(currentData.maf, Random.nextFloat() * 1f - 0.5f, 0f, 100f),
                
                // Update history
                rpmHistory = (currentData.rpmHistory + newRpm.value).takeLast(50),
                boostHistory = (currentData.boostHistory + newBoost.value).takeLast(50)
            )
            
            emit(currentData)
            delay(100) // 10Hz update
        }
    }
}
