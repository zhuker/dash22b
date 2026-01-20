package com.example.dash22b.data

import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockDataSource {

    // Simulate data stream
    fun getEngineData(): Flow<EngineData> = flow {
        var currentData = EngineData()

        while (true) {
            // Simulate random variations
            val rpmVal =
                    (currentData.values["Engine Speed"]?.value
                            ?: 800f) + Random.nextInt(-100, 100).toFloat()
            val rpmValue = rpmVal.coerceIn(800f, 7000f)

            val boostVal =
                    (currentData.values["Boost"]?.value ?: 0f) + (Random.nextFloat() * 0.1f - 0.05f)
            val boostValue = boostVal.coerceIn(0f, 2.0f)

            val newValues = currentData.values.toMutableMap()
            newValues["Engine Speed"] = ValueWithUnit(rpmValue, DisplayUnit.RPM)
            newValues["Boost"] = ValueWithUnit(boostValue, DisplayUnit.BAR)
            newValues["Battery Voltage"] =
                    ValueWithUnit(
                            ((currentData.values["Battery Voltage"]?.value
                                            ?: 13.5f) + (Random.nextFloat() * 0.1f - 0.05f))
                                    .coerceIn(12.0f, 14.5f),
                            DisplayUnit.VOLTS
                    )
            newValues["Coolant Temp"] =
                    ValueWithUnit(
                            ((currentData.values["Coolant Temp"]?.value
                                            ?: 90f) + Random.nextInt(-1, 2).toFloat()).coerceIn(
                                    70f,
                                    110f
                            ),
                            DisplayUnit.F
                    )
            newValues["Ignition Timing"] =
                    ValueWithUnit(
                            ((currentData.values["Ignition Timing"]?.value
                                            ?: 20f) + (Random.nextFloat() * 0.5f - 0.25f)).coerceIn(
                                    10f,
                                    40f
                            ),
                            DisplayUnit.DEGREES
                    )
            newValues["Vehicle Speed"] =
                    ValueWithUnit(
                            ((currentData.values["Vehicle Speed"]?.value
                                            ?: 0f) + Random.nextInt(-2, 3).toFloat()).coerceIn(
                                    0f,
                                    240f
                            ),
                            DisplayUnit.KMH
                    )
            newValues["Intake Air Temp"] =
                    ValueWithUnit(
                            ((currentData.values["Intake Air Temp"]?.value
                                            ?: 40f) + Random.nextInt(-1, 2).toFloat()).coerceIn(
                                    20f,
                                    60f
                            ),
                            DisplayUnit.F
                    )
            newValues["AFR"] =
                    ValueWithUnit(
                            ((currentData.values["AFR"]?.value
                                            ?: 14.7f) + (Random.nextFloat() * 0.1f - 0.05f))
                                    .coerceIn(10f, 20f),
                            DisplayUnit.AFR
                    )
            newValues["Mass Airflow"] =
                    ValueWithUnit(
                            ((currentData.values["Mass Airflow"]?.value
                                            ?: 5f) + (Random.nextFloat() * 1f - 0.5f)).coerceIn(
                                    0f,
                                    100f
                            ),
                            DisplayUnit.GRAMS_PER_SEC
                    )

            currentData = currentData.copy(values = newValues)

            emit(currentData)
            delay(100) // 10Hz update
        }
    }
}
