package com.example.dash22b.data

import android.content.Context
import com.example.dash22b.obd.SsmEcuInit
import com.example.dash22b.obd.SsmExpressionEvaluator
import com.example.dash22b.obd.SsmHardcodedParameters
import com.example.dash22b.obd.SsmSerialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import kotlin.math.min

/**
 * SSM real-time data source that provides live ECU data via USB serial.
 * Follows the same architecture pattern as LogFileDataSource.
 */
class SsmDataSource(private val context: Context) {

    companion object {
        private const val TAG = "SsmDataSource"
        private const val POLL_DELAY_MS = 50L  // Target ~20Hz
        private const val MAX_RETRY_DELAY_MS = 10_000L
        private const val HISTORY_SIZE = 50
    }

    private val serialManager = SsmSerialManager(context)
    private val parameters = SsmHardcodedParameters.parameters

    /**
     * Returns a Flow that continuously polls the ECU for real-time data.
     * Handles connection retry with exponential backoff.
     */
    fun getEngineData(): Flow<EngineData> = flow {
        try {
            // Connection loop with exponential backoff retry
            var retryDelay = 1000L
            while (!serialManager.isConnected()) {
                Timber.tag(TAG).i("Attempting to connect to ECU...")
                if (serialManager.connect()) {
                    // Send init to verify connection
                    val initResponse = serialManager.sendInit(1)
                    if (initResponse != null) {
                        val romId = SsmEcuInit(initResponse).getRomId()
                        Timber.tag(TAG).i("Connected to ECU, ROM ID: $romId")
                        break
                    } else {
                        Timber.tag(TAG).w("Init failed, disconnecting")
                        serialManager.disconnect()
                    }
                }

                Timber.tag(TAG).w("Connection failed, retrying in ${retryDelay}ms")
                delay(retryDelay)
                retryDelay = min(retryDelay * 2, MAX_RETRY_DELAY_MS)
            }

            // Continuous polling loop
            var history = EngineData()
            var consecutiveErrors = 0

            while (true) {
                try {
                    val response = serialManager.readParameters(parameters)
                    if (response != null) {
                        val engineData = parseResponse(response, history)
                        history = engineData
                        emit(engineData)
                        consecutiveErrors = 0  // Reset error counter on success
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            Timber.tag(TAG).w("Multiple read failures, checking connection")
                            if (!serialManager.isConnected()) {
                                Timber.tag(TAG).w("Disconnected, attempting reconnect")
                                serialManager.disconnect()
                                consecutiveErrors = 0

                                // Attempt reconnection with backoff
                                retryDelay = 1000L
                                while (!serialManager.isConnected()) {
                                    Timber.tag(TAG).i("Reconnecting to ECU...")
                                    if (serialManager.connect()) {
                                        val initResponse = serialManager.sendInit(1)
                                        if (initResponse != null) {
                                            Timber.tag(TAG).i("Reconnected to ECU")
                                            break
                                        }
                                        serialManager.disconnect()
                                    }
                                    delay(retryDelay)
                                    retryDelay = min(retryDelay * 2, MAX_RETRY_DELAY_MS)
                                }
                            }
                        }
                    }

                    delay(POLL_DELAY_MS)

                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    // IO errors usually mean disconnection
                    if (e is java.io.IOException) {
                        Timber.tag(TAG).w("IO error (likely disconnected), attempting reconnect")
                        serialManager.disconnect()
                        consecutiveErrors = 0

                        // Reconnection loop
                        retryDelay = 1000L
                        while (!serialManager.isConnected()) {
                            Timber.tag(TAG).i("Reconnecting to ECU...")
                            if (serialManager.connect()) {
                                val initResponse = serialManager.sendInit(1)
                                if (initResponse != null) {
                                    Timber.tag(TAG).i("Reconnected to ECU")
                                    break
                                }
                                serialManager.disconnect()
                            }
                            delay(retryDelay)
                            retryDelay = min(retryDelay * 2, MAX_RETRY_DELAY_MS)
                        }
                    } else {
                        Timber.tag(TAG).e(e, "Error reading parameters")
                        delay(1000)
                    }
                }
            }
        } finally {
            Timber.tag(TAG).i("Data flow cancelled, disconnecting")
            serialManager.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parses SSM response packet into EngineData.
     * Applies conversion expressions and maps to typed fields.
     */
    private fun parseResponse(
        packet: com.example.dash22b.obd.SsmPacket,
        previousData: EngineData
    ): EngineData {
        val data = packet.data

        // Response format: [0xE8, value1, value2, ...]
        // Skip first byte (0xE8 marker)
        if (data.isEmpty() || data[0] != com.example.dash22b.obd.SsmPacket.RSP_READ_ADDRESS) {
            Timber.tag(TAG).w("Invalid response format")
            return previousData
        }

        val dynamicValues = mutableMapOf<String, ValueWithUnit>()
        var offset = 1  // Skip 0xE8 marker

        // Parse each parameter value
        parameters.forEach { param ->
            try {
                if (offset + param.length > data.size) {
                    Timber.tag(TAG).w("Not enough data for ${param.name} at offset $offset")
                    return@forEach
                }

                // Parse raw value from bytes
                val rawValue = param.parseValue(data, offset)
                offset += param.length

                // Apply conversion expression
                val convertedValue = SsmExpressionEvaluator.evaluate(param.expression, rawValue)

                // Store with original SSM unit
                dynamicValues[param.name] = ValueWithUnit(convertedValue, param.unit)

                // Log parsed value
                Timber.tag(TAG).d("${param.name}: raw=0x${rawValue.toString(16)}, converted=${convertedValue}${param.unit.displayName()}")

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error parsing ${param.name}")
            }
        }

        // Map to EngineData fields
        // Helper to get value or default
        fun getV(key: String, defaultUnit: Unit = Unit.UNKNOWN): ValueWithUnit {
            return dynamicValues[key] ?: ValueWithUnit(0f, defaultUnit)
        }

        // Unit conversions
        val rpm = getV("Engine Speed", Unit.RPM)

        // Coolant: Convert °C to °F
        val coolantC = getV("Coolant Temp", Unit.C)
        val coolant = coolantC.to(Unit.F)

        // Boost: Convert kPa to bar (gauge pressure)
        // SSM reports absolute pressure, subtract atmospheric (101.3 kPa) for gauge pressure
        val boostKpa = getV("Boost", Unit.KPA)
        val boost = ValueWithUnit(UnitConverter.convert(boostKpa.value - 101.3f, Unit.KPA, Unit.BAR),
            Unit.BAR
        )

        // Intake Air Temp: Convert °C to °F
        val iatC = getV("Intake Air Temp", Unit.C)
        val iat = iatC.to(Unit.F)

        // Mass Airflow: Already in g/s
        val maf = getV("Mass Airflow", Unit.GRAMS_PER_SEC)

        val battery = getV("Battery Voltage", Unit.VOLTS)
        val throttle = getV("Throttle", Unit.PERCENT)
        val spark = getV("Ignition Timing", Unit.DEGREES)
        val knockCorrection = getV("Knock Correction", Unit.DEGREES)
        val map = getV("MAP", Unit.KPA)
        val speed = getV("Vehicle Speed", Unit.KMH)

        // Log final converted values
        Timber.tag(TAG).d("--- Final Values ---")
        Timber.tag(TAG).d("Engine Speed: ${rpm.value} ${rpm.unit}")
        Timber.tag(TAG).d("Coolant Temp: ${coolant.value} ${coolant.unit} (from ${coolantC.value}°C)")
        Timber.tag(TAG).d("Boost: ${boost.value} ${boost.unit} (from ${boostKpa.value} kPa)")
        Timber.tag(TAG).d("Throttle: ${throttle.value} ${throttle.unit}")
        Timber.tag(TAG).d("Ignition Timing: ${spark.value} ${spark.unit}")
        Timber.tag(TAG).d("Knock Correction: ${knockCorrection.value} ${knockCorrection.unit}")
        Timber.tag(TAG).d("Intake Air Temp: ${iat.value} ${iat.unit} (from ${iatC.value}°C)")
        Timber.tag(TAG).d("Battery Voltage: ${battery.value} ${battery.unit}")
        Timber.tag(TAG).d("MAP: ${map.value} ${map.unit}")
        Timber.tag(TAG).d("Vehicle Speed: ${speed.value} ${speed.unit}")
        Timber.tag(TAG).d("Mass Airflow: ${maf.value} ${maf.unit}")

        // Build EngineData with current timestamp
        val currentTimestamp = System.currentTimeMillis()

        return EngineData(
            timestamp = currentTimestamp,
            values = dynamicValues,

            // Core parameters
            rpm = rpm,
            boost = boost,
            coolantTemp = coolant,
            batteryVoltage = battery,
            sparkLines = spark,
            iat = iat,
            maf = maf,

            // Extended parameters (map from SSM names)
            throttlePos = throttle,

            // History tracking (last 50 samples)
            rpmHistory = (previousData.rpmHistory + rpm.value).takeLast(HISTORY_SIZE),
            boostHistory = (previousData.boostHistory + boost.value).takeLast(HISTORY_SIZE)
        )
    }
}
