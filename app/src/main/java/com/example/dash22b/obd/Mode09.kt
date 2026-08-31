package com.example.dash22b.obd

/**
 * Decoder for generic OBD-II Mode $09 vehicle information — VIN, calibration IDs and CVNs.
 *
 * Each item arrives as several KWP messages of `49 <PID> <index> <4 bytes>`, so the value
 * is the four-byte chunks reassembled in index order. Text items are ASCII with null
 * padding; CVNs are opaque 4-byte numbers and stay hex.
 *
 * These matter beyond curiosity: since July 2020 a California Smog Check compares the Cal
 * ID and CVN against the expected calibration, and a non-stock tune fails on that
 * criterion regardless of readiness monitors. Reading them from the car is how "it's back
 * to stock" becomes checkable rather than asserted.
 */
object Mode09 {

    const val PID_VIN = 0x02
    const val PID_CAL_ID = 0x04
    const val PID_CVN = 0x06

    /** Reassembles the 4-byte chunks for [pid], in index order. */
    fun payload(raw: ByteArray, pid: Int): ByteArray {
        val chunks = Obd2Frame.responseFrames(raw, 0x09, pid)
            .filter { it.size >= 5 }
            .map { (it[0].toInt() and 0xFF) to it.copyOfRange(1, 5) }
            .sortedBy { it.first }
        return chunks.fold(ByteArray(0)) { acc, (_, bytes) -> acc + bytes }
    }

    /**
     * ASCII text for [pid], with padding removed.
     *
     * Leading NULs are dropped as well as trailing ones: the VIN arrives right-aligned in
     * its chunks, so the first message carries three padding bytes before the first
     * character.
     */
    fun text(raw: ByteArray, pid: Int): String =
        payload(raw, pid)
            .map { it.toInt() and 0xFF }
            .filter { it != 0x00 }
            .map { it.toChar() }
            .joinToString("")
            .trim()

    /**
     * Calibration IDs, split into the fixed 16-byte slots the ECU reports.
     *
     * Split rather than concatenated because the count matters: this ECU carries two, and
     * each pairs with one CVN in the same order.
     */
    fun calibrationIds(raw: ByteArray): List<String> =
        payload(raw, PID_CAL_ID)
            .toList()
            .chunked(CAL_ID_LENGTH)
            .map { slot ->
                slot.filter { it.toInt() != 0 }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
            }
            .filter { it.isNotBlank() }

    /** Calibration verification numbers as hex, one per calibration ID. */
    fun calibrationVerificationNumbers(raw: ByteArray): List<String> =
        payload(raw, PID_CVN)
            .toList()
            .chunked(CVN_LENGTH)
            .filter { it.size == CVN_LENGTH }
            .map { chunk -> chunk.joinToString("") { "%02X".format(it.toInt() and 0xFF) } }

    private const val CAL_ID_LENGTH = 16
    private const val CVN_LENGTH = 4
}
