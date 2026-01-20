package com.example.dash22b.obd

import com.example.dash22b.data.ParameterDefinition
import com.example.dash22b.data.DisplayUnit

/**
 * SSM (Subaru Select Monitor) parameter definition.
 * Represents a single ECU parameter with its address, conversion expression, and metadata.
 */
class SsmParameter(
    id: String,           // Parameter ID (e.g., "P8", "P2")
    name: String,         // Human-readable name (e.g., "Engine Speed")
    val address: Int,         // 3-byte ECU memory address as Int (e.g., 0x00000E)
    val length: Int,          // Number of bytes to read (1, 2, or 4)
    val expression: String,   // Conversion expression (e.g., "x/4", "x-40")
    unit: DisplayUnit,            // Display unit (e.g., Unit.RPM, Unit.C)
    val storageType: String? = null // Storage type (e.g., "float", "uint16")
) : ParameterDefinition(id, "float", unit, name, id, 0f, 100f, name) {
    /**
     * Parse raw bytes from SSM response into an integer or float value.
     * Handles 1-byte, 2-byte, and 4-byte big-endian values.
     *
     * @param bytes The response data byte array
     * @param offset Starting offset in the byte array
     * @return Parsed Number value
     */
    fun parseValue(bytes: ByteArray, offset: Int): Number {
        if (offset < 0 || offset + length > bytes.size) {
            throw IndexOutOfBoundsException("Cannot parse $length bytes at offset $offset from array of size ${bytes.size}")
        }

        if (storageType == "float" && length == 4) {
            val intBits = ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            return java.lang.Float.intBitsToFloat(intBits)
        }

        return when (length) {
            1 -> {
                // Single byte (unsigned)
                bytes[offset].toInt() and 0xFF
            }
            2 -> {
                // Two bytes, big-endian (most significant byte first)
                ((bytes[offset].toInt() and 0xFF) shl 8) or
                        (bytes[offset + 1].toInt() and 0xFF)
            }
            4 -> {
                // Four bytes, big-endian
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                        (bytes[offset + 3].toInt() and 0xFF)
            }
            else -> {
                throw IllegalArgumentException("Unsupported parameter length: $length (must be 1, 2, or 4)")
            }
        }
    }
}
