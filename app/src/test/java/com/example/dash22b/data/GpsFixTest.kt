package com.example.dash22b.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixTest {

    private fun fix(elapsedRealtimeNanos: Long) = GpsFix(
        latitude = 37.4219983,
        longitude = -122.084,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        utcMillis = 1_700_000_000_000L,
        recordedAtMillis = 1_700_000_000_000L,
        speedKmh = 88f
    )

    @Test
    fun ageIsMeasuredOnTheMonotonicClock() {
        val fix = fix(elapsedRealtimeNanos = 10_000_000_000L)
        assertEquals(2_500L, fix.ageMillis(12_500_000_000L))
    }

    @Test
    fun aFixIsFreshUntilTheAgeLimit() {
        val fix = fix(elapsedRealtimeNanos = 10_000_000_000L)
        assertTrue(fix.isFresh(10_000_000_000L))
        assertTrue(fix.isFresh(14_999_000_000L))
        assertFalse(fix.isFresh(16_000_000_000L))
    }

    /**
     * The wall clock can be stepped by NTP mid-drive, and the boot clock resets on reboot.
     * Either can make "now" appear to precede the fix; a negative age is not freshness.
     */
    @Test
    fun aFixFromTheFutureIsNotFresh() {
        val fix = fix(elapsedRealtimeNanos = 20_000_000_000L)
        assertFalse(fix.isFresh(10_000_000_000L))
    }
}
