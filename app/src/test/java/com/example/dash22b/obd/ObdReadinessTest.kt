package com.example.dash22b.obd

import com.example.dash22b.obd.ObdReadiness.Monitor
import com.example.dash22b.obd.ObdReadiness.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdReadinessTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * The real car, 2026-08-30, as printed by the Accessport's I/M Readiness screen:
     * MIL off, 0 DTCs, everything READY except Heated Catalyst / Secondary Air /
     * A/C refrigerant / EGR, which are NA. Those four are exactly the ones the FSM
     * (EN(STi)(diag)-30) lists as "No support" on this ECU.
     *
     * Byte C supported = catalyst(0) + evap(2) + O2(5) + O2 heater(6) = 0x65
     * Byte D incomplete = none = 0x00
     * Byte B = misfire/fuel/components supported, none incomplete = 0x07
     */
    @Test
    fun `decodes the real 22B readiness capture`() {
        val report = ObdReadiness.decode(bytes(0x00, 0x07, 0x65, 0x00))

        assertFalse(report.milOn)
        assertEquals(0, report.dtcCount)
        assertFalse(report.compressionIgnition)

        listOf(
            Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS,
            Monitor.CATALYST, Monitor.EVAPORATIVE,
            Monitor.OXYGEN_SENSOR, Monitor.OXYGEN_SENSOR_HEATER
        ).forEach { assertEquals(it.label, Status.READY, report.statuses[it]) }

        listOf(
            Monitor.HEATED_CATALYST, Monitor.SECONDARY_AIR,
            Monitor.AC_REFRIGERANT, Monitor.EGR
        ).forEach { assertEquals(it.label, Status.NOT_SUPPORTED, report.statuses[it]) }

        assertTrue(report.incomplete.isEmpty())
        assertEquals(4, report.unsupported.size)
        assertTrue(report.passesCaliforniaReadiness)
    }

    @Test
    fun `monitor order matches the PID 01 bit order`() {
        assertEquals(
            listOf(
                "Misfire", "Fuel System", "Comprehensive Component",
                "Catalyst", "Heated Catalyst", "Evaporative System", "Secondary Air System",
                "A/C system refrigerant", "Oxygen Sensor", "Oxygen Sensor heater", "EGR system"
            ),
            Monitor.entries.map { it.label }
        )
    }

    @Test
    fun `MIL bit and DTC count share byte A`() {
        val on = ObdReadiness.decode(bytes(0x83, 0x00, 0x00, 0x00))
        assertTrue(on.milOn)
        assertEquals(3, on.dtcCount)

        val off = ObdReadiness.decode(bytes(0x03, 0x00, 0x00, 0x00))
        assertFalse(off.milOn)
        assertEquals(3, off.dtcCount)
    }

    @Test
    fun `a set bit in byte D means incomplete, not ready`() {
        // Catalyst + EVAP supported; EVAP flagged incomplete.
        val report = ObdReadiness.decode(bytes(0x00, 0x07, 0x05, 0x04))

        assertEquals(Status.READY, report.statuses[Monitor.CATALYST])
        assertEquals(Status.INCOMPLETE, report.statuses[Monitor.EVAPORATIVE])
        assertEquals(listOf(Monitor.EVAPORATIVE), report.incomplete)
    }

    @Test
    fun `continuous monitors take their incomplete flags from the high nibble of byte B`() {
        // All three supported (0x07), fuel system incomplete (bit 5 = 0x20).
        val report = ObdReadiness.decode(bytes(0x00, 0x27, 0x00, 0x00))

        assertEquals(Status.READY, report.statuses[Monitor.MISFIRE])
        assertEquals(Status.INCOMPLETE, report.statuses[Monitor.FUEL_SYSTEM])
        assertEquals(Status.READY, report.statuses[Monitor.COMPONENTS])
    }

    @Test
    fun `unsupported beats incomplete when both bits are set`() {
        // Byte C says catalyst unsupported, byte D claims it incomplete. Unsupported wins:
        // a monitor the ECU does not implement cannot be "waiting to run".
        val report = ObdReadiness.decode(bytes(0x00, 0x00, 0x00, 0x01))

        assertEquals(Status.NOT_SUPPORTED, report.statuses[Monitor.CATALYST])
        assertTrue(report.incomplete.isEmpty())
    }

    @Test
    fun `compression ignition flag is byte B bit 3`() {
        assertTrue(ObdReadiness.decode(bytes(0x00, 0x08, 0x00, 0x00)).compressionIgnition)
        assertFalse(ObdReadiness.decode(bytes(0x00, 0x07, 0x00, 0x00)).compressionIgnition)
    }

    @Test
    fun `California readiness excuses an incomplete EVAP monitor only`() {
        // EVAP alone incomplete -> still passes (16 CCR 3340.42.2 carve-out).
        val evapOnly = ObdReadiness.decode(bytes(0x00, 0x07, 0x65, 0x04))
        assertEquals(listOf(Monitor.EVAPORATIVE), evapOnly.incomplete)
        assertTrue(evapOnly.passesCaliforniaReadiness)

        // Catalyst incomplete -> fails, no carve-out for it.
        val catalyst = ObdReadiness.decode(bytes(0x00, 0x07, 0x65, 0x01))
        assertFalse(catalyst.passesCaliforniaReadiness)
    }

    @Test
    fun `California readiness fails on a lit MIL or a stored code even when all monitors are ready`() {
        assertFalse(ObdReadiness.decode(bytes(0x80, 0x07, 0x65, 0x00)).passesCaliforniaReadiness)
        assertFalse(ObdReadiness.decode(bytes(0x01, 0x07, 0x65, 0x00)).passesCaliforniaReadiness)
    }

    @Test
    fun `every monitor unsupported decodes cleanly`() {
        val report = ObdReadiness.decode(bytes(0x00, 0x00, 0x00, 0x00))

        assertEquals(11, report.unsupported.size)
        assertTrue(report.incomplete.isEmpty())
        assertTrue(report.passesCaliforniaReadiness)
    }

    @Test
    fun `extra trailing bytes are ignored`() {
        val report = ObdReadiness.decode(bytes(0x00, 0x07, 0x65, 0x00, 0xAA, 0xBB))
        assertTrue(report.passesCaliforniaReadiness)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short response is rejected rather than silently misread`() {
        ObdReadiness.decode(bytes(0x00, 0x07, 0x65))
    }
}
