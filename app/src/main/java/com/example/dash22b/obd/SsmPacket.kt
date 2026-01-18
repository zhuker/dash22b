package com.example.dash22b.obd

/**
 * SSM (Subaru Select Monitor) protocol packet implementation.
 * Based on PiMonitor Python implementation.
 * 
 * Packet format: [0x80, destination, source, data_length, data..., checksum]
 * - Header: 0x80
 * - Destination: 0x10 (ECU), 0x18 (TCU)
 * - Source: 0xF0 (diagnostic tool)
 * - Data length: number of data bytes
 * - Checksum: (sum of all bytes) & 0xFF
 */
data class SsmPacket(
    val destination: Int,
    val source: Int,
    val data: ByteArray
) {
    companion object {
        const val HEADER: Byte = 0x80.toByte()
        const val DESTINATION_ECU: Int = 0x10
        const val DESTINATION_TCU: Int = 0x18
        const val SOURCE_DIAG: Int = 0xF0
        const val CMD_INIT: Byte = 0xBF.toByte()
        
        /**
         * Creates an ECU/TCU init request packet.
         * @param target 1 for ECU, 2 for TCU
         */
        fun createInitPacket(target: Int = 1): SsmPacket {
            val destination = if (target == 2) DESTINATION_TCU else DESTINATION_ECU
            return SsmPacket(
                destination = destination,
                source = SOURCE_DIAG,
                data = byteArrayOf(CMD_INIT)
            )
        }
        
        /**
         * Parses a response packet from raw bytes.
         * @return SsmPacket if valid, null if invalid
         */
        fun fromBytes(bytes: ByteArray): SsmPacket? {
            if (bytes.size < 5) return null
            if (bytes[0] != HEADER) return null
            
            val dataLen = bytes[3].toInt() and 0xFF
            val expectedLen = 5 + dataLen
            if (bytes.size < expectedLen) return null
            
            // Verify checksum
            var checksum = 0
            for (i in 0 until bytes.size - 1) {
                checksum = (checksum + (bytes[i].toInt() and 0xFF)) and 0xFF
            }
            val receivedChecksum = bytes[bytes.size - 1].toInt() and 0xFF
            if (checksum != receivedChecksum) return null
            
            val data = bytes.copyOfRange(4, 4 + dataLen)
            return SsmPacket(
                destination = bytes[1].toInt() and 0xFF,
                source = bytes[2].toInt() and 0xFF,
                data = data
            )
        }
    }
    
    /**
     * Serializes the packet to bytes for transmission.
     */
    fun toBytes(): ByteArray {
        val packet = ByteArray(5 + data.size)
        packet[0] = HEADER
        packet[1] = destination.toByte()
        packet[2] = source.toByte()
        packet[3] = data.size.toByte()
        data.copyInto(packet, 4)
        
        // Calculate checksum
        var checksum = 0
        for (i in 0 until packet.size - 1) {
            checksum = (checksum + (packet[i].toInt() and 0xFF)) and 0xFF
        }
        packet[packet.size - 1] = checksum.toByte()
        
        return packet
    }
    
    /**
     * Returns hex string representation for logging.
     */
    fun toHexString(): String {
        return toBytes().joinToString(" ") { String.format("%02X", it) }
    }
    
    /**
     * For init response, extracts the ECU ROM ID (bytes 5-9 of data).
     */
    fun getRomId(): String? {
        // Init response data: [0xFF, romId(5 bytes), capabilities...]
        if (data.size < 6 || data[0] != 0xFF.toByte()) return null
        return data.copyOfRange(1, 6).joinToString("") { String.format("%02X", it) }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SsmPacket
        return destination == other.destination && 
               source == other.source && 
               data.contentEquals(other.data)
    }
    
    override fun hashCode(): Int {
        var result = destination
        result = 31 * result + source
        result = 31 * result + data.contentHashCode()
        return result
    }
}
