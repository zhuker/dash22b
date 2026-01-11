package com.example.dash22b.data

import org.junit.Test
import org.junit.Assert.assertEquals

class UnitConverterTest {
    @Test
    fun testAFR() {
        assertEquals(1.0f, UnitConverter.convert(14.7f, "lambda", "afr"))
        assertEquals(14.7f, UnitConverter.convert(1.0f, "afr", "lambda"))
    }
}