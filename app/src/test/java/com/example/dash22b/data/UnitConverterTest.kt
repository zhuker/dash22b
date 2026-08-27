package com.example.dash22b.data

import org.junit.Test
import org.junit.Assert.assertEquals

class UnitConverterTest {
    @Test
    fun testAFR() {
        // convert(value, from, to). Stoichiometric gasoline is 14.7:1, so lambda 1.0
        // is AFR 14.7 -- not the other way round.
        assertEquals(14.7f, UnitConverter.convert(1.0f, DisplayUnit.LAMBDA, DisplayUnit.AFR), 0.001f)
        assertEquals(1.0f, UnitConverter.convert(14.7f, DisplayUnit.AFR, DisplayUnit.LAMBDA), 0.001f)
    }

    @Test
    fun testAFRRoundTrip() {
        val lambda = 0.85f  // a plausible boosted target
        val afr = UnitConverter.convert(lambda, DisplayUnit.LAMBDA, DisplayUnit.AFR)
        assertEquals(12.495f, afr, 0.001f)
        assertEquals(lambda, UnitConverter.convert(afr, DisplayUnit.AFR, DisplayUnit.LAMBDA), 0.001f)
    }

    @Test
    fun testMPG() {
        assertEquals(11.76f, UnitConverter.convert(20f, DisplayUnit.MPG, DisplayUnit.L100KM), 0.01f)
        assertEquals(11.76f, UnitConverter.convert(20f, DisplayUnit.L100KM, DisplayUnit.MPG), 0.01f)
    }
}
