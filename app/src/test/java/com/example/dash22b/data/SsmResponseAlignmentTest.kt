package com.example.dash22b.data

import com.example.dash22b.obd.SsmPacket
import com.example.dash22b.obd.SsmParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SSM read response is positional -- values arrive in request order with nothing
 * identifying them -- so a parameter that fails to parse must still consume its bytes.
 * Otherwise every later parameter in the row decodes from the wrong offset and reaches
 * the graphs and the CSV as a plausible-looking wrong number.
 */
class SsmResponseAlignmentTest {

    private fun param(name: String, length: Int, expr: String = "x") = SsmParameter(
        id = name,
        name = name,
        address = 0x000000,
        length = length,
        expression = expr,
        unit = DisplayUnit.UNKNOWN
    )

    private fun response(vararg values: Int) = SsmPacket(
        destination = 0x10,
        source = 0xF0,
        data = byteArrayOf(SsmPacket.RSP_READ_ADDRESS, *values.map { it.toByte() }.toByteArray())
    )

    @Test
    fun `a mid-row parse failure does not shift later parameters`() {
        // Length 3 is unsupported by parseValue, so the middle parameter throws.
        val params = listOf(
            param("First", 1),
            param("Broken", 3),
            param("Last", 1)
        )
        // First=100, Broken consumes 3 bytes, Last=80
        val packet = response(100, 0xAA, 0xBB, 0xCC, 80)

        val data = SsmDataSource.parseResponse(packet, params)
        assertNotNull(data)

        assertEquals(100f, data!!.values["First"]?.value)
        assertNull("the failing parameter must be dropped", data.values["Broken"])
        // Before the fix this read 0xAA (170) -- the first byte of the failed
        // parameter -- because the offset never advanced past it.
        assertEquals(80f, data.values["Last"]?.value)
    }

    @Test
    fun `a truncated response drops the tail but keeps what arrived`() {
        val params = listOf(param("First", 1), param("Second", 2), param("Third", 1))
        val packet = response(10, 0x00) // Second is cut short, Third never arrives

        val data = SsmDataSource.parseResponse(packet, params)
        assertNotNull(data)

        assertEquals(10f, data!!.values["First"]?.value)
        assertNull(data.values["Second"])
        assertNull(data.values["Third"])
    }

    @Test
    fun `a clean row decodes every parameter at its own offset`() {
        val params = listOf(param("A", 1), param("B", 2), param("C", 1))
        val packet = response(1, 0x01, 0x02, 3)

        val data = SsmDataSource.parseResponse(packet, params)!!

        assertEquals(1f, data.values["A"]?.value)
        assertEquals(258f, data.values["B"]?.value)  // 0x0102
        assertEquals(3f, data.values["C"]?.value)
    }
}
