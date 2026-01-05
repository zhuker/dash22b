package com.example.dash22b.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

class LogFileDataSource(private val assetLoader: AssetLoader) {

    // Ideally this could be selectable, but user said constant.
    // However, user also said "parse ANY of the files".
    // I will stick to one default for now but make it easy to swap or iterate if needed.
    // Let's pick one that has extensive data or the one requested last? 
    // User modified code to use "20251018-p0420.csv". I will stick with that or allow passing it.
    // private val logFileName = "sampleLogs/20251018-p0420.csv"
     private val logFileName = "sampleLogs/20251024184038-replaced-o2-sensor.csv"

    fun getEngineData(): Flow<EngineData> = flow {
        // Initialize Registry
        ParameterRegistry.initialize(assetLoader)
        
        while (true) {
            try {
                // 1. Open File
                val inputStream = assetLoader.open(logFileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                
                // 2. Parse Header
                val headerLine = reader.readLine() ?: break
                val headers = headerLine.split(",").map { it.trim() }
                
                // 3. Dynamic Column Mapping
                data class MappedColumn(val index: Int, val definition: ParameterDefinition, val logUnit: String)
                val mappedColumns = mutableListOf<MappedColumn>()
                
                headers.forEachIndexed { index, header ->
                    // Extract Unit if present in header, e.g. "RPM (rpm)" or "Boost (psi)"
                    val unitRegex = Regex("(.*)\\s*\\((.*?)\\)")
                    val match = unitRegex.matchEntire(header)
                    
                    val cleanHeader = match?.groupValues?.get(1)?.trim() ?: header.trim()
                    val logUnit = match?.groupValues?.get(2)?.trim() ?: ""

                    var def = ParameterRegistry.getDefinition(cleanHeader)
                    
                    // Fallback lookup
                    if (def == null) {
                         val allDefs = ParameterRegistry.getAllDefinitions()
                         def = allDefs.firstOrNull { header.contains(it.accessportName, ignoreCase = true) }
                    }

                    if (def != null) {
                        mappedColumns.add(MappedColumn(index, def, logUnit))
                    }
                }
                
                // Identify critical columns for specific UI logic (Timestamp, History)
                val timeIdx = headers.indexOfFirst { it.contains("Time", ignoreCase = true) }
                
                // 6. Playback Loop
                var baseTime = 1493373060000L 
                try {
                    val simpleName = logFileName.substringAfterLast("/")
                    if (simpleName.length >= 14 && simpleName.take(14).all { it.isDigit() }) {
                        val timestampStr = simpleName.take(14)
                        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                        format.timeZone = java.util.TimeZone.getDefault() 
                        baseTime = format.parse(timestampStr)?.time ?: baseTime
                    }
                } catch (e: Exception) {}

                var startTime = System.currentTimeMillis()
                var previousLogTime = 0f
                var currentHistory = EngineData()

                var line: String? = reader.readLine()
                while (line != null) {
                    val values = line.split(",").map { it.trim() }
                    
                    fun getF(idx: Int, default: Float = 0f): Float {
                        if (idx == -1 || idx >= values.size) return default
                        return values[idx].toFloatOrNull() ?: default
                    }

                    // Time Sync
                    val time = getF(timeIdx)
                    val currentTimestamp = baseTime + (time * 1000).toLong()

                    val timeDiff = (time - previousLogTime) * 1000
                    if (timeDiff > 0) delay(timeDiff.toLong())
                    previousLogTime = time

                    // Parse All Mapped Columns
                    val dynamicValues = mutableMapOf<String, ValueWithUnit>()
                    mappedColumns.forEach { col ->
                        val rawVal = getF(col.index)
                        // Store RAW value with its UNIT
                        dynamicValues[col.definition.accessportName] = ValueWithUnit(rawVal, col.logUnit)
                    }
                    
                    // Backward Compatibility Mapping - Populate Standard Fields with ValueWithUnit items from Map
                    // Fallback to defaults with empty unit if missing
                    
                    fun getV(key: String, altKey: String? = null, defaultUnit: String = ""): ValueWithUnit {
                        return dynamicValues[key] 
                            ?: (if (altKey != null) dynamicValues[altKey] else null)
                            ?: ValueWithUnit(0f, defaultUnit)
                    }

                    val rpm = getV("RPM", "Engine Speed", "rpm")
                    val boost = getV("Boost", "Manifold Relative Pressure", "psi") // Default unit guess
                    val battery = getV("Battery Voltage", null, "V")
                    val pulse = getV("Inj Pulse Width", null, "ms")
                    val coolant = getV("Coolant Temp", null, "F")
                    val spark = getV("Ignition Timing", null, "deg")
                    val duty = getV("Inj Duty Cycle", "Injector Duty Cycle", "%")
                    val speed = getV("Vehicle Speed", null, "km/h")
                    val iat = getV("Intake Temp", null, "F")
                    val afr = getV("AFR", "AF Sens 1 Ratio", "AFR")
                    val maf = getV("Mass Airflow", null, "g/s")
                    
                    val newData = EngineData(
                        timestamp = currentTimestamp,
                        values = dynamicValues, // The new map
                        
                        rpm = rpm,
                        boost = boost,
                        batteryVoltage = battery,
                        pulseWidth = pulse,
                        coolantTemp = coolant,
                        sparkLines = spark,
                        dutyCycle = duty,
                        speed = speed,
                        iat = iat,
                        afr = afr,
                        maf = maf,
                        
                        // History: Use raw values for now. 
                        // Graphs will need to handle unit conversion if needed.
                        rpmHistory = (currentHistory.rpmHistory + rpm.value).takeLast(50),
                        boostHistory = (currentHistory.boostHistory + boost.value).takeLast(50)
                    )
                    
                    currentHistory = newData
                    emit(newData)
                    
                    line = reader.readLine()
                }
                
                reader.close()
                inputStream.close()
                delay(1000)
                
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                emit(EngineData())
                delay(1000)
            }
        }
    }
}
