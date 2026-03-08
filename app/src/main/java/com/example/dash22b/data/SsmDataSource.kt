package com.example.dash22b.data

import android.content.Context
import com.example.dash22b.obd.SsmDtcCode
import com.example.dash22b.obd.SsmEcuInit
import com.example.dash22b.obd.SsmExpressionEvaluator
import com.example.dash22b.obd.SsmPacket
import com.example.dash22b.obd.SsmParameter
import com.example.dash22b.obd.SsmSerialManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * SSM real-time data source that provides live ECU data via USB serial.
 * Follows the same architecture pattern as LogFileDataSource.
 *
 * Supports parameter subscription to only poll currently displayed parameters,
 * which is critical for maintaining good refresh rates on the 4800 baud connection.
 */
class SsmDataSource(private val context: Context,
                    private val parameterRegistry: ParameterRegistry
) {

    companion object {
        const val TAG = "SsmDataSource"
        private const val POLL_DELAY_MS = 50L  // Target ~20Hz
        private const val MAX_RETRY_DELAY_MS = 10_000L
        private const val HISTORY_SIZE = 50

        /**
         * Parses SSM response packet into EngineData.
         * Applies conversion expressions and maps to typed fields.
         *
         * @param packet The SSM response packet
         * @param parametersRead The parameters that were requested (in order)
         */
        fun parseResponse(
            packet: com.example.dash22b.obd.SsmPacket,
            parametersRead: List<com.example.dash22b.obd.SsmParameter>
        ): EngineData? {
            val data = packet.data

            // Response format: [0xE8, value1, value2, ...]
            // Skip first byte (0xE8 marker)
            if (data.isEmpty() || data[0] != com.example.dash22b.obd.SsmPacket.RSP_READ_ADDRESS) {
                Timber.tag(TAG).w("Invalid response format")
                return null
            }

            // Fresh map for this specific response packet
            val dynamicValues = mutableMapOf<String, ValueWithUnit>()
            var offset = 1  // Skip 0xE8 marker

            // Parse each parameter value (only the ones we requested)
            parametersRead.forEach { param ->
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

                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error parsing ${param.name}")
                }
            }

            // Build EngineData with current timestamp
            val currentTimestamp = System.currentTimeMillis()

            return EngineData(
                    timestamp = currentTimestamp,
                    values = dynamicValues
            )
        }
    }

    private val serialManager = SsmSerialManager(context)
    private val allParameters = parameterRegistry.getAllDefinitions()

    // Parameter subscription - only poll these parameters
    private val _subscribedParams = MutableStateFlow<Set<String>>(emptySet())

    // Pending DTC read request (serialized with polling loop)
    private val pendingDtcRequest = AtomicReference<CompletableDeferred<List<SsmDtcCode>>?>(null)
    private var dtcDefinitions: List<SsmDtcCode> = emptyList()

    // Pending ECU reset request (serialized with polling loop)
    private val pendingResetRequest = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /**
     * Subscribe to specific parameters by name.
     * Only subscribed parameters will be polled from the ECU.
     */
    fun subscribeToParameters(parameterNames: Set<String>) {
        _subscribedParams.value = parameterNames
        Timber.tag(TAG).d("Subscribed to ${parameterNames.size} parameters: $parameterNames")
    }

    /**
     * Get the SsmParameters to poll based on current subscription.
     * Returns all parameters if no subscription is set.
     */
    private fun getParametersToRead(): List<SsmParameter> {
        val subscribed = _subscribedParams.value
        return if (subscribed.isEmpty()) {
            emptyList()
        } else {
            // Filter to only subscribed parameters
            allParameters.filter { param -> subscribed.contains(param.name) }.map { it as SsmParameter }
        }
    }

    /**
     * Request a DTC read from the polling loop. Returns a deferred that completes
     * with the DTC results once the polling loop services the request.
     */
    fun requestDtcRead(definitions: List<SsmDtcCode>): CompletableDeferred<List<SsmDtcCode>> {
        val deferred = CompletableDeferred<List<SsmDtcCode>>()
        dtcDefinitions = definitions
        pendingDtcRequest.set(deferred)
        return deferred
    }

    /**
     * Request an ECU reset (clear codes) from the polling loop.
     * Writes 0x40 to address 0x000060. Must be serialized with polling.
     */
    fun requestEcuReset(): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingResetRequest.set(deferred)
        return deferred
    }

    /**
     * Read DTC codes from the ECU. Must be called from the polling loop (serial not thread-safe).
     */
    private fun readDtcCodes(definitions: List<SsmDtcCode>): List<SsmDtcCode> {
        if (definitions.isEmpty()) return emptyList()

        // Collect unique addresses for temporary and memorized reads
        val tmpAddresses = definitions.map { it.tmpAddr }.distinct().sorted()
        val memAddresses = definitions.map { it.memAddr }.distinct().sorted()

        Timber.tag(TAG).i("DTC: reading ${tmpAddresses.size} tmp addresses, ${memAddresses.size} mem addresses")

        // Read temporary addresses
        val tmpValues = readAddressBytes(tmpAddresses)
        if (tmpValues == null) {
            Timber.tag(TAG).w("DTC: tmp address read failed")
            return emptyList()
        }
        Timber.tag(TAG).i("DTC: tmp read OK, ${tmpValues.size} bytes, non-zero: ${tmpValues.count { it.toInt() != 0 }}")

        // Read memorized addresses
        val memValues = readAddressBytes(memAddresses)
        if (memValues == null) {
            Timber.tag(TAG).w("DTC: mem address read failed")
            return emptyList()
        }
        Timber.tag(TAG).i("DTC: mem read OK, ${memValues.size} bytes, non-zero: ${memValues.count { it.toInt() != 0 }}")

        // Map addresses to byte values
        val tmpMap = tmpAddresses.zip(tmpValues).toMap()
        val memMap = memAddresses.zip(memValues).toMap()

        // Check each DTC
        return definitions.mapNotNull { dtc ->
            val tmpByte = tmpMap[dtc.tmpAddr] ?: return@mapNotNull null
            val memByte = memMap[dtc.memAddr] ?: return@mapNotNull null

            // 0xFF means the ECU doesn't support this address (unimplemented memory)
            val tmpInt = tmpByte.toInt() and 0xFF
            val memInt = memByte.toInt() and 0xFF
            if (tmpInt == 0xFF && memInt == 0xFF) return@mapNotNull null

            val isTemp = tmpInt and (1 shl dtc.bit) != 0
            val isMem = memInt and (1 shl dtc.bit) != 0

            if (isTemp || isMem) {
                Timber.tag(TAG).d("DTC active: ${dtc.name} tmp=0x${String.format("%02X", tmpByte)} mem=0x${String.format("%02X", memByte)} bit=${dtc.bit} current=$isTemp stored=$isMem")
                dtc.copy(isTemporary = isTemp, isMemorized = isMem)
            } else {
                null
            }
        }
    }

    /**
     * Read a batch of individual byte addresses from the ECU.
     * Splits into chunks to avoid exceeding ECU limits.
     * Returns the byte values in the same order as the addresses.
     */
    private fun readAddressBytes(addresses: List<Int>): List<Byte>? {
        if (addresses.isEmpty()) return emptyList()

        // Split into chunks of ~30 addresses to stay well within ECU limits
        val results = mutableListOf<Byte>()
        for (chunk in addresses.chunked(30)) {
            val params = chunk.mapIndexed { i, addr ->
                SsmParameter(
                    id = "DTC_$i",
                    name = "DTC_$i",
                    address = addr,
                    length = 1,
                    expression = "x",
                    unit = DisplayUnit.UNKNOWN
                )
            }

            val response = serialManager.readParameters(params)
            if (response == null) {
                Timber.tag(TAG).w("DTC batch read failed for ${chunk.size} addresses")
                return null
            }
            val data = response.data
            if (data.isEmpty() || data[0] != SsmPacket.RSP_READ_ADDRESS) return null

            if (data.size < 1 + chunk.size) {
                Timber.tag(TAG).w("DTC response too short: ${data.size} for ${chunk.size} addresses")
                return null
            }
            for (j in 1..chunk.size) {
                results.add(data[j])
            }
        }
        return results
    }

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

            var consecutiveErrors = 0
            while (true) {
                try {
                    // Check for pending DTC read request (must be serviced from this thread)
                    val dtcDeferred = pendingDtcRequest.getAndSet(null)
                    if (dtcDeferred != null) {
                        Timber.tag(TAG).i("Servicing DTC read request (${dtcDefinitions.size} definitions)")
                        try {
                            val results = readDtcCodes(dtcDefinitions)
                            dtcDeferred.complete(results)
                            Timber.tag(TAG).i("DTC read complete: ${results.size} active codes")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.tag(TAG).e(e, "DTC read failed")
                            dtcDeferred.complete(emptyList())
                        }
                    }

                    // Check for pending ECU reset request
                    val resetDeferred = pendingResetRequest.getAndSet(null)
                    if (resetDeferred != null) {
                        Timber.tag(TAG).i("Servicing ECU reset request")
                        try {
                            val success = serialManager.writeAddress(0x000060, 0x40.toByte())
                            resetDeferred.complete(success)
                            Timber.tag(TAG).i("ECU reset ${if (success) "successful" else "failed"}")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.tag(TAG).e(e, "ECU reset failed")
                            resetDeferred.complete(false)
                        }
                    }

                    // Get parameters to read based on current subscription
                    val parametersToRead = getParametersToRead()

                    if (parametersToRead.isEmpty()) {
                        // No parameters subscribed - wait and check again
                        delay(POLL_DELAY_MS)
                        continue
                    }

                    val response = serialManager.readParameters(parametersToRead)
                    if (response != null) {
                        val engineData = parseResponse(response, parametersToRead) ?: EngineData()
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
                        Timber.tag(TAG).w("IO error (likely disconnected), attempting reconnect $e")
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
                        Timber.tag(TAG).e(e, "Error reading parameters $e")
                        delay(1000)
                    }
                }
            }
        } finally {
            Timber.tag(TAG).i("Data flow cancelled, disconnecting")
            serialManager.disconnect()
        }
    }.flowOn(Dispatchers.IO)

}
