package com.example.dash22b.obd

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import timber.log.Timber

/**
 * Generic OBD-II over the K-line, for the things SSM cannot answer — chiefly the
 * emissions readiness monitors ([ObdReadiness]).
 *
 * This is a second protocol stack sharing one wire with SSM, not an extension of it:
 *
 * |            | SSM ([SsmSerialManager]) | Generic OBD-II (here) |
 * |------------|--------------------------|-----------------------|
 * | Baud       | 4800                     | 10400                 |
 * | Init       | `0xBF` request           | 25 ms break, or 5-baud address |
 * | Framing    | `0x80 [dst][src][len]`   | ISO 9141-2 / KWP2000 header |
 *
 * Because both speak on the same pin at different baud rates, the two cannot be open at
 * once. The caller must release the SSM port first and re-establish it afterwards; see
 * `SsmDataSource.requestReadiness`, which serialises that swap inside the polling loop.
 *
 * **Init strategy.** Which of the two pre-CAN protocols this ECU speaks is not documented
 * in the FSM (EN(STi)(diag)-24 says only "a general scan tool required by SAE J1978"), so
 * this tries the cheap one first and falls back, exactly as a generic scan tool does:
 * KWP2000 fast init (~50 ms), then ISO 9141-2 5-baud slow init (~2.5 s). Whichever answers
 * decides the frame format for the session.
 */
class Obd2SerialManager(private val context: Context) {

    companion object {
        const val TAG = "Obd2SerialManager"

        /** Generic OBD-II on the K-line runs at 10400 baud, unlike SSM's 4800. */
        private const val BAUD_RATE = 10400
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        private const val WRITE_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 500

        /** ISO 14230-2 fast-init wake-up: 25 ms low then 25 ms high. */
        private const val FAST_INIT_LOW_MS = 25L
        private const val FAST_INIT_HIGH_MS = 25L

        /** ISO 9141-2 slow init clocks address 0x33 out at 5 baud — 200 ms per bit. */
        private const val SLOW_INIT_BIT_MS = 200L
        private const val SLOW_INIT_ADDRESS = 0x33

        /** P3 min — quiet time the bus wants between a response and the next request. */
        private const val INTER_REQUEST_DELAY_MS = 55L

        /** How long to keep draining after the first bytes arrive, to catch the whole frame. */
        private const val RESPONSE_SETTLE_MS = 120L
        private const val RESPONSE_TOTAL_TIMEOUT_MS = 2000L
    }

    private var port: UsbSerialPort? = null
    private var format: Obd2Frame.Format? = null

    /** True once a session is initialised and requests can be sent. */
    fun isReady(): Boolean = port != null && format != null

    /**
     * Opens the port at OBD-II speed and establishes a session.
     *
     * Does not request USB permission: by the time readiness is asked for, SSM has already
     * been talking on this cable, so permission exists. If it somehow does not, this fails
     * and the caller reports that rather than popping a dialog from a background service.
     *
     * @return true if an ECU answered one of the two inits.
     */
    fun connect(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Timber.tag(TAG).w("No USB serial device found")
            return false
        }

        val driver = drivers[0]
        if (!usbManager.hasPermission(driver.device)) {
            Timber.tag(TAG).w("No USB permission for ${driver.device.deviceName}")
            return false
        }

        val connection = usbManager.openDevice(driver.device) ?: run {
            Timber.tag(TAG).e("Could not open USB device")
            return false
        }

        val opened = driver.ports[0]
        try {
            opened.open(connection)
            opened.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            port = opened
            Timber.tag(TAG).i("Port open at $BAUD_RATE baud for generic OBD-II")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not open port at $BAUD_RATE baud")
            closeQuietly(opened)
            port = null
            return false
        }

        format = fastInit() ?: slowInit()
        if (format == null) {
            Timber.tag(TAG).w("No ECU response to either fast or slow init")
            disconnect()
            return false
        }

        Timber.tag(TAG).i("OBD-II session established using $format")
        return true
    }

    /**
     * ISO 14230-4 fast init: a 25 ms break, 25 ms idle, then StartCommunication.
     *
     * @return [Obd2Frame.Format.KWP2000] if the ECU answered, else null.
     */
    private fun fastInit(): Obd2Frame.Format? {
        val activePort = port ?: return null
        return try {
            Timber.tag(TAG).d("Trying KWP2000 fast init")
            activePort.setBreak(true)
            Thread.sleep(FAST_INIT_LOW_MS)
            activePort.setBreak(false)
            Thread.sleep(FAST_INIT_HIGH_MS)

            // StartCommunication: C1 33 F1 81 + checksum
            val request = byteArrayOf(0xC1.toByte(), 0x33, 0xF1.toByte(), 0x81.toByte())
            val frame = request + Obd2Frame.checksum(request).toByte()
            activePort.write(frame, WRITE_TIMEOUT_MS)

            val response = drainResponse(activePort)
            Timber.tag(TAG).d("Fast init response: ${Obd2Frame.toHex(response)}")

            // A positive StartCommunication response is 0xC1 (0x81 + 0x40), past our echo.
            val answered = response.size > frame.size &&
                response.drop(frame.size).any { (it.toInt() and 0xFF) == 0xC1 }
            if (answered) Obd2Frame.Format.KWP2000 else null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Fast init failed")
            null
        }
    }

    /**
     * ISO 9141-2 slow init: clock address 0x33 out at 5 baud by holding the line, then
     * read the ECU's 0x55 sync byte and key bytes.
     *
     * The 5-baud address cannot be sent by the UART at this baud rate, so it is bit-banged
     * with the break line: one 200 ms start bit low, eight data bits LSB-first, one stop
     * bit high. That is 2 seconds of wall clock before the ECU even answers, which is why
     * this is the fallback rather than the first attempt.
     *
     * @return [Obd2Frame.Format.ISO9141] if the ECU synced, else null.
     */
    private fun slowInit(): Obd2Frame.Format? {
        val activePort = port ?: return null
        return try {
            Timber.tag(TAG).d("Trying ISO 9141-2 slow init (5 baud address)")
            activePort.purgeHwBuffers(true, true)

            // Start bit (low), then 0x33 LSB-first, then stop bit (high). setBreak(true)
            // pulls the line low, which is what a '0' bit looks like on the wire.
            activePort.setBreak(true)
            Thread.sleep(SLOW_INIT_BIT_MS)
            for (bit in 0 until 8) {
                val high = (SLOW_INIT_ADDRESS shr bit) and 1 == 1
                activePort.setBreak(!high)
                Thread.sleep(SLOW_INIT_BIT_MS)
            }
            activePort.setBreak(false)
            Thread.sleep(SLOW_INIT_BIT_MS)

            val response = drainResponse(activePort)
            Timber.tag(TAG).d("Slow init response: ${Obd2Frame.toHex(response)}")

            // The ECU answers with 0x55 followed by two key bytes.
            val synced = response.any { (it.toInt() and 0xFF) == 0x55 }
            if (synced) Obd2Frame.Format.ISO9141 else null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Slow init failed")
            null
        }
    }

    /**
     * Sends [mode]/[pid] and returns [expectedLength] data bytes, or null.
     *
     * Null covers every failure the caller must not paper over: no session, no answer, a
     * negative response, or a frame too short to hold the payload. A readiness report
     * built from guessed bytes would be worse than no report.
     */
    fun request(mode: Int, pid: Int, expectedLength: Int): ByteArray? {
        val activePort = port ?: return null
        val activeFormat = format ?: return null

        return try {
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            val frame = Obd2Frame.buildRequest(mode, pid, activeFormat)
            Timber.tag(TAG).d("-> ${Obd2Frame.toHex(frame)}")
            activePort.write(frame, WRITE_TIMEOUT_MS)

            val raw = drainResponse(activePort)
            Timber.tag(TAG).d("<- ${Obd2Frame.toHex(raw)}")

            if (Obd2Frame.isNegativeResponse(raw, mode)) {
                Timber.tag(TAG).w("ECU returned a negative response to mode $mode PID $pid")
                return null
            }
            Obd2Frame.extractData(raw, mode, pid, expectedLength)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "OBD-II request failed")
            null
        }
    }

    /**
     * Result of one probe, kept as close to the wire as possible.
     *
     * Both the frame sent and everything heard back are retained verbatim: on an
     * undocumented pre-CAN map the bytes we could not interpret are exactly the ones worth
     * keeping, and a dump that stores only what today's code understands cannot answer
     * tomorrow's question.
     */
    data class ProbeResult(
        val requestHex: String,
        val rawHex: String,
        val dataHex: String?,
        val negative: Boolean
    )

    /**
     * Issues [mode]/[pid] and returns the exchange without interpreting the payload.
     *
     * Never returns null for "no data": a probe that got a negative response, or an empty
     * one, is itself a finding. Null means the request could not be sent at all.
     */
    fun probe(mode: Int, pid: Int): ProbeResult? {
        val activePort = port ?: return null
        val activeFormat = format ?: return null

        return try {
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            val frame = Obd2Frame.buildRequest(mode, pid, activeFormat)
            activePort.write(frame, WRITE_TIMEOUT_MS)
            val raw = drainResponse(activePort)

            val negative = Obd2Frame.isNegativeResponse(raw, mode)
            val data = if (negative) null else Obd2Frame.extractRaw(raw, mode, pid)
            Timber.tag(TAG).i(
                "probe %02X%02X -> %s%s".format(
                    mode, pid, Obd2Frame.toHex(raw), if (negative) " (negative)" else ""
                )
            )
            ProbeResult(
                requestHex = Obd2Frame.toHex(frame),
                rawHex = Obd2Frame.toHex(raw),
                dataHex = data?.let { Obd2Frame.toHex(it) },
                negative = negative
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Probe %02X%02X failed".format(mode, pid))
            null
        }
    }

    /** Which init the ECU actually answered — the first thing to know when a dump is odd. */
    fun negotiatedFormat(): Obd2Frame.Format? = format

    /** Reads Mode $01 PID $01 and decodes it. Null if the read failed. */
    fun readReadiness(): ObdReadiness.Report? {
        val data = request(ObdReadiness.MODE, ObdReadiness.PID, ObdReadiness.DATA_LENGTH)
            ?: return null
        return try {
            ObdReadiness.decode(data)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Could not decode readiness payload")
            null
        }
    }

    /**
     * Collects bytes until the bus goes quiet.
     *
     * K-line responses arrive byte by byte with gaps, so a single read() typically returns
     * a fragment. This keeps reading until nothing new has arrived for [RESPONSE_SETTLE_MS],
     * capped by [RESPONSE_TOTAL_TIMEOUT_MS] so a chattering bus cannot hang the caller.
     */
    private fun drainResponse(activePort: UsbSerialPort): ByteArray {
        val collected = mutableListOf<Byte>()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + RESPONSE_TOTAL_TIMEOUT_MS
        var lastByteAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val read = try {
                activePort.read(buffer, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Read failed while draining response")
                break
            }
            if (read > 0) {
                for (i in 0 until read) collected.add(buffer[i])
                lastByteAt = System.currentTimeMillis()
            } else if (collected.isNotEmpty() &&
                System.currentTimeMillis() - lastByteAt > RESPONSE_SETTLE_MS
            ) {
                break
            }
        }
        return collected.toByteArray()
    }

    /**
     * Ends the KWP2000 session with StopCommunication (service 0x82).
     *
     * **This is not optional.** A KWP2000 ECU that was never told the session ended stays
     * in it: it ignores SSM entirely, and it ignores a fresh StartCommunication too, so
     * both the gauges and any later OBD-II request are dead until the key is cycled.
     * Observed on the car 2026-08-30 -- readiness read fine, then SSM returned nothing but
     * its own echo for the following two minutes and the next dump could not init.
     *
     * ISO 9141-2 has no equivalent request; that session ends by bus timeout, so this is a
     * no-op for it.
     */
    private fun stopCommunication() {
        val activePort = port ?: return
        if (format != Obd2Frame.Format.KWP2000) return

        try {
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            // C1 33 F1 82 + checksum -- same shape as StartCommunication, service 0x82.
            val request = byteArrayOf(0xC1.toByte(), 0x33, 0xF1.toByte(), 0x82.toByte())
            val frame = request + Obd2Frame.checksum(request).toByte()
            activePort.write(frame, WRITE_TIMEOUT_MS)
            val response = drainResponse(activePort)
            Timber.tag(TAG).i("StopCommunication response: ${Obd2Frame.toHex(response)}")
        } catch (e: Exception) {
            // Best effort: if this fails the ECU is left in-session, which the caller's
            // settle delay and SSM retries then have to survive.
            Timber.tag(TAG).w(e, "StopCommunication failed; ECU may still hold the session")
        }
    }

    /**
     * Ends the session and closes the port so SSM can reclaim the wire.
     *
     * Always ends the session first -- see [stopCommunication] for why simply closing the
     * port is not enough.
     */
    fun disconnect() {
        stopCommunication()
        port?.let { closeQuietly(it) }
        port = null
        format = null
    }

    private fun closeQuietly(target: UsbSerialPort) {
        try {
            target.setBreak(false)
        } catch (e: Exception) {
            Timber.tag(TAG).d("Could not clear break on close: ${e.message}")
        }
        try {
            target.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing OBD-II port")
        }
    }
}
