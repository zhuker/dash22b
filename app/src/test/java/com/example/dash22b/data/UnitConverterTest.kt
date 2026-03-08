package com.example.dash22b.data

import org.junit.Test
import org.junit.Assert.assertEquals

class UnitConverterTest {
    @Test
    fun testAFR() {
        assertEquals(1.0f, UnitConverter.convert(14.7f, DisplayUnit.LAMBDA, DisplayUnit.AFR))
        assertEquals(14.7f, UnitConverter.convert(1.0f, DisplayUnit.AFR, DisplayUnit.LAMBDA))
    }

    @Test
    fun testMPG() {
        assertEquals(11.76f, UnitConverter.convert(20f, DisplayUnit.MPG, DisplayUnit.L100KM), 0.01f)
        assertEquals(11.76f, UnitConverter.convert(20f, DisplayUnit.L100KM, DisplayUnit.MPG), 0.01f)
    }
}