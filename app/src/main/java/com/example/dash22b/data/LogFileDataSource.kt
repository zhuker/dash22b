package com.example.dash22b.data

// import android.content.Context
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

    // ... (previous code) ...
    // private val logFileName = "sampleLogs/20251024184038-replaced-o2-sensor.csv" (keep existing)

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
                data class MappedColumn(val index: Int, val definition: ParameterDefinition)
                val mappedColumns = mutableListOf<MappedColumn>()
                
                headers.forEachIndexed { index, header ->
                    // Try to find a matching definition in the registry
                    // The Registry maps "Accessport Monitor Name" -> Definition
                    // But Log Headers might be slightly different.
                    // The provided CSV "Accessport Monitor" column matches the log headers usually.
                    // Let's try direct look up specific normalization.
                    
                    // Simple normalization: remove units in parens if present for matching? 
                    // e.g. "RPM (rpm)" -> "RPM". 
                    // Registry has "RPM".
                    
                    // Specific to Cobb generic logs: Headers often match exactly or need simple cleaning.
                    // Let's iterate all definitions and see if header contains the name (case insensitive).
                    // Or specific known mappings.
                    
                    // Optimization: Registry keys are lowercase accessport names.
                    // Try to match header (lowercased) to keys.
                    // Also strip common units "(psi)", "(F)", "(g/s)" etc from header before matching.
                    
                    val cleanHeader = header.replace(Regex("\\(.*?\\)"), "").trim()
                    var def = ParameterRegistry.getDefinition(cleanHeader)
                    
                    // Fallback: Check if any definition's accessport name is contained in the header?
                    if (def == null) {
                         val allDefs = ParameterRegistry.getAllDefinitions()
                         def = allDefs.firstOrNull { header.contains(it.accessportName, ignoreCase = true) }
                    }

                    if (def != null) {
                        mappedColumns.add(MappedColumn(index, def))
                    }
                }
                
                // Identify critical columns for specific UI logic (Timestamp, History)
                val timeIdx = headers.indexOfFirst { it.contains("Time", ignoreCase = true) }
                
                // 6. Playback Loop (Keep existing time logic)
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
                    val dynamicValues = mutableMapOf<String, Float>()
                    mappedColumns.forEach { col ->
                        val rawVal = getF(col.index)
                        // TODO: Apply conversions if needed based on col.definition.unit vs expected?
                        // For now assuming log units match display expectations (Accessport standard).
                        // Except known ones like Boost (kPa/PSI vs Bar).
                        
                        var processedVal = rawVal
                        
                        // Special Handling from previous logic
                        if (col.definition.name.equals("Boost", ignoreCase = true) ||
                            col.definition.name.equals("Manifold Relative Pressure", ignoreCase = true)) {
                             // Heuristic: If value > 50, likely kPa. If value < 30 and > -14.7, likely psi?
                             // But let's check header unit again if possible?
                             // Registry handles name mapping. The *header* had the unit.
                             // Re-using previous simple logic:
                             val headerStr = headers[col.index]
                             if (headerStr.contains("kPa", ignoreCase = true)) processedVal /= 100f // to Bar
                             else if (headerStr.contains("psi", ignoreCase = true)) processedVal *= 0.0689476f // to Bar
                        }
                        
                        if (col.definition.name.equals("Intake Temp", ignoreCase = true) || 
                            col.definition.name.equals("Coolant Temp", ignoreCase = true)) {
                                val headerStr = headers[col.index]
                                if (headerStr.contains("(F)", ignoreCase = true)) {
                                    processedVal = (processedVal - 32) * 5 / 9
                                }
                        }
                        
                        dynamicValues[col.definition.accessportName] = processedVal
                    }
                    
                    // Backward Compatibility Mapping
                    // Use standard names from Registry to populate standard fields
                    // Note: Ensure Registry names match these exactly or map them.
                    // Common names: "RPM", "Boost", "Battery Voltage", "Coolant Temp", "Ignition Timing", "Inj Duty Cycle", 
                    // "Vehicle Speed", "Intake Temp", "AFR", "MAF"
                    
                    val rpm = dynamicValues["RPM"]?.toInt() ?: dynamicValues["Engine Speed"]?.toInt() ?: 0
                    val boost = dynamicValues["Boost"] ?: dynamicValues["Manifold Relative Pressure"] ?: 0f
                    val battery = dynamicValues["Battery Voltage"] ?: 0f
                    val pulse = 0f // Not standard mapped yet?
                    val coolant = dynamicValues["Coolant Temp"]?.toInt() ?: 0
                    val spark = dynamicValues["Ignition Timing"] ?: 0f
                    val duty = dynamicValues["Inj Duty Cycle"] ?: dynamicValues["Injector Duty Cycle"] ?: 0f
                    val speed = dynamicValues["Vehicle Speed"]?.toInt() ?: 0
                    val iat = dynamicValues["Intake Temp"]?.toInt() ?: 0
                    val afr = dynamicValues["AFR"] ?: dynamicValues["AF Sens 1 Ratio"] ?: 0f
                    val maf = dynamicValues["Mass Airflow"] ?: 0f // or MAF
                    
                    
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
                        
                        // Populate others if needed, or rely on 'values' map in UI now
                        
                        // History
                        rpmHistory = (currentHistory.rpmHistory + rpm.toFloat()).takeLast(50),
                        boostHistory = (currentHistory.boostHistory + boost).takeLast(50)
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
