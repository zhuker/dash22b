package com.example.dash22b.data

import com.example.dash22b.TestHelper
import com.example.dash22b.obd.SsmEcuInit
import com.example.dash22b.obd.SsmLoggerDefinitionParser
import com.example.dash22b.obd.SsmPacket
import com.example.dash22b.obd.SsmParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class SsmDataSourceTest {

    private val delta = 0.001f

    @Test
    fun testParseResponseWithHexDump() {
        // Hex dump provided by user (request + response)
        // Request:  80 10 F0 20 A8 00 00 00 09 00 00 0A 00 00 46 FF 4B A4 FF 4B A5 FF 4B A6 FF 4B A7 00 00 0E 00 00 0F 00 00 24 A0
        // Response: 80 10 F0 20 A8 00 00 00 09 00 00 0A 00 00 46 FF 4B A4 FF 4B A5 FF 4B A6 FF 4B A7 00 00 0E 00 00 0F 00 00 24 A0 80 F0 10 0B E8 7D 7A 7F 42 0B D4 10 28 E3 4E 73
        
        val responseHex = "80 F0 10 0B E8 7D 7A 7F 42 0B D4 10 28 E3 4E 73"
        val packet = SsmPacket.fromBytes(SsmPacket.hexToBytes(responseHex))
        assertNotNull("Failed to parse SSM packet from hex dump", packet)

        val allParams = TestHelper.stiParams()

        // The parameters requested in the hex dump in order:
        // A/F Correction #1, A/F Learning #1, A/F Sensor #1, Boost Error*, Engine Speed, Manifold Relative Pressure
        val paramNames = listOf(
            "A/F Correction #1",
            "A/F Learning #1",
            "A/F Sensor #1",
            "Boost Error*",
            "Engine Speed",
            "Manifold Relative Pressure"
        )
        
        val parametersRead = paramNames.mapNotNull { name -> 
            allParams.find { it.name == name }
        }
        
        assertEquals("Should have found all 6 parameters", 6, parametersRead.size)

        // Parse the response
        val engineData = SsmDataSource.parseResponse(packet!!, parametersRead)
        assertNotNull("parseResponse returned null", engineData)

        val values = engineData!!.values
        
        // 1. A/F Correction #1: raw 0x7D (125). Expr: (x-128)*100/128. (125-128)*100/128 = -2.34375
        assertEquals(-2.34375f, values["A/F Correction #1"]?.value ?: 0f, delta)
        
        // 2. A/F Learning #1: raw 0x7A (122). Expr: (x-128)*100/128. (122-128)*100/128 = -4.6875
        assertEquals(-4.6875f, values["A/F Learning #1"]?.value ?: 0f, delta)
        
        // 3. A/F Sensor #1: raw 0x7F (127). Expr: x/128. 127/128 = 0.9921875
        assertEquals(0.9921875f, values["A/F Sensor #1"]?.value ?: 0f, delta)
        
        assertEquals(4.6605635f, values["Boost Error*"]?.value ?: 0f, delta)
        
        // 5. Engine Speed: raw 0x28 E3 (10467). Expr: x/4. 10467/4 = 2616.75
        assertEquals(2616.75f, values["Engine Speed"]?.value ?: 0f, delta)
        
        // 6. Manifold Relative Pressure: raw 0x4E (78). Expr: x-128. 78-128 = -50
        assertEquals(-50f, values["Manifold Relative Pressure"]?.value ?: 0f, delta)
    }
}
