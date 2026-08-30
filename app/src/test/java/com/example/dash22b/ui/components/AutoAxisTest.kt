package com.example.dash22b.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAxisTest {

    @Test
    fun `snaps to nice numbers`() {
        val (lo, hi) = AutoAxis.niceBounds(3.7f, 65.1f)
        assertEquals(0f, lo, 0f)      // never went negative, so zero floor
        assertEquals(80f, hi, 0f)     // 20-unit steps
        assertTrue(hi >= 65.1f)
    }

    @Test
    fun `non-negative data gets a zero floor`() {
        // Wastegate duty: padding below zero would waste plot height on the impossible.
        val (lo, hi) = AutoAxis.niceBounds(0f, 65.1f)
        assertEquals(0f, lo, 0f)
        assertTrue(hi >= 65.1f)
    }

    @Test
    fun `non-positive data gets a zero ceiling`() {
        // Knock correction is retard: zero or below.
        val (lo, hi) = AutoAxis.niceBounds(-8.5f, 0f)
        assertEquals(0f, hi, 0f)
        assertTrue(lo <= -8.5f)
    }

    @Test
    fun `bounds always contain the data`() {
        val cases = listOf(
            -2.075f to 2.625f,     // fuel tank pressure
            -54.96f to 99.44f,     // boost error
            0f to 5986.75f,        // engine speed
            26.75f to 201.32f      // target boost
        )
        for ((dMin, dMax) in cases) {
            val (lo, hi) = AutoAxis.niceBounds(dMin, dMax)
            assertTrue("$dMin must be inside $lo..$hi", lo <= dMin)
            assertTrue("$dMax must be inside $lo..$hi", hi >= dMax)
        }
    }

    @Test
    fun `flat data does not get amplified into noise`() {
        val (lo, hi) = AutoAxis.niceBounds(14.4f, 14.4f)
        assertTrue("axis must have width", hi > lo)
        assertTrue("value sits inside", lo < 14.4f && hi > 14.4f)
    }

    @Test
    fun `niceStep returns 1 2 or 5 times a power of ten`() {
        assertEquals(1f, AutoAxis.niceStep(0.9f), 0f)
        assertEquals(2f, AutoAxis.niceStep(1.5f), 0f)
        assertEquals(5f, AutoAxis.niceStep(4.2f), 0f)
        assertEquals(10f, AutoAxis.niceStep(6f), 0f)
        assertEquals(200f, AutoAxis.niceStep(150f), 0f)
        assertEquals(0.5f, AutoAxis.niceStep(0.42f), 0.0001f)
    }

    @Test
    fun `grows immediately when data escapes`() {
        val axis = AutoAxis()
        val first = axis.update(0f, 10f, 0L)
        val grown = axis.update(0f, 500f, 100L)
        assertTrue("must grow at once", grown.second > first.second)
        assertTrue(grown.second >= 500f)
    }

    @Test
    fun `does not shrink until the data has settled`() {
        val axis = AutoAxis()
        axis.update(0f, 1000f, 0L)
        val big = axis.bounds!!

        // Data collapses to a small range, but not for long enough yet.
        val soon = axis.update(0f, 10f, 1_000L)
        assertEquals(big, soon)

        // Still inside, now past the delay.
        val later = axis.update(0f, 10f, AutoAxis.SHRINK_DELAY_MS + 1_000L)
        assertTrue("must eventually shrink", later.second < big.second)
    }

    @Test
    fun `a brief excursion resets the shrink timer`() {
        val axis = AutoAxis()
        axis.update(0f, 1000f, 0L)
        val big = axis.bounds!!

        axis.update(0f, 10f, 1_000L)              // settling
        axis.update(0f, 900f, 2_000L)             // spikes back near the top
        val after = axis.update(0f, 10f, 4_000L)  // would have shrunk if not reset

        assertEquals(big, after)
    }

    @Test
    fun `empty data keeps the previous axis`() {
        val axis = AutoAxis()
        val set = axis.update(0f, 100f, 0L)
        assertEquals(set, axis.update(null, null, 1_000L))
    }
}
