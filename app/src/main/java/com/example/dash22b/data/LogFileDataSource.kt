package com.example.dash22b.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader

class LogFileDataSource(
    private val assetLoader: AssetLoader,
    private val parameterRegistry: ParameterRegistry
) {

    fun getEngineData(): Flow<EngineData> {
        return parseLogFile(exampleLogFileName)
            .delayByTimestamp()
            .loop()
    }

    fun parseLogFile(logFileName: String): Flow<EngineData> = flow {
        try {
            var baseTime = defaultBaseTime
            try {
                val simpleName = logFileName.substringAfterLast("/")
                if (simpleName.length >= 14 && simpleName.take(14).all { it.isDigit() }) {
                    val timestampStr = simpleName.take(14)
                    val format =
                        java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                    format.timeZone = java.util.TimeZone.getDefault()
                    baseTime = format.parse(timestampStr)?.time ?: baseTime
                }
            } catch (e: Exception) {
            }

            assetLoader.open(logFileName).bufferedReader(Charsets.ISO_8859_1).use { reader ->
                parseCsv(reader, baseTime)
                return@flow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            emit(EngineData())
        }
    }

    private suspend fun FlowCollector<EngineData>.parseCsv(
        reader: BufferedReader,
        baseTime: Long
    ) {
        val headerLine = reader.readLine() ?: return
        val headers = headerLine.split(",").map { it.trim() }

        data class MappedColumn(
            val index: Int,
            val definition: ParameterDefinition,
            val logUnit: String
        )

        val mappedColumns = mutableListOf<MappedColumn>()

        headers.forEachIndexed { index, header ->
            val pair = findParameterDefinition(header)
            val logUnit = pair.second
            var def = pair.third


            if (def != null) {
                mappedColumns.add(MappedColumn(index, def, logUnit))
            } else {
                println("No definition found for header: $header")
            }
        }

        // Identify critical columns for specific UI logic (Timestamp, History)
        val timeIdx = headers.indexOfFirst { it.contains("Time", ignoreCase = true) }

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

            // Parse All Mapped Columns
            val dynamicValues = mutableMapOf<String, ValueWithUnit>()
            mappedColumns.forEach { col ->
                val rawVal = getF(col.index)
                // Store RAW value with its UNIT
                dynamicValues[col.definition.accessportName] =
                    ValueWithUnit(rawVal, DisplayUnit.fromString(col.logUnit))
            }

            // Backward Compatibility Mapping - Populate Standard Fields with ValueWithUnit items from Map
            // Fallback to defaults with empty unit if missing

            fun getV(
                key: String,
                altKey: String? = null,
                defaultUnit: DisplayUnit = DisplayUnit.UNKNOWN
            ): ValueWithUnit {
                return dynamicValues[key]
                    ?: (if (altKey != null) dynamicValues[altKey] else null)
                    ?: ValueWithUnit(0f, defaultUnit)
            }

            val rpm = getV("RPM", "Engine Speed", DisplayUnit.RPM)
            val boost =
                getV("Boost", "Manifold Relative Pressure", DisplayUnit.PSI) // Default unit guess
            val battery = getV("Battery Voltage", null, DisplayUnit.VOLTS)
            val pulse = getV("Inj Pulse Width", null, DisplayUnit.MILLISECONDS)
            val coolant = getV("Coolant Temp", null, DisplayUnit.F)
            val spark = getV("Ignition Timing", null, DisplayUnit.DEGREES)
            val duty = getV("Inj Duty Cycle", "Injector Duty Cycle", DisplayUnit.PERCENT)
            val speed = getV("Vehicle Speed", null, DisplayUnit.KMH)
            val iat = getV("Intake Temp", null, DisplayUnit.F)
            val afr = getV("AFR", "AF Sens 1 Ratio", DisplayUnit.AFR)
            val maf = getV("Mass Airflow", null, DisplayUnit.GRAMS_PER_SEC)

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
    }

    fun findParameterDefinition(header: String): Triple<String, String, ParameterDefinition?> {
        // Extract Unit if present in header, e.g. "RPM (rpm)" or "Boost (psi)"
        val unitRegex = Regex("(.*)\\s*\\((.*?)\\)")
        val match = unitRegex.matchEntire(header)

        val cleanHeader = match?.groupValues?.get(1)?.trim() ?: header.trim()
        val logUnit = match?.groupValues?.get(2)?.trim() ?: ""

        val def = parameterRegistry.getDefinition(cleanHeader)

        return Triple(cleanHeader, logUnit, def)
    }

    companion object {
        const val exampleLogFileName = "sampleLogs/20251024184038-replaced-o2-sensor.csv"
        const val defaultBaseTime = 1493373060000L

    }
}

private fun <T> Flow<T>.loop(): Flow<T> = flow {
    while (true) {
        collect { emit(it) }
    }
}

private fun Flow<EngineData>.delayByTimestamp(): Flow<EngineData> = flow {
    var previousTimestamp: Long? = null
    collect { data ->
        previousTimestamp?.let {
            val delayMs = data.timestamp - it
            if (delayMs > 0) {
                delay(delayMs)
            }
        }
        emit(data)
        previousTimestamp = data.timestamp
    }
}
