package com.example.dash22b.data

import android.content.Context
import com.example.dash22b.obd.Obd2SerialManager
import com.example.dash22b.obd.ObdProbe
import com.example.dash22b.obd.ObdReadiness
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
        // Pause between loop passes when nothing is streaming: idle (no subscriptions),
        // single-read fallback, and after a failed read. Never used while fast-polling --
        // the ECU keeps sending while we sleep, and unread frames back up in the cable's
        // FIFO until it overflows (docs/ssm_fastpoll_plan.md, gotcha 5). There the
        // blocking read is the pacing.
        private const val IDLE_POLL_DELAY_MS = 50L

        // Fast poll (SSM continuous read) for gauge reads. After this many failed fast
        // reads in a row, fall back to single reads until the next reconnect rather than
        // stall the gauges.
        private const val FAST_POLL_ENABLED = true
        private const val FAST_POLL_MAX_FAILURES = 3

        // How often the poll loop writes its health line (rate, mode, stream restarts)
        // to the log, so a drive can be judged afterwards from the log file alone.
        private const val POLL_STATS_INTERVAL_MS = 60_000L

        // Settling time when handing the K-line between the SSM and generic OBD-II
        // stacks. The two run at different baud rates on the same pin, so the wire needs
        // to go quiet before the other side starts clocking bits onto it.
        private const val OBD_PROTOCOL_SWAP_DELAY_MS = 500L

        // How many times to re-establish SSM after an OBD-II session before giving up and
        // letting the polling loop's reconnect logic take over.
        private const val SSM_RESTORE_ATTEMPTS = 3
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

            // Parse each parameter value (only the ones we requested).
            //
            // The SSM read response is purely positional: values come back in the order
            // the addresses were requested, with nothing identifying them. So the offset
            // MUST advance by this parameter's length whether or not it parses -- it is
            // a property of the response layout, not of our success in reading it.
            // Advancing it only on success (as this used to) means one failure shifts
            // every later parameter in the row by that many bytes, and they all decode
            // into plausible-looking wrong numbers that reach the graphs and the CSV
            // with nothing marking them as suspect.
            parametersRead.forEach { param ->
                val valueOffset = offset
                offset += param.length

                if (valueOffset + param.length > data.size) {
                    Timber.tag(TAG).w("Not enough data for ${param.name} at offset $valueOffset")
                    return@forEach
                }

                try {
                    // Parse raw value from bytes
                    val rawValue = param.parseValue(data, valueOffset)

                    // Apply conversion expression
                    val convertedValue = SsmExpressionEvaluator.evaluate(param.expression, rawValue)

                    // Monitored values are recorded as complete rows by MonitorCsvWriter.
                    // Timber is reserved for app diagnostics and parse failures.
                    // Store with original SSM unit
                    dynamicValues[param.name] = ValueWithUnit(convertedValue, param.unit)

                } catch (e: Exception) {
                    // Drop just this parameter. Later ones stay aligned because the
                    // offset already moved past this value.
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

    // Pending readiness read (serialized with polling loop). Held as a nullable Report so
    // the caller can distinguish "read failed" (null) from any decoded result.
    private val pendingReadinessRequest =
        AtomicReference<CompletableDeferred<ObdReadiness.Report?>?>(null)

    // Pending raw diagnostic capture (serialized with polling loop).
    private val pendingDumpRequest =
        AtomicReference<CompletableDeferred<DiagnosticDump.Dump?>?>(null)

    // Pending exhaustive TID sweep (serialized with polling loop).
    private val pendingSweepRequest =
        AtomicReference<CompletableDeferred<DiagnosticDump.Dump?>?>(null)

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
            // Filter to only subscribed parameters. filterIsInstance, not a cast: the
            // registry also carries parameters this app produces itself, such as GPS speed,
            // which have no ECU address and must never reach a read request.
            allParameters
                .filter { param -> subscribed.contains(param.name) }
                .filterIsInstance<SsmParameter>()
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
     * Request an emissions readiness read from the polling loop.
     *
     * This is the one request that changes protocol: readiness is not in SSM's address
     * space on this ECU, so the loop hands the cable to [Obd2SerialManager] at 10400 baud
     * and takes it back when done. Gauges stall for the duration (a second or two on fast
     * init, a few more if it has to fall back to the 5-baud slow init).
     */
    fun requestReadiness(): CompletableDeferred<ObdReadiness.Report?> {
        val deferred = CompletableDeferred<ObdReadiness.Report?>()
        pendingReadinessRequest.set(deferred)
        return deferred
    }

    /**
     * Request a raw OBD-II diagnostic capture from the polling loop.
     *
     * Same protocol takeover as [requestReadiness] but longer — roughly a dozen probes at
     * ~150 ms each on top of the init — so the gauges stall for a few seconds.
     */
    fun requestDiagnosticDump(): CompletableDeferred<DiagnosticDump.Dump?> {
        val deferred = CompletableDeferred<DiagnosticDump.Dump?>()
        pendingDumpRequest.set(deferred)
        return deferred
    }

    /**
     * Request an exhaustive Mode $05/$06 TID sweep. Takes about a minute, during which
     * the gauges are stalled — parked use only.
     */
    fun requestObdSweep(): CompletableDeferred<DiagnosticDump.Dump?> {
        val deferred = CompletableDeferred<DiagnosticDump.Dump?>()
        pendingSweepRequest.set(deferred)
        return deferred
    }

    /**
     * Swaps the cable to generic OBD-II, runs [probes], writes the capture, and swaps
     * back. Must be called from the polling loop.
     */
    private fun runObdCapture(
        probes: List<ObdProbe>,
        filePrefix: String
    ): DiagnosticDump.Dump? {
        val obd = Obd2SerialManager(context)
        try {
            serialManager.disconnect()
            Thread.sleep(OBD_PROTOCOL_SWAP_DELAY_MS)

            if (!obd.connect()) {
                Timber.tag(TAG).w("Could not establish a generic OBD-II session")
                return null
            }
            val dump = DiagnosticDump.capture(obd, probes)
            DiagnosticDump.write(
                context.getExternalFilesDir(null) ?: context.filesDir,
                dump,
                prefix = filePrefix
            )
            return dump
        } finally {
            obd.disconnect()
            restoreSsm()
        }
    }

    /**
     * Brings SSM back after a generic OBD-II session.
     *
     * Retries, because a single attempt is not enough in practice: the ECU needs the bus
     * quiet and the diagnostic session actually ended before it will answer SSM again, and
     * on 2026-08-30 one failed attempt left the gauges dead for the rest of the drive with
     * every read returning nothing but its own echo. An init that returns null here is a
     * real failure, not a slow start -- so it is retried rather than left to the polling
     * loop's own error counter, which needs three failed reads per attempt to notice.
     */
    private fun restoreSsm() {
        repeat(SSM_RESTORE_ATTEMPTS) { attempt ->
            Thread.sleep(OBD_PROTOCOL_SWAP_DELAY_MS)
            if (serialManager.connect() && serialManager.sendInit(1) != null) {
                Timber.tag(TAG).i("SSM restored after OBD-II session (attempt ${attempt + 1})")
                return
            }
            Timber.tag(TAG).w("SSM restore attempt ${attempt + 1} failed")
            serialManager.disconnect()
        }
        Timber.tag(TAG).e("Could not restore SSM after the OBD-II session")
    }

    /**
     * Swaps the cable to generic OBD-II, reads readiness, and swaps back.
     *
     * Must be called from the polling loop — serial access is not thread-safe, and for the
     * duration of this call the SSM session does not exist. SSM is torn down first because
     * the two protocols cannot share the wire: different baud rate, different framing.
     * The finally block restores SSM whatever happens, so a failed readiness read costs a
     * reconnect rather than the gauges for the rest of the drive.
     */
    private fun readReadiness(): ObdReadiness.Report? {
        val obd = Obd2SerialManager(context)
        try {
            serialManager.disconnect()
            Thread.sleep(OBD_PROTOCOL_SWAP_DELAY_MS)

            if (!obd.connect()) {
                Timber.tag(TAG).w("Could not establish a generic OBD-II session")
                return null
            }
            return obd.readReadiness()
        } finally {
            obd.disconnect()
            restoreSsm()
        }
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
     * One log line summarising the last stats window, e.g.
     * `Poll: fast, 13 params/16 addr, 1302 samples in 60.0 s = 21.7 Hz, 0 failed reads;
     * stream: 1 start, 0 bad frames, 0 replays, 0 unrecoverable`.
     * The rate is over wall-clock time, so a DTC read or OBD-II takeover inside the window
     * pulls it down; the stream counters stay at zero while in slow mode.
     */
    private fun logPollStats(windowMs: Long, fast: Boolean, params: Int, addresses: Int,
                             samples: Int, failedReads: Int) {
        val s = serialManager.takeFastPollStats()
        val seconds = windowMs / 1000.0
        Timber.tag(TAG).i(
            "Poll: %s, %d params/%d addr, %d samples in %.1f s = %.1f Hz, %d failed reads; " +
                "stream: %d start%s, %d bad frames, %d replays, %d unrecoverable",
            if (fast) "fast" else "slow", params, addresses, samples, seconds,
            if (seconds > 0) samples / seconds else 0.0, failedReads,
            s.streamStarts, if (s.streamStarts == 1) "" else "s", s.badFrames, s.replays, s.failures
        )
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
            var useFastPoll = FAST_POLL_ENABLED
            var fastPollFailures = 0
            var statsWindowStart = System.currentTimeMillis()
            var windowSamples = 0
            var windowFailedReads = 0
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

                    // Check for pending readiness request. Serviced last of the three
                    // because it is the only one that tears down the SSM session.
                    val readinessDeferred = pendingReadinessRequest.getAndSet(null)
                    if (readinessDeferred != null) {
                        Timber.tag(TAG).i("Servicing readiness request (switching to generic OBD-II)")
                        try {
                            val report = readReadiness()
                            readinessDeferred.complete(report)
                            Timber.tag(TAG).i("Readiness read ${if (report != null) "complete" else "failed"}")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.tag(TAG).e(e, "Readiness read failed")
                            readinessDeferred.complete(null)
                        }
                    }

                    // Check for pending diagnostic dump. Like readiness, this takes the
                    // K-line away from SSM for its duration.
                    val dumpDeferred = pendingDumpRequest.getAndSet(null)
                    if (dumpDeferred != null) {
                        Timber.tag(TAG).i("Servicing diagnostic dump request")
                        try {
                            val dump = runObdCapture(ObdProbe.defaultDump(), DiagnosticDump.FILE_PREFIX)
                            dumpDeferred.complete(dump)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.tag(TAG).e(e, "Diagnostic dump failed")
                            dumpDeferred.complete(null)
                        }
                    }

                    // Check for pending TID sweep. Longest of the OBD takeovers.
                    val sweepDeferred = pendingSweepRequest.getAndSet(null)
                    if (sweepDeferred != null) {
                        Timber.tag(TAG).i("Servicing OBD sweep request")
                        try {
                            val dump = runObdCapture(ObdProbe.sweep(), DiagnosticDump.SWEEP_PREFIX)
                            sweepDeferred.complete(dump)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.tag(TAG).e(e, "OBD sweep failed")
                            sweepDeferred.complete(null)
                        }
                    }

                    // Get parameters to read based on current subscription
                    val parametersToRead = getParametersToRead()

                    if (parametersToRead.isEmpty()) {
                        // No parameters subscribed - wait and check again. Nobody reads a
                        // stream while idle, so stop it rather than let the FIFO overflow.
                        serialManager.stopStreaming()
                        delay(IDLE_POLL_DELAY_MS)
                        continue
                    }

                    val response = if (useFastPoll) {
                        serialManager.readParametersFast(parametersToRead)
                    } else {
                        serialManager.readParameters(parametersToRead)
                    }
                    if (response != null) {
                        val engineData = parseResponse(response, parametersToRead) ?: EngineData()
                        emit(engineData)
                        consecutiveErrors = 0  // Reset error counter on success
                        fastPollFailures = 0
                        windowSamples++
                    } else {
                        windowFailedReads++
                        if (useFastPoll && ++fastPollFailures >= FAST_POLL_MAX_FAILURES) {
                            Timber.tag(TAG).w("Fast poll failed $fastPollFailures times in a row, falling back to single reads")
                            serialManager.stopStreaming()
                            useFastPoll = false
                        }
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
                                            useFastPoll = FAST_POLL_ENABLED
                                            fastPollFailures = 0
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

                    val now = System.currentTimeMillis()
                    if (now - statsWindowStart >= POLL_STATS_INTERVAL_MS) {
                        logPollStats(now - statsWindowStart, useFastPoll, parametersToRead.size,
                            parametersToRead.sumOf { it.length }, windowSamples, windowFailedReads)
                        statsWindowStart = now
                        windowSamples = 0
                        windowFailedReads = 0
                    }

                    // No sleep after a good fast read: see IDLE_POLL_DELAY_MS.
                    if (!useFastPoll || response == null) {
                        delay(IDLE_POLL_DELAY_MS)
                    }

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
                                    useFastPoll = FAST_POLL_ENABLED
                                    fastPollFailures = 0
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
