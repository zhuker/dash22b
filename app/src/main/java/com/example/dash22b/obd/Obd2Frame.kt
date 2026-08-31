package com.example.dash22b.obd

/**
 * Frame building and parsing for generic OBD-II over the K-line.
 *
 * Two wire formats show up on a pre-CAN Subaru, and they differ only in the header:
 *
 *  - **ISO 9141-2** — fixed header `68 6A F1`, established by a 5-baud slow init.
 *  - **ISO 14230-4 (KWP2000)** — header `[0xC0 or length][target][source]`, established
 *    by either a fast init (25 ms break) or the same slow init.
 *
 * Both then carry the same service bytes (mode, PID) and both end in a single-byte
 * checksum that is the plain 8-bit sum of everything before it. Keeping this class free
 * of IO means the framing can be unit-tested without a car attached, which matters here
 * because the car is the one thing that cannot be mocked.
 */
object Obd2Frame {

    /** Tester (scan tool) address. */
    const val SOURCE_TESTER: Int = 0xF1

    /** Physical address of the engine ECU on the K-line. */
    const val TARGET_ECU: Int = 0x33

    /** ISO 9141-2's fixed three-byte header. */
    val ISO9141_HEADER = intArrayOf(0x68, 0x6A, SOURCE_TESTER)

    /** Which header style to use; decided by whichever init succeeded. */
    enum class Format { ISO9141, KWP2000 }

    /** 8-bit sum of every preceding byte — the checksum both formats use. */
    fun checksum(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var sum = 0
        for (i in from until until) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    /**
     * Builds a request frame for [mode] / [pid].
     *
     * KWP2000's format byte encodes the payload length in its low six bits with the two
     * high bits marking "address information included" — for a two-byte service request
     * that is 0xC2.
     */
    fun buildRequest(mode: Int, pid: Int, format: Format): ByteArray {
        val payload = intArrayOf(mode, pid)
        val header = when (format) {
            Format.ISO9141 -> ISO9141_HEADER
            Format.KWP2000 -> intArrayOf(0xC0 or payload.size, TARGET_ECU, SOURCE_TESTER)
        }
        val frame = ByteArray(header.size + payload.size + 1)
        var i = 0
        header.forEach { frame[i++] = it.toByte() }
        payload.forEach { frame[i++] = it.toByte() }
        frame[i] = checksum(frame, 0, i).toByte()
        return frame
    }

    /** A positive response to [mode] carries mode + 0x40. */
    fun positiveResponseMode(mode: Int): Int = mode + 0x40

    /**
     * Extracts the data bytes of a positive response to [mode]/[pid] from [raw].
     *
     * [raw] is everything the cable handed back, which on a K-line includes **our own
     * echo** — the interface is a single wire and hears itself transmit. Rather than
     * assume a fixed echo length, this scans for the response signature (`mode+0x40`
     * followed by `pid`) and takes what follows, which is robust to a partial echo, to
     * leading noise from the init, and to the ECU's inter-byte timing.
     *
     * @return the data bytes after the mode/PID echo, or null if no positive response is
     *   present. A negative response (`0x7F`) also returns null — the caller reports the
     *   read as failed rather than inventing monitor states.
     */
    fun extractData(raw: ByteArray, mode: Int, pid: Int, expectedLength: Int): ByteArray? {
        val responseMode = positiveResponseMode(mode)
        var i = 0
        while (i + 1 < raw.size) {
            val isResponse = (raw[i].toInt() and 0xFF) == responseMode &&
                (raw[i + 1].toInt() and 0xFF) == pid
            if (isResponse) {
                val start = i + 2
                val available = raw.size - start
                // The trailing byte is the frame checksum, not data; only trust a frame
                // that carries the full payload we asked for.
                if (available < expectedLength) return null
                return raw.copyOfRange(start, start + expectedLength)
            }
            i++
        }
        return null
    }

    /**
     * Like [extractData] but takes **everything** after the mode/PID echo instead of a
     * fixed slice.
     *
     * This is the discovery path. Mode $06 record layout on a pre-CAN ECU is
     * manufacturer-defined and not published by Subaru, so there is no length to ask for;
     * demanding one would return null for every TID and read as "not supported" when the
     * truth is "we do not know how long the answer is yet". The trailing checksum byte is
     * left in deliberately — until the record layout is known, dropping a byte on the
     * assumption it is a checksum could be dropping data.
     *
     * @return every byte following the `mode+0x40, pid` pair, or null if that pair is absent.
     */
    fun extractRaw(raw: ByteArray, mode: Int, pid: Int): ByteArray? {
        val responseMode = positiveResponseMode(mode)
        var i = 0
        while (i + 1 < raw.size) {
            if ((raw[i].toInt() and 0xFF) == responseMode &&
                (raw[i + 1].toInt() and 0xFF) == pid
            ) {
                return raw.copyOfRange(i + 2, raw.size)
            }
            i++
        }
        return null
    }

    /** True if [raw] contains a negative-response frame (0x7F) for [mode]. */
    fun isNegativeResponse(raw: ByteArray, mode: Int): Boolean {
        for (i in 0 until raw.size - 1) {
            if ((raw[i].toInt() and 0xFF) == 0x7F && (raw[i + 1].toInt() and 0xFF) == mode) {
                return true
            }
        }
        return false
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
