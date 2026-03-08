package com.example.dash22b.obd

/**
 * Represents a Diagnostic Trouble Code (DTC) definition from the SSM logger XML.
 * Each DTC has both a temporary (current) and memorized (stored) address/bit.
 */
data class SsmDtcCode(
    val id: String,           // e.g., "D1"
    val name: String,         // e.g., "P0335 - Crankshaft Position Sensor A Circuit"
    val tmpAddr: Int,         // Temporary (current) DTC address
    val memAddr: Int,         // Memorized (stored) DTC address
    val bit: Int,             // Bit position within the byte (0-7)
    val isTemporary: Boolean = false,  // Set after reading from ECU
    val isMemorized: Boolean = false   // Set after reading from ECU
) {
    /** Extract just the DTC code (e.g., "P0335") from the name */
    val code: String
        get() = name.substringBefore(" - ").substringBefore(" ").trim()

    /** Extract the description (e.g., "Crankshaft Position Sensor A Circuit") */
    val description: String
        get() {
            val parts = name.split(" - ", limit = 2)
            return if (parts.size > 1) parts[1].trim() else name
        }
}
