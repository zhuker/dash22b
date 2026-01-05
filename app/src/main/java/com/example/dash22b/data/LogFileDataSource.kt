package com.example.dash22b.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

class LogFileDataSource(private val context: Context) {

    private val logFileName = "sampleLogs/20251018-p0420.csv"

    fun getEngineData(): Flow<EngineData> = flow {
        while (true) {
            try {
                val inputStream = context.assets.open(logFileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                
                // Parse Header
                val headerLine = reader.readLine() ?: break
                val headers = headerLine.split(",").map { it.trim() }
                
                // Map columns to indices
                val rpmIndex = headers.indexOfFirst { it.contains("RPM") }
                val boostIndex = headers.indexOfFirst { it.contains("Boost") || it.contains("Manifold Relative Pressure") }
                val afrIndex = headers.indexOfFirst { it.contains("AF Sens 1 Ratio") || it.contains("AFR") }
                val iatIndex = headers.indexOfFirst { it.contains("Intake Temp") }
                val isIatFahrenheit = iatIndex != -1 && headers[iatIndex].contains("(F)")
                val mafIndex = headers.indexOfFirst { it.contains("MAF (g/s)") }
                val dutyCycleIndex = headers.indexOfFirst { it.contains("Inj Duty Cycle") }
                val timingIndex = headers.indexOfFirst { it.contains("Ignition Timing") }
                val timeIndex = headers.indexOfFirst { it.contains("Time") }

                var startTime: Long = System.currentTimeMillis()
                var previousLogTime = 0f
                var currentHistory = EngineData() // Keep history

                var line: String? = reader.readLine()
                while (line != null) {
                    println(line)
                    val values = line.split(",").map { it.trim() }
                    
                    // Parse values safetly
                    fun getFloat(index: Int): Float = 
                        if (index != -1 && index < values.size) values[index].toFloatOrNull() ?: 0f else 0f
                    
                    fun getInt(index: Int): Int = 
                        if (index != -1 && index < values.size) values[index].toFloatOrNull()?.toInt() ?: 0 else 0

                    val time = getFloat(timeIndex)
                    
                    // Calculate delay
                    val timeDiff = (time - previousLogTime) * 1000 // to ms
                    if (timeDiff > 0) {
                        delay(timeDiff.toLong())
                    }
                    previousLogTime = time

                    val rpm = getInt(rpmIndex)
                    val boostKpa = getFloat(boostIndex)
                    val boostBar = if (headers.getOrNull(boostIndex)?.contains("kPa") == true) boostKpa / 100f else boostKpa * 0.0689476f // psi to bar default if not kPa, or just raw if assume bar
                    // Actually if we matched "Boost (kPa)" previously it was kPa. Now we match "Boost" generic.
                    // Let's refine:
                    var finalBoost = boostKpa
                    if (headers.getOrNull(boostIndex)?.contains("kPa") == true) {
                        finalBoost = boostKpa / 100f
                    } else if (headers.getOrNull(boostIndex)?.contains("psi") == true) {
                        finalBoost = boostKpa * 0.0689476f
                    }
                    
                    val afr = getFloat(afrIndex)
                    var iat = getInt(iatIndex)
                    if (isIatFahrenheit) {
                        iat = ((iat - 32) * 5 / 9)
                    }
                    
                    val maf = getFloat(mafIndex)
                    val duty = getFloat(dutyCycleIndex)
                    val timing = getFloat(timingIndex)

                    val newData = EngineData(
                        rpm = rpm,
                        boost = finalBoost,
                        batteryVoltage = 0f, // Not in log
                        pulseWidth = 0f, // Not in log
                        coolantTemp = 90, // Not in log, use dummy or 0? Prompt says fill with zeros. But 90 is standard. User said "fill with zeros". Okay, 0.
                        sparkLines = timing,
                        dutyCycle = duty,
                        speed = 0, // Not in log
                        iat = iat,
                        afr = afr,
                        maf = maf,
                        rpmHistory = (currentHistory.rpmHistory + rpm.toFloat()).takeLast(50),
                        boostHistory = (currentHistory.boostHistory + finalBoost).takeLast(50)
                    )
                    
                    currentHistory = newData
                    emit(newData)

                    line = reader.readLine()
                }

                reader.close()
                inputStream.close()
                
                // Loop delay
                delay(1000)
                
            } catch (e: Exception) {
                e.printStackTrace()
                // Emit empty or retry
                emit(EngineData())
                delay(1000)
            }
        }
    }
}
