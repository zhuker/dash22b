package com.example.dash22b.obd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages USB serial connection for SSM communication with the ECU.
 * Based on PiMonitor Python PMConnection implementation.
 */
class SsmSerialManager private constructor(
    private val context: Context?,
    private var link: SsmLink?
) {

    constructor(context: Context) : this(context, null)

    /** A manager already talking over [link], with no USB behind it. For tests. */
    internal constructor(link: SsmLink) : this(null, link)

    companion object {
        const val TAG = "SsmSerialManager"
        private const val BAUD_RATE = 4800
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        private const val READ_TIMEOUT_MS = 2000
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERMISSION = "com.example.dash22b.USB_PERMISSION"

        // Bytes in a read frame that are not parameter data: header, dst, src, len,
        // cmd, checksum. A request adds the padding byte.
        private const val RESPONSE_OVERHEAD = 6
        private const val REQUEST_OVERHEAD = 7

        // Line break that stops a fast-poll stream. RomRaider sizes it from the framing,
        // 10000/baud * (data bits + 2) ms: ~21 ms at 4800, the value proven on the car.
        private const val BREAK_MS = 21L
        // Draining after the break: read with this timeout until nothing arrives, but
        // give up after DRAIN_MAX_MS in case the ECU never stopped.
        private const val DRAIN_READ_TIMEOUT_MS = 50
        private const val DRAIN_MAX_MS = 500L

        /**
         * Why [frame] is not a usable read response for [numAddresses] addresses, or
         * null if it is. A streamed frame has no request of ours in front of it to
         * anchor it, so it is checked the way RomRaider checks one.
         */
        internal fun frameProblem(frame: ByteArray, numAddresses: Int): String? {
            val u = { i: Int -> frame[i].toInt() and 0xFF }
            return when {
                frame.size != numAddresses + RESPONSE_OVERHEAD -> "wrong frame length ${frame.size}"
                frame[0] != SsmPacket.HEADER -> "bad header 0x%02X".format(u(0))
                u(1) != SsmPacket.SOURCE_DIAG -> "bad tester id 0x%02X".format(u(1))
                u(2) != SsmPacket.DESTINATION_ECU && u(2) != SsmPacket.DESTINATION_TCU ->
                    "bad module id 0x%02X".format(u(2))
                frame[4] != SsmPacket.RSP_READ_ADDRESS -> "not a read response 0x%02X".format(u(4))
                SsmPacket.fromBytes(frame) == null -> "bad length byte or checksum"
                else -> null
            }
        }

        /** One address per byte: a multi-byte parameter is consecutive addresses. */
        internal fun addressesOf(parameters: List<SsmParameter>): List<Int> =
            parameters.flatMap { param -> List(param.length) { i -> param.address + i } }

        internal fun buildReadRequest(addresses: List<Int>, padding: Byte): SsmPacket {
            val data = ByteArray(2 + addresses.size * 3)
            data[0] = SsmPacket.CMD_READ_ADDRESS
            data[1] = padding
            addresses.forEachIndexed { i, addr ->
                data[2 + i * 3] = ((addr shr 16) and 0xFF).toByte()
                data[3 + i * 3] = ((addr shr 8) and 0xFF).toByte()
                data[4 + i * 3] = (addr and 0xFF).toByte()
            }
            return SsmPacket(SsmPacket.DESTINATION_ECU, SsmPacket.SOURCE_DIAG, data)
        }
    }

    /** Fast-poll counters since the last [takeFastPollStats], for the periodic health log line. */
    data class FastPollStats(
        val samples: Int = 0,       // good streamed frames
        val streamStarts: Int = 0,  // continuous requests sent
        val badFrames: Int = 0,     // damaged frames and timeouts, each costing a break
        val replays: Int = 0,       // bad frames answered with the previous sample
        val failures: Int = 0       // bad frames with nothing safe to replay
    )

    private var stats = FastPollStats()

    fun takeFastPollStats(): FastPollStats = stats.also { stats = FastPollStats() }

    // Fast poll (SSM continuous read). See docs/ssm_fastpoll_plan.md.
    //
    // STATE_0 writes the request with READ_ADDRESS_CONTINUOUS and reads echo + one
    // response; STATE_1 writes nothing and reads one streamed response. The stream only
    // stops on a line break, so everything that would talk over it calls stopStreaming().
    private enum class PollState { STATE_0, STATE_1 }

    private var pollState = PollState.STATE_0
    // Set as soon as a continuous request goes out, even if its answer never arrives:
    // the ECU may be streaming regardless.
    private var streaming = false
    // The address list the ECU is streaming. A frame is positional, so reading one
    // against a different list decodes every value into the wrong parameter.
    private var streamKey: List<Int>? = null
    // Last good frame, replayed once if a streamed frame arrives damaged.
    private var lastResponse: ByteArray? = null
    // Bytes a read() returned past the end of the frame it was reading. While streaming
    // they are the start of the next frame, so they must not be dropped.
    private var pending = ByteArray(0)
    
    /**
     * Attempts to find and connect to a USB serial device.
     * @return true if connection successful
     */
    fun connect(): Boolean {
        val context = context ?: return false
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            Timber.tag(TAG).w("No USB serial devices found")
            return false
        }
        
        val driver = availableDrivers[0]
        val device = driver.device
        Timber.tag(TAG).i("Found USB device: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")
        
        // Check if we have permission
        if (!usbManager.hasPermission(device)) {
            Timber.tag(TAG).i("Requesting USB permission...")
            requestPermission(context, usbManager, device)
            return false // Permission request is async, will need to retry
        }
        
        return openDevice(usbManager, driver)
    }
    
    private fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)  // Make intent explicit for Android 14+
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else 
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Timber.tag(TAG).i("USB permission ${if (granted) "granted" else "denied"}")
                    context.unregisterReceiver(this)
                    
                    if (granted) {
                        // Try connecting again
                        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                        if (drivers.isNotEmpty()) {
                            if (openDevice(usbManager, drivers[0])) {
                                // Send init now that we're connected
                                val response = sendInit(1)
                                if (response != null) {
                                    Timber.tag(TAG).i("ECU responded! ROM ID: ${SsmEcuInit(response).getRomId()}")
                                }
                                disconnect()
                            }
                        }
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        usbManager.requestPermission(device, permissionIntent)
    }
    
    private fun openDevice(usbManager: UsbManager, driver: UsbSerialDriver): Boolean {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Timber.tag(TAG).e("Failed to open USB device connection")
            return false
        }
        
        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            link = UsbSsmLink(port)
            resetStreamState()
            Timber.tag(TAG).i("Connected to USB serial at $BAUD_RATE baud")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open USB serial port")
            link = null
            return false
        }
    }
    
    /**
     * Sends an ECU init packet and waits for response.
     * @return Response packet, or null if failed
     */
    fun sendInit(target: Int = 1): SsmPacket? {
        val port = this.link ?: run {
            Timber.tag(TAG).e("Not connected")
            return null
        }
        stopStreaming()

        val initPacket = SsmPacket.createInitPacket(target)
        Timber.tag(TAG).i("Sending init packet: ${initPacket.toHexString()}")
        
        try {
            // Send packet
            val txBytes = initPacket.toBytes()

            try {
                port.write(txBytes, WRITE_TIMEOUT_MS)
            } catch (e: java.io.IOException) {
                // Write failed at offset 0 usually means cable disconnected
                Timber.tag(TAG).w("Write failed: ${e.message}")
                handleWriteError()
                return null
            }

            // Small delay after write (like Python version)
            Thread.sleep(50)
            
            // Read response
            val rxBuffer = ByteArray(256)
            val responseData = mutableListOf<Byte>()
            
            // Read header (3 bytes)
            var bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
            if (bytesRead < 3) {
                Timber.tag(TAG).w("No response or incomplete header (got $bytesRead bytes)")
                return null
            }
            
            // Skip our own echo (the cable echoes back what we send)
            var offset = 0
            while (offset < bytesRead) {
                // Look for response header (destination and source are swapped)
                if (rxBuffer[offset] == SsmPacket.HEADER && 
                    offset + 3 < bytesRead &&
                    (rxBuffer[offset + 1].toInt() and 0xFF) == SsmPacket.SOURCE_DIAG) {
                    // This is the response (destination is now 0xF0, our address)
                    break
                }
                offset++
            }
            
            if (offset >= bytesRead) {
                Timber.tag(TAG).w("Could not find response in received data")
                logHex("Received", rxBuffer.copyOf(bytesRead))
                return null
            }
            
            // Copy from response start
            for (i in offset until bytesRead) {
                responseData.add(rxBuffer[i])
            }
            
            // If we got the header, read rest of packet
            if (responseData.size >= 4) {
                val dataLen = responseData[3].toInt() and 0xFF
                val expectedTotal = 5 + dataLen
                
                // Read more if needed
                while (responseData.size < expectedTotal) {
                    bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
                    if (bytesRead <= 0) break
                    for (i in 0 until bytesRead) {
                        responseData.add(rxBuffer[i])
                    }
                }
            }
            
            val responseBytes = responseData.toByteArray()
            logHex("Response", responseBytes)
            
            val response = SsmPacket.fromBytes(responseBytes)
            if (response != null) {
                val romId = SsmEcuInit(response).getRomId()
                Timber.tag(TAG).i("ECU init successful! ROM ID: $romId")
            } else {
                Timber.tag(TAG).w("Failed to parse response packet")
            }
            
            return response
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during init")
            return null
        }
    }
    
    /**
     * Disconnects from the USB serial device.
     */
    fun disconnect() {
        // A streaming ECU keeps clocking frames onto the K-line after the port closes,
        // which would collide with whatever talks on it next -- the OBD-II stack at
        // 10400 baud, or a fresh SSM init.
        stopStreaming()
        try {
            link?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error closing port")
        }
        link = null
        resetStreamState()
        Timber.tag(TAG).i("Disconnected")
    }

    /**
     * Checks if currently connected.
     */
    fun isConnected(): Boolean = link != null

    /**
     * Handles write errors by disconnecting, which will trigger reconnection logic.
     */
    private fun handleWriteError() {
        Timber.tag(TAG).w("Write error detected, disconnecting to trigger reconnect")
        disconnect()
    }

    /**
     * Reads multiple parameters from the ECU in a single batched request.
     * More efficient than individual reads.
     *
     * @param parameters List of SSM parameters to read
     * @return Response packet containing parameter values, or null if failed
     */
    fun readParameters(parameters: List<SsmParameter>): SsmPacket? {
        val port = this.link ?: run {
            Timber.tag(TAG).e("Not connected")
            return null
        }
        // A request on top of a running stream would read streamed frames as its
        // answer. DTC reads land here mid-session with several address lists in a row.
        stopStreaming()

        // Calculate expected sizes upfront
        // For multi-byte parameters, request each byte separately
        val addresses = addressesOf(parameters)
        val requestSize = REQUEST_OVERHEAD + addresses.size * 3  // header(5) + cmd(1) + pad(1) + addresses(3 each) + checksum(1)
        val expectedResponseSize = RESPONSE_OVERHEAD + addresses.size  // header(5) + rspCmd(1) + values(N) + checksum(1)
        val expectedTotalRead = requestSize + expectedResponseSize
        Timber.tag(TAG).d("Expecting to read: request echo=$requestSize + response=$expectedResponseSize = $expectedTotalRead bytes")

        val requestPacket = buildReadRequest(addresses, SsmPacket.READ_ADDRESS_ONCE)

        try {
            // Send packet
            val txBytes = requestPacket.toBytes()
            val requestStart = System.nanoTime()
            Timber.tag(TAG).d("Sending read request: ${requestPacket.toHexString()}")

            try {
                port.write(txBytes, WRITE_TIMEOUT_MS)
            } catch (e: java.io.IOException) {
                // Write failed at offset 0 usually means cable disconnected
                Timber.tag(TAG).w("Write failed: ${e.message}")
                handleWriteError()
                return null
            }

            // Small delay after write
            Thread.sleep(50)

            // Read exactly expectedTotalRead bytes (echo + response)
            val allBytes = ByteArray(expectedTotalRead)
            val rxBuffer = ByteArray(256)
            var totalBytesRead = 0

            while (totalBytesRead < expectedTotalRead) {
                val bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
                if (bytesRead <= 0) {
                    Timber.tag(TAG).w("Read timeout: got $totalBytesRead of $expectedTotalRead bytes")
                    break
                }
                val bytesToCopy = minOf(bytesRead, expectedTotalRead - totalBytesRead)
                System.arraycopy(rxBuffer, 0, allBytes, totalBytesRead, bytesToCopy)
                totalBytesRead += bytesToCopy
            }

            if (totalBytesRead < expectedTotalRead) {
                Timber.tag(TAG).w("Incomplete read: got $totalBytesRead of $expectedTotalRead bytes")
                logHex("Partial data", allBytes.copyOf(totalBytesRead))
                return null
            }

            val requestDuration = System.nanoTime() - requestStart
            val requestDurationMsec = requestDuration / 1_000_000L

            logHex("Received in $requestDurationMsec msec", allBytes)

            // Response starts right after the echo
            val responseBytes = allBytes.copyOfRange(requestSize, expectedTotalRead)
            val response = SsmPacket.fromBytes(responseBytes)

            if (response == null) {
                Timber.tag(TAG).w("Failed to parse response packet")
                return null
            }

            // Verify it's a read response
            if (response.data.isNotEmpty() && response.data[0] != SsmPacket.RSP_READ_ADDRESS) {
                Timber.tag(TAG).w("Unexpected response type: ${String.format("%02X", response.data[0])}")
                return null
            }

            return response

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error reading parameters $e")
            return null
        }
    }

    /**
     * Reads one sample, holding the ECU in continuous-read mode across calls.
     *
     * The first call (and the first after any break) sends the request with
     * READ_ADDRESS_CONTINUOUS; later calls with the same parameter list only read the
     * next streamed frame. Returns the same shape as [readParameters].
     *
     * The caller must keep calling without sleeping: the ECU streams whether or not
     * anyone reads, and frames left unread back up in the cable's FIFO until it
     * overflows and drops bytes.
     *
     * A damaged frame breaks the stream and returns the previous good sample once, so
     * one glitch costs a stale reading rather than the session. Returns null on a
     * failure with nothing safe to replay.
     */
    fun readParametersFast(parameters: List<SsmParameter>): SsmPacket? {
        val link = this.link ?: run {
            Timber.tag(TAG).e("Not connected")
            return null
        }

        val addresses = addressesOf(parameters)
        val responseSize = RESPONSE_OVERHEAD + addresses.size

        if (pollState == PollState.STATE_1 && addresses != streamKey) {
            Timber.tag(TAG).i("Parameter list changed (${streamKey?.size} -> ${addresses.size} addresses), restarting the stream")
            clearLine()
        }

        try {
            val frame: ByteArray? = if (pollState == PollState.STATE_0) {
                val request = buildReadRequest(addresses, SsmPacket.READ_ADDRESS_CONTINUOUS).toBytes()
                Timber.tag(TAG).d("Starting stream: ${request.toHex()}")
                pending = ByteArray(0)
                try {
                    link.write(request, WRITE_TIMEOUT_MS)
                } catch (e: java.io.IOException) {
                    Timber.tag(TAG).w("Write failed: ${e.message}")
                    handleWriteError()
                    return null
                }
                streaming = true
                streamKey = addresses
                stats = stats.copy(streamStarts = stats.streamStarts + 1)
                // Half duplex: the cable echoes the request before the ECU answers.
                readExactly(link, request.size + responseSize)
                    ?.copyOfRange(request.size, request.size + responseSize)
            } else {
                readExactly(link, responseSize)
            }

            val problem = if (frame == null) "read timeout" else frameProblem(frame, addresses.size)
            if (problem == null) {
                lastResponse = frame
                pollState = PollState.STATE_1
                stats = stats.copy(samples = stats.samples + 1)
                return SsmPacket.fromBytes(frame!!)
            }
            stats = stats.copy(badFrames = stats.badFrames + 1)

            // clearLine() forgets lastResponse, so it is captured first.
            val lastGood = lastResponse
            Timber.tag(TAG).w("Bad streamed frame ($problem), breaking the stream")
            frame?.let { logHex("Bad frame", it) }
            clearLine()
            // Only a sample read against this same layout may be replayed; one from
            // another parameter list would decode into nonsense.
            if (lastGood == null || lastGood.size != responseSize) {
                stats = stats.copy(failures = stats.failures + 1)
                return null
            }
            stats = stats.copy(replays = stats.replays + 1)
            return SsmPacket.fromBytes(lastGood)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in fast read $e")
            clearLine()
            return null
        }
    }

    /** Stops a fast-poll stream if one is running. Safe to call at any time. */
    fun stopStreaming() {
        if (streaming) clearLine()
    }

    /**
     * Breaks the stream with a serial line break and drains whatever was in flight.
     * Always leaves the manager in STATE_0 with nothing buffered, even if the break
     * itself fails -- the next fast read then re-requests from scratch.
     */
    private fun clearLine() {
        val link = this.link
        if (link != null) {
            try {
                link.setBreak(true)
                Thread.sleep(BREAK_MS)
                link.setBreak(false)

                val rx = ByteArray(256)
                val deadline = System.currentTimeMillis() + DRAIN_MAX_MS
                var drained = 0
                var quiet = false
                while (System.currentTimeMillis() < deadline) {
                    val n = link.read(rx, DRAIN_READ_TIMEOUT_MS)
                    if (n <= 0) { quiet = true; break }
                    drained += n
                }
                if (quiet) {
                    Timber.tag(TAG).d("Stream stopped, drained $drained bytes")
                } else {
                    Timber.tag(TAG).w("Line still busy ${DRAIN_MAX_MS}ms after the break ($drained bytes)")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to break the stream: $e")
            }
        }
        resetStreamState()
    }

    private fun resetStreamState() {
        streaming = false
        pollState = PollState.STATE_0
        streamKey = null
        lastResponse = null
        pending = ByteArray(0)
    }

    /**
     * Reads exactly [count] bytes, starting with any left over from the previous read,
     * and keeps whatever arrives past [count] for the next call. Null on timeout.
     */
    private fun readExactly(link: SsmLink, count: Int): ByteArray? {
        val out = ByteArray(count)
        var filled = minOf(pending.size, count)
        pending.copyInto(out, 0, 0, filled)
        pending = pending.copyOfRange(filled, pending.size)

        val rx = ByteArray(256)
        while (filled < count) {
            val n = link.read(rx, READ_TIMEOUT_MS)
            if (n <= 0) {
                Timber.tag(TAG).w("Read timeout: got $filled of $count bytes")
                logHex("Partial data", out.copyOf(filled))
                return null
            }
            val take = minOf(n, count - filled)
            rx.copyInto(out, filled, 0, take)
            filled += take
            if (n > take) pending = rx.copyOfRange(take, n)
        }
        return out
    }

    private fun ByteArray.toHex() = joinToString(" ") { String.format("%02X", it) }

    /**
     * Writes a single byte to an ECU address.
     * Used for ECU reset (clear codes): write 0x40 to 0x000060.
     *
     * @return true if ECU acknowledged the write
     */
    fun writeAddress(address: Int, value: Byte): Boolean {
        val port = this.link ?: run {
            Timber.tag(TAG).e("Not connected")
            return false
        }
        stopStreaming()

        // Build write request: [0xB8, addr_hi, addr_mid, addr_lo, value]
        val data = byteArrayOf(
            SsmPacket.CMD_WRITE_ADDRESS,
            ((address shr 16) and 0xFF).toByte(),
            ((address shr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
            value
        )

        val requestPacket = SsmPacket(
            destination = SsmPacket.DESTINATION_ECU,
            source = SsmPacket.SOURCE_DIAG,
            data = data
        )

        try {
            val txBytes = requestPacket.toBytes()
            Timber.tag(TAG).i("Sending write request: ${requestPacket.toHexString()}")

            try {
                port.write(txBytes, WRITE_TIMEOUT_MS)
            } catch (e: java.io.IOException) {
                Timber.tag(TAG).w("Write failed: ${e.message}")
                handleWriteError()
                return false
            }

            Thread.sleep(50)

            // Expected: echo (10 bytes) + response (7 bytes: 80 F0 10 02 F8 40 checksum)
            val expectedRequestSize = txBytes.size
            val expectedResponseSize = 7
            val expectedTotal = expectedRequestSize + expectedResponseSize

            val allBytes = ByteArray(expectedTotal)
            val rxBuffer = ByteArray(256)
            var totalBytesRead = 0

            while (totalBytesRead < expectedTotal) {
                val bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
                if (bytesRead <= 0) {
                    Timber.tag(TAG).w("Read timeout: got $totalBytesRead of $expectedTotal bytes")
                    break
                }
                val bytesToCopy = minOf(bytesRead, expectedTotal - totalBytesRead)
                System.arraycopy(rxBuffer, 0, allBytes, totalBytesRead, bytesToCopy)
                totalBytesRead += bytesToCopy
            }

            if (totalBytesRead < expectedTotal) {
                Timber.tag(TAG).w("Incomplete write response: got $totalBytesRead of $expectedTotal bytes")
                logHex("Partial data", allBytes.copyOf(totalBytesRead))
                return false
            }

            logHex("Write response", allBytes)

            // Parse response (after echo)
            val responseBytes = allBytes.copyOfRange(expectedRequestSize, expectedTotal)
            val response = SsmPacket.fromBytes(responseBytes)

            if (response == null) {
                Timber.tag(TAG).w("Failed to parse write response")
                return false
            }

            // Verify write acknowledgment: data should be [0xF8, value]
            if (response.data.size >= 2 &&
                response.data[0] == SsmPacket.RSP_WRITE_ADDRESS &&
                response.data[1] == value) {
                Timber.tag(TAG).i("ECU write acknowledged")
                return true
            }

            Timber.tag(TAG).w("Unexpected write response: ${response.toHexString()}")
            return false

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error writing address")
            return false
        }
    }

    private fun logHex(label: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { String.format("%02X", it) }
        Timber.tag(TAG).d("$label (${bytes.size} bytes): $hex")
    }
}
