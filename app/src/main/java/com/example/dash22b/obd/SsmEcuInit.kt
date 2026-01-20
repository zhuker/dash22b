package com.example.dash22b.obd

import timber.log.Timber

/**
 * SSM ECU initialization response data.
 * Contains the raw init response bytes and provides methods for:
 * - Extracting ECU ROM ID
 * - Checking capability bits to determine supported parameters
 *
 * Init Response Structure:
 * [0x80][dst][src][len][0xFF][romid 5 bytes][capability bytes...][checksum]
 *    0    1    2    3    4      5-9              10+
 *
 * The XML ecubyteindex values are relative to the data portion (after byte 4).
 * So ecubyteindex=8 means byte 5+8=13 in the full packet.
 */
class SsmEcuInit(private val packet: SsmPacket) {

    /**
     * Extract the 5-byte ROM ID from the init response.
     * With SsmPacket, data starts at the 0xFF marker (element 4 of raw packet).
     * Raw Config matches:
     * Full: [80] [F0] [10] [39] [FF] [R1] [R2] [R3] [R4] [R5] [C1] ...
     * Pkt.Data:                 [0]  [1]  [2]  [3]  [4]  [5]  [6]
     *                            FF   R1   R2 ...
     *
     * WAIT! Previous analysis showed ROM ID is at raw offset **8**.
     * [80 F0 10 39 FF A2 10 11 3D 12 59 40 06 ...]
     *  0  1  2  3  4  5  6  7  8  9  10 11 12
     *
     * 0xFF is at index 4.
     * ROM ID (3D12594006) starts at index 8.
     * So offsets 5, 6, 7 are padding/flags? (A2 10 11)
     *
     * Packet.data[0] = 0xFF (raw 4)
     * Packet.data[1] = A2   (raw 5)
     * Packet.data[2] = 10   (raw 6)
     * Packet.data[3] = 11   (raw 7)
     * Packet.data[4] = 3D   (raw 8) -> ROM ID Start
     *
     * So ROM ID is at packet.data[4..8].
     *
     * @return ROM ID as uppercase hex string
     */
    fun getRomId(): String {
        if (packet.data.size < 9) {
            throw IllegalStateException("Init response too short to contain ROM ID")
        }

        val romIdBytes = ByteArray(5)
        System.arraycopy(packet.data, 4, romIdBytes, 0, 5)

        return romIdBytes.joinToString("") { byte ->
            String.format("%02X", byte.toInt() and 0xFF)
        }
    }

    /**
     * Check if a parameter is supported by this ECU based on capability bits.
     *
     * XML ecubyteindex=8 -> Raw packet index 13.
     * Raw index 13 = Packet.data[9]. (13 - 4 = 9)
     * So internal offset is 1?
     *
     * Math:
     * Packet.data[0] = Raw[4] (0xFF)
     * Target: Raw[13]
     * 13 - 4 = 9.
     *
     * ecubyteindex = 8.
     * So we need 8 + X = 9
     * X = 1.
     *
     * So actual index in packet.data = 1 + ecubyteIndex.
     *
     * @param ecuByteIndex The byte index as specified in XML
     * @param ecuBit The bit position (0-7) within that byte to check
     * @return true if the capability bit is set, false otherwise
     */
    fun isParameterSupported(ecuByteIndex: Int, ecuBit: Int): Boolean {
        // Offset relative to packet.data (which starts at 0xFF)
        // We verified above that offset is 1 + ecuByteIndex
        val actualIndex = 1 + ecuByteIndex

        if (actualIndex < 0 || actualIndex >= packet.data.size) {
//            Timber.d("isParameterSupported false - invalid index: $actualIndex for ecuByteIndex: $ecuByteIndex and ecuBit $ecuBit packet data len: ${packet.data.size}")
            return false
        }

        val byte = packet.data[actualIndex].toInt() and 0xFF
        val bitMask = 1 shl ecuBit
        return (byte and bitMask) != 0
    }

    /**
     * Get the underlying SsmPacket
     */
    fun getPacket(): SsmPacket = packet

    companion object {
        /**
         * Create a hardcoded EcuInit using actual captured ECU init response.
         *
         * @return SsmEcuInit with actual ECU capability bits for ROM ID 3D12594006
         */
        fun createHardcoded(): SsmEcuInit {
            // Actual ECU init response
            val initBytes = byteArrayOf(
                0x80.toByte(), 0xF0.toByte(), 0x10.toByte(), 0x39.toByte(), // Header
                0xFF.toByte(),                                               // Init marker
                0xA2.toByte(), 0x10.toByte(), 0x11.toByte(), 0x3D.toByte(), 0x12.toByte(), // ROM ID
                0x59.toByte(), 0x40.toByte(), 0x06.toByte(), 0x73.toByte(), 0xFA.toByte(), // Cap bytes
                0xCB.toByte(), 0xA6.toByte(), 0x2B.toByte(), 0x81.toByte(), 0xFE.toByte(),
                0xA8.toByte(), 0x00.toByte(), 0x82.toByte(), 0x00.toByte(), 0x60.toByte(),
                0xCE.toByte(), 0x54.toByte(), 0xF8.toByte(), 0xB1.toByte(), 0xE4.toByte(),
                0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xDC.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x75.toByte(), 0x1E.toByte(), 0x30.toByte(), 0xC0.toByte(),
                0xF0.toByte(), 0x22.toByte(), 0x00.toByte(), 0x00.toByte(), 0x43.toByte(),
                0xFB.toByte(), 0x00.toByte(), 0xF1.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0xF0.toByte(), 0x3A.toByte()                                 // Checksum
            )

            // Parse via SsmPacket first
            val packet = SsmPacket.fromBytes(initBytes)
                ?: throw IllegalStateException("Failed to parse hardcoded init bytes")

            return SsmEcuInit(packet)
        }
    }
}
