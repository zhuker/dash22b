package com.example.dash22b.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the split between fixed and autoscaled Y ranges: a confident parameter must
 * keep its range, and an unlisted one must return null so the graph autoscales rather
 * than silently defaulting to 0..100.
 */
class ParameterRangeTest {

    private class Def(name: String, unit: DisplayUnit) :
        ParameterDefinition(name, "float", unit, name, name, 0f, 100f, name)

    private val registry = ParameterRanges

    @Test
    fun `knock correction spans its negative range`() {
        val r = registry.expected(Def("Knock Correction", DisplayUnit.DEGREES), DisplayUnit.DEGREES)
        assertNotNull(r)
        assertEquals(-15f, r!!.first, 0f)
        assertEquals(2f, r.second, 0f)
    }

    @Test
    fun `both the XML and the short parameter spellings resolve`() {
        val long = registry.expected(Def("Coolant Temperature", DisplayUnit.C), DisplayUnit.C)
        val short = registry.expected(Def("Coolant Temp", DisplayUnit.C), DisplayUnit.C)
        assertEquals(long, short)
        assertEquals(20f, long!!.first, 0f)
        assertEquals(150f, long.second, 0f)
    }

    @Test
    fun `range converts into the display unit`() {
        val c = registry.expected(Def("Coolant Temperature", DisplayUnit.C), DisplayUnit.C)!!
        val f = registry.expected(Def("Coolant Temperature", DisplayUnit.C), DisplayUnit.F)!!
        assertEquals(68f, f.first, 0.1f)    // 20 C
        assertEquals(302f, f.second, 0.1f)  // 150 C
        assertEquals(20f, c.first, 0f)
    }

    @Test
    fun `unlisted parameters autoscale`() {
        assertNull(registry.expected(Def("Boost", DisplayUnit.KPA), DisplayUnit.KPA))
        assertNull(registry.expected(Def("DAM", DisplayUnit.MULTIPLIER), DisplayUnit.MULTIPLIER))
        assertNull(registry.expected(Def("Nonexistent Param", DisplayUnit.PERCENT), DisplayUnit.PERCENT))
        assertNull(registry.expected(null, DisplayUnit.PERCENT))
    }

    @Test
    fun `fuel tank pressure spans the sensor, in kPa not bar`() {
        // One byte through (x-128)/40 gives -3.2..+3.175 kPa. Logged values on
        // 2026-08-29 ran -2.08..+2.63 kPa, so a +-2 bar axis was 60x too wide.
        val r = registry.expected(Def("Fuel Tank Pressure", DisplayUnit.KPA), DisplayUnit.KPA)!!
        assertEquals(-3.2f, r.first, 0.001f)
        assertEquals(3.2f, r.second, 0.001f)

        val observedMin = -2.075f
        val observedMax = 2.625f
        assertTrue("observed minimum must fit the axis", observedMin > r.first)
        assertTrue("observed maximum must fit the axis", observedMax < r.second)
        // A real EVAP pulldown has to use a usable share of the plot height.
        assertTrue(
            "observed span should fill a good fraction of the axis",
            (observedMax - observedMin) / (r.second - r.first) > 0.5f
        )
    }

    @Test
    fun `pressure ranges stay inside what a single byte can express`() {
        val map = registry.expected(Def("Manifold Relative Pressure", DisplayUnit.KPA), DisplayUnit.KPA)!!
        assertTrue("x-128 cannot exceed +127 kPa", map.second <= 127f)
        assertTrue("x-128 cannot go below -128 kPa", map.first >= -128f)
    }

    @Test
    fun `fuel level keeps its calibration range`() {
        val gal = registry.expected(Def("Fuel Level", DisplayUnit.VOLTS), DisplayUnit.GALLONS)
        assertNotNull(gal)
        assertEquals(0f, gal!!.first, 0f)
    }

    @Test
    fun `gauges still get a non-null range for everything`() {
        // Circular gauges cannot autoscale, so these must stay total.
        assertEquals(0f, registry.min(Def("Boost", DisplayUnit.KPA), DisplayUnit.KPA), 0f)
        assertEquals(100f, registry.max(Def("Boost", DisplayUnit.KPA), DisplayUnit.KPA), 0f)
    }
}
