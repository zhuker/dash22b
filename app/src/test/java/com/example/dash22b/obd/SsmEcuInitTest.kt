package com.example.dash22b.obd

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.max

class SsmEcuInitTest {
    @Test
    fun testHardcodedEcuInit() {
        val ecuInit = SsmEcuInit.createHardcoded()

        // Verify ROM ID extraction works
        // Note: With fixed offset 8 logic, ROM ID is 3D12594006, not A210113D12
        val romId = ecuInit.getRomId()
        println("ROM ID: $romId")
        assertEquals("3D12594006", romId)

        // Print all bytes for analysis
        println("\nFull ECU init response bytes:")
        val initBytes = ecuInit.getPacket().toBytes()
        for (i in initBytes.indices) {
            val byte = initBytes[i].toInt().and(0xFF)
            val binary = byte.toString(2).padStart(8, '0')
            println(
                "  Byte $i (${max(0, i - 4)}): 0x${
                    byte.toString(16).padStart(2, '0').uppercase()
                } = $binary"
            )
        }

        // Analyze key capability bytes based on XML ecubyteindex values
        println("\n\nCapability analysis for common parameters:")
        // Note: Indices in raw packet are PAYLOAD_OFFSET (5) + ecubyteindex? No, logic changed.
        // But initBytes here is the raw packet from toBytes().
        // SsmEcuInit logic internal details changed, but verification here is on raw bytes.

        println("XML says P2 Coolant is at ecubyteindex=8")
        // In raw packet: Header(4) + Mark(1) + Payload...
        // Data[0]=FF (Raw 4). 
        // ROM ID is at Data[4] (Raw 8).
        // SsmEcuInit.isParameterSupported uses: actualIndex = 1 + ecuByteIndex (index into Data)
        // Raw Index = 4 (Header) + 1 + ecuByteIndex = 5 + ecuByteIndex.
        // So raw index IS 5 + 8 = 13.

        val byte8 = initBytes[13].toInt().and(0xFF) // 5 + 8
        println(
            "  Raw Byte 13 (ecubyteindex 8) = 0x${
                byte8.toString(16).uppercase()
            } = ${byte8.toString(2).padStart(8, '0')}"
        )

        println("\nXML says P9 Vehicle Speed is at ecubyteindex=9")
        val byte9 = initBytes[14].toInt().and(0xFF) // 5 + 9
        println(
            "  Raw Byte 14 (ecubyteindex 9) = 0x${
                byte9.toString(16).uppercase()
            } = ${byte9.toString(2).padStart(8, '0')}"
        )
    }
}
