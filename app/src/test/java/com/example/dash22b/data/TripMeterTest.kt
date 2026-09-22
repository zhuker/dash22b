package com.example.dash22b.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TripMeterTest {

    /** One degree of latitude is about 111.32 km, close enough to place fixes N metres apart. */
    private fun metersNorth(meters: Double) = meters / 111_320.0

    private var clock = 0L

    private fun fix(
        northMeters: Double,
        speedKmh: Float? = 50f,
        accuracyMeters: Float? = 4f,
        afterMillis: Long = 1_000L
    ): GpsFix {
        clock += afterMillis
        return GpsFix(
            latitude = 37.0 + metersNorth(northMeters),
            longitude = -122.0,
            elapsedRealtimeNanos = clock * 1_000_000L,
            utcMillis = 1_700_000_000_000L + clock,
            recordedAtMillis = 1_700_000_000_000L + clock,
            speedKmh = speedKmh,
            accuracyMeters = accuracyMeters
        )
    }

    @Test
    fun sumsTheDistanceBetweenFixes() {
        val meter = TripMeter()
        meter.accept(fix(0.0))
        meter.accept(fix(20.0))
        meter.accept(fix(40.0))

        assertEquals(40.0, meter.tripMeters, 0.5)
        assertEquals(40.0, meter.odometerMeters, 0.5)
    }

    /**
     * The failure this class exists to prevent: a receiver standing still wanders by a few
     * metres, and summing that at 1 Hz over a lunch stop invents kilometres.
     */
    @Test
    fun aParkedCarTravelsNoDistance() {
        val meter = TripMeter()
        val drift = listOf(0.0, 3.0, -2.0, 4.0, -3.0, 2.0, -4.0, 1.0)
        drift.forEach { meter.accept(fix(it, speedKmh = 0.2f)) }

        assertEquals(0.0, meter.tripMeters, 0.0)
    }

    /**
     * Without Doppler speed there is nothing to separate drift from motion except size, so
     * the step itself has to clear the noise floor.
     */
    @Test
    fun withoutSpeedASmallStepIsTreatedAsNoise() {
        val meter = TripMeter()
        meter.accept(fix(0.0, speedKmh = null))
        meter.accept(fix(2.0, speedKmh = null))
        assertEquals(0.0, meter.tripMeters, 0.0)

        meter.accept(fix(20.0, speedKmh = null))
        assertEquals(18.0, meter.tripMeters, 0.5)
    }

    @Test
    fun aVagueFixIsNotEvidenceOfMotion() {
        val meter = TripMeter()
        meter.accept(fix(0.0))
        meter.accept(fix(200.0, accuracyMeters = 60f))

        assertEquals(0.0, meter.tripMeters, 0.0)
    }

    /**
     * After a tunnel the straight line between the two fixes is a guess, not a path. The
     * gap must re-anchor, and the next real step must not include the skipped distance.
     */
    @Test
    fun aLongGapReanchorsInsteadOfDrawingAStraightLine() {
        val meter = TripMeter()
        meter.accept(fix(0.0))
        meter.accept(fix(1_000.0, afterMillis = 60_000L))
        assertEquals(0.0, meter.tripMeters, 0.0)

        meter.accept(fix(1_020.0))
        assertEquals(20.0, meter.tripMeters, 0.5)
    }

    @Test
    fun aTeleportIsRejected() {
        val meter = TripMeter()
        meter.accept(fix(0.0))
        meter.accept(fix(5_000.0, speedKmh = 90f))

        assertEquals(0.0, meter.tripMeters, 0.0)
    }

    @Test
    fun theOdometerContinuesFromWhereItWasLeft() {
        val meter = TripMeter(startingOdometerMeters = 123_456.0)
        meter.accept(fix(0.0))
        meter.accept(fix(50.0))

        assertEquals(50.0, meter.tripMeters, 0.5)
        assertEquals(123_506.0, meter.odometerMeters, 0.5)
    }
}
