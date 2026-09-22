package com.example.dash22b.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomStatusBarFormatTest {

    /**
     * The previous build showed the constants "000006 km" and "005.6 km". Both looked like
     * readings and neither ever was, so nothing must be shown until there is a real value.
     */
    @Test
    fun showsBlanksRatherThanInventedNumbers() {
        assertEquals("------ km", formatOdometer(0.0))
        assertEquals("---.- km", formatTrip(0.0, hasFix = false))
    }

    @Test
    fun keepsThePlaceholderWidths() {
        assertEquals("000006 km", formatOdometer(6_400.0))
        assertEquals("005.6 km", formatTrip(5_600.0, hasFix = true))
    }

    @Test
    fun showsZeroTripOnceThereIsAFix() {
        assertEquals("000.0 km", formatTrip(0.0, hasFix = true))
    }

    @Test
    fun roundsTheOdometerDownToWholeKilometres() {
        assertEquals("123456 km", formatOdometer(123_456_999.0))
    }
}
