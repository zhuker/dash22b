package com.example.dash22b.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

class LogFileDataSource(private val context: Context) {

    // Ideally this could be selectable, but user said constant.
    // However, user also said "parse ANY of the files".
    // I will stick to one default for now but make it easy to swap or iterate if needed.
    // Let's pick one that has extensive data or the one requested last? 
    // User modified code to use "20251018-p0420.csv". I will stick with that or allow passing it.
//    private val logFileName = "sampleLogs/20251018-p0420.csv"
     private val logFileName = "sampleLogs/20251024184038-replaced-o2-sensor.csv"

    fun getEngineData(): Flow<EngineData> = flow {
        while (true) {
            try {
                // 1. Open File
                val inputStream = context.assets.open(logFileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                
                // 2. Parse Header
                val headerLine = reader.readLine() ?: break
                val headers = headerLine.split(",").map { it.trim() }
                
                // 3. Helper to find columns
                fun findCol(vararg substrings: String): Int {
                    // Find first header that contains ANY of the substrings (case insensitive?)
                    // The log headers seem consistent in casing but let's be safe(r) if needed.
                    // For now, exact containment.
                    return headers.indexOfFirst { header -> 
                        substrings.any { sub -> header.contains(sub, ignoreCase = true) } 
                    }
                }

                // 4. Map Independent Columns
                val timeIdx = findCol("Time")
                val rpmIdx = findCol("RPM", "Engine Speed")
                val boostIdx = findCol("Boost", "Manifold Relative Pressure")
                val afrIdx = findCol("AF Sens 1 Ratio", "AFR")
                val iatIdx = findCol("Intake Temp")
                val coolantIdx = findCol("Coolant Temp")
                val mafIdx = findCol("MAF (g/s)")
                val dutyIdx = findCol("Inj Duty Cycle")
                val timingIdx = findCol("Ignition Timing")
                val speedIdx = findCol("Vehicle Speed")
                val batteryIdx = findCol("Battery Voltage")
                
                // Extended Fields
                val afCorrIdx = findCol("AF Correction 1")
                val afLearnIdx = findCol("AF Learning 1")
                val afSens1CurrIdx = findCol("AF Sens 1 Curr")
                val afSens3VoltsIdx = findCol("AF Sens 3 Volts")
                val calcLoadIdx = findCol("Calculated Load")
                val commFuelFinalIdx = findCol("Comm Fuel Final")
                val damIdx = findCol("Dyn Adv Mult", "DAM")
                val fbKnockIdx = findCol("Feedback Knock")
                val fkLearnIdx = findCol("Fine Knock Learn")
                val mafVoltsIdx = findCol("MAF Volts")
                val tdBoostErrIdx = findCol("TD Boost Error")
                val throttleIdx = findCol("Throttle Pos")
                val wgDutyIdx = findCol("Wastegate Duty")


                // 5. Unit Detection Helpers
                fun isUnit(idx: Int, unit: String): Boolean {
                    return if (idx != -1) headers[idx].contains(unit, ignoreCase = true) else false
                }
                
                val isBoostKpa = isUnit(boostIdx, "kPa")
                val isBoostPsi = isUnit(boostIdx, "psi")
                
                val isIatF = isUnit(iatIdx, "(F)")
                val isCoolantF = isUnit(coolantIdx, "(F)")

                // 6. Playback Loop
                // Attempt to determine base time from filename
                // Format: YYYYMMDDHHMMSS-xxxx.csv
                var baseTime = 1493373060000L // Default fallback
                try {
                    val simpleName = logFileName.substringAfterLast("/")
                    // Take first 14 chars
                    if (simpleName.length >= 14 && simpleName.take(14).all { it.isDigit() }) {
                        val timestampStr = simpleName.take(14)
                        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                        format.timeZone = java.util.TimeZone.getDefault() 
                        baseTime = format.parse(timestampStr)?.time ?: baseTime
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }

                var startTime = System.currentTimeMillis()
                var previousLogTime = 0f
                var currentHistory = EngineData()

                var line: String? = reader.readLine()
                while (line != null) {
                    // Handle quoted strings in CSV if necessary? The logs seem to have quotes only at the end description.
                    // Simple split should work for data interaction lines which are numbers.
                    val values = line.split(",").map { it.trim() }
                    
                    fun getF(idx: Int, default: Float = 0f): Float {
                        if (idx == -1 || idx >= values.size) return default
                        return values[idx].toFloatOrNull() ?: default
                    }
                    
                    fun getI(idx: Int, default: Int = 0): Int {
                        if (idx == -1 || idx >= values.size) return default
                        return values[idx].toFloatOrNull()?.toInt() ?: default
                    }

                    // Time Sync
                    val time = getF(timeIdx) // Seconds from start of log
                    val currentTimestamp = baseTime + (time * 1000).toLong()

                    val timeDiff = (time - previousLogTime) * 1000
                    if (timeDiff > 0) delay(timeDiff.toLong())
                    previousLogTime = time

                    // Parse Data
                    val rpm = getI(rpmIdx)
                    
                    // Boost Conversion to Bar
                    // If no boost column, 0. If kPa -> /100. If psi -> * 0.0689.
                    var boostVal = getF(boostIdx)
                    if (isBoostKpa) boostVal /= 100f
                    else if (isBoostPsi || boostIdx != -1) boostVal *= 0.0689476f // Assume PSI if not kPa but found column? 
                    
                    // Temp Conversion to C
                    var iatVal = getI(iatIdx)
                    if (isIatF) iatVal = ((iatVal - 32) * 5 / 9)
                    
                    var coolantVal = getI(coolantIdx)
                    if (isCoolantF) coolantVal = ((coolantVal - 32) * 5 / 9)
                    
                    val newData = EngineData(
                        timestamp = currentTimestamp,
                        rpm = rpm,
                        boost = boostVal,
                        batteryVoltage = getF(batteryIdx),
                        pulseWidth = 0f, // Need mapping if available
                        coolantTemp = coolantVal,
                        sparkLines = getF(timingIdx),
                        dutyCycle = getF(dutyIdx),
                        speed = getI(speedIdx),
                        iat = iatVal,
                        afr = getF(afrIdx),
                        maf = getF(mafIdx),
                        
                        afCorrection = getF(afCorrIdx),
                        afLearning = getF(afLearnIdx),
                        afSens1Curr = getF(afSens1CurrIdx),
                        afSens3Volts = getF(afSens3VoltsIdx),
                        calculatedLoad = getF(calcLoadIdx),
                        commFuelFinal = getF(commFuelFinalIdx),
                        dam = getF(damIdx, 1.0f), // Default DAM 1.0
                        feedbackKnock = getF(fbKnockIdx),
                        fineKnockLearn = getF(fkLearnIdx),
                        mafVolts = getF(mafVoltsIdx),
                        tdBoostError = getF(tdBoostErrIdx),
                        throttlePos = getF(throttleIdx),
                        wastegateDuty = getF(wgDutyIdx),
                        
                        // History
                        rpmHistory = (currentHistory.rpmHistory + rpm.toFloat()).takeLast(50),
                        boostHistory = (currentHistory.boostHistory + boostVal).takeLast(50)
                    )
                    
                    currentHistory = newData
                    emit(newData)
                    
                    line = reader.readLine()
                }
                
                reader.close()
                inputStream.close()
                delay(1000) // Pause before loop
                
            } catch (e: Exception) {
                e.printStackTrace()
                emit(EngineData())
                delay(1000)
            }
        }
    }
}
