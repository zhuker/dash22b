package com.example.dash22b.data

import com.example.dash22b.obd.ObdReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessMessageTest {

    private fun report(vararg data: Int) =
        ObdReadiness.decode(ByteArray(data.size) { data[it].toByte() })

    /** The real car: MIL off, 0 DTCs, four monitors unsupported, nothing incomplete. */
    private fun cleanSweep() = report(0x00, 0x07, 0x65, 0x00)

    @Test
    fun `clean sweep reports a pass and names the unsupported count`() {
        val text = ReadinessMessage.format(cleanSweep())

        assertTrue(text, text.contains("MIL: Off"))
        assertTrue(text, text.contains("Stored DTCs: 0"))
        assertTrue(text, text.contains("Clears the CA OBD-II readiness check"))
        assertTrue(text, text.contains("4 monitors not supported"))
        assertFalse(text, text.contains("FAIL"))
    }

    @Test
    fun `every monitor is listed, in PID 01 bit order`() {
        val text = ReadinessMessage.format(cleanSweep())

        val order = ObdReadiness.Monitor.entries.map { it.label }
        var cursor = 0
        order.forEach { label ->
            val at = text.indexOf("$label:", cursor)
            assertTrue("$label missing or out of order", at >= 0)
            cursor = at
        }
    }

    @Test
    fun `unsupported monitors render as NA, matching the Accessport`() {
        val text = ReadinessMessage.format(cleanSweep())

        assertTrue(text, text.contains("Heated Catalyst: NA"))
        assertTrue(text, text.contains("Secondary Air System: NA"))
        assertTrue(text, text.contains("A/C system refrigerant: NA"))
        assertTrue(text, text.contains("EGR system: NA"))
        assertTrue(text, text.contains("Catalyst: READY"))
    }

    @Test
    fun `an incomplete monitor is named in the failure verdict`() {
        // Catalyst supported and incomplete.
        val text = ReadinessMessage.format(report(0x00, 0x07, 0x65, 0x01))

        assertTrue(text, text.contains("Catalyst: NOT READY"))
        assertTrue(text, text.contains("Would FAIL smog"))
        assertTrue(text, text.contains("Catalyst is not complete"))
    }

    @Test
    fun `several incomplete monitors are pluralised and all named`() {
        // Catalyst (bit 0) and Oxygen Sensor (bit 5) incomplete.
        val text = ReadinessMessage.format(report(0x00, 0x07, 0x65, 0x21))

        assertTrue(text, text.contains("Would FAIL smog"))
        assertTrue(text, text.contains("Catalyst"))
        assertTrue(text, text.contains("Oxygen Sensor"))
        assertTrue(text, text.contains("are not complete"))
    }

    @Test
    fun `an incomplete EVAP monitor still passes, and says why`() {
        val text = ReadinessMessage.format(report(0x00, 0x07, 0x65, 0x04))

        assertTrue(text, text.contains("Evaporative System: NOT READY"))
        assertTrue(text, text.contains("Clears the CA OBD-II readiness check"))
        assertTrue(text, text.contains("California excuses that one"))
    }

    @Test
    fun `a lit MIL fails even with every monitor ready`() {
        val text = ReadinessMessage.format(report(0x80, 0x07, 0x65, 0x00))

        assertTrue(text, text.contains("MIL: ON"))
        assertTrue(text, text.contains("Would FAIL smog"))
        assertTrue(text, text.contains("lit MIL or a stored code"))
    }

    @Test
    fun `a stored code fails even with the MIL off`() {
        val text = ReadinessMessage.format(report(0x02, 0x07, 0x65, 0x00))

        assertTrue(text, text.contains("Stored DTCs: 2"))
        assertTrue(text, text.contains("Would FAIL smog"))
    }

    @Test
    fun `every report carries the Cal ID caveat, since readiness is only half the test`() {
        listOf(
            cleanSweep(),
            report(0x80, 0x07, 0x65, 0x00),
            report(0x00, 0x07, 0x65, 0x01)
        ).forEach { r ->
            assertTrue(ReadinessMessage.format(r).contains("Cal ID/CVN"))
        }
    }
}
