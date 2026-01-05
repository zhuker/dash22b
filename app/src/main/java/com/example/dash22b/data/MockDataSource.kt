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
            // Simulate random variations
            currentData = currentData.copy(
                rpm = (currentData.rpm + Random.nextInt(-100, 100)).coerceIn(800, 7000),
                boost = (currentData.boost + Random.nextFloat() * 0.1f - 0.05f).coerceIn(0f, 2.0f),
                batteryVoltage = (currentData.batteryVoltage + Random.nextFloat() * 0.1f - 0.05f).coerceIn(12.0f, 14.5f),
                pulseWidth = (currentData.pulseWidth + Random.nextFloat() * 0.5f - 0.25f).coerceIn(0f, 20f),
                coolantTemp = (currentData.coolantTemp + Random.nextInt(-1, 2)).coerceIn(70, 110),
                sparkLines = (currentData.sparkLines + Random.nextFloat() * 0.5f - 0.25f).coerceIn(10f, 40f),
                dutyCycle = (currentData.dutyCycle + Random.nextFloat() * 1f - 0.5f).coerceIn(0f, 100f),
                speed = (currentData.speed + Random.nextInt(-2, 3)).coerceIn(0, 240),
                iat = (currentData.iat + Random.nextInt(-1, 2)).coerceIn(20, 60),
                afr = (currentData.afr + Random.nextFloat() * 0.1f - 0.05f).coerceIn(10f, 20f),
                maf = (currentData.maf + Random.nextFloat() * 1f - 0.5f).coerceIn(0f, 100f),
                
                // Update history
                rpmHistory = (currentData.rpmHistory + currentData.rpm.toFloat()).takeLast(50),
                boostHistory = (currentData.boostHistory + currentData.boost).takeLast(50)
                // Add others as needed
            )
            
            emit(currentData)
            delay(100) // 10Hz update
        }
    }
}
