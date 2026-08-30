package com.example.dash22b.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
