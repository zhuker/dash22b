package com.example.dash22b.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsRepositoryTest {

    private class FakeStore(private var meters: Double = 0.0) : OdometerStore {
        var writes = 0
            private set

        override fun readMeters(): Double = meters

        override fun writeMeters(meters: Double) {
            this.meters = meters
            writes++
        }
    }

    private var clock = 0L

    private fun fix(northMeters: Double, speedKmh: Float? = 60f): GpsFix {
        clock += 1_000L
        return GpsFix(
            latitude = 37.0 + northMeters / 111_320.0,
            longitude = -122.0,
            elapsedRealtimeNanos = clock * 1_000_000L,
            utcMillis = 1_700_000_000_000L + clock,
            recordedAtMillis = 1_700_000_000_000L + clock,
            speedKmh = speedKmh,
            accuracyMeters = 4f
        )
    }

    @Test
    fun publishesTripAndOdometerAsFixesArrive() {
        val repository = GpsRepository(FakeStore(meters = 5_000.0))
        repository.record(fix(0.0))
        repository.record(fix(30.0))

        val state = repository.state.value
        assertEquals(30.0, state.tripMeters, 0.5)
        assertEquals(5_030.0, state.odometerMeters, 0.5)
    }

    /**
     * At 1 Hz a write per fix would be a write per second for the whole drive. Every
     * hundred metres is rare enough for flash and loses at most 100 m in a crash.
     */
    @Test
    fun savesTheOdometerOnlyEveryHundredMetres() {
        val store = FakeStore()
        val repository = GpsRepository(store)

        var north = 0.0
        repeat(5) {
            north += 30.0
            repository.record(fix(north))
        }

        assertEquals(1, store.writes)
        assertEquals(100.0, store.readMeters(), 30.0)
    }

    @Test
    fun persistKeepsTheLastPartialHundredMetres() {
        val store = FakeStore()
        val repository = GpsRepository(store)
        repository.record(fix(0.0))
        repository.record(fix(40.0))
        assertEquals(0, store.writes)

        repository.persist()

        assertEquals(1, store.writes)
        assertEquals(40.0, store.readMeters(), 0.5)
    }

    @Test
    fun offersTheSatelliteClockWhileTheFixIsRecent() {
        val repository = GpsRepository()
        val fix = fix(0.0)
        repository.record(fix)

        val now = fix.elapsedRealtimeNanos + 2_000_000_000L
        assertEquals(fix.utcMillis, repository.state.value.clockMillis(now))
    }

    /**
     * A stale fix must return null so the status bar falls back to the ECU timestamp
     * instead of showing a clock frozen at the last moment the sky was visible.
     */
    @Test
    fun givesUpTheClockOnceTheFixIsStale() {
        val repository = GpsRepository()
        val fix = fix(0.0)
        repository.record(fix)

        val now = fix.elapsedRealtimeNanos + 30_000_000_000L
        assertNull(repository.state.value.clockMillis(now))
    }

    @Test
    fun hasNoClockBeforeTheFirstFix() {
        val repository = GpsRepository()
        assertNull(repository.state.value.clockMillis(1_000_000_000L))
        assertTrue(repository.state.value.latest == null)
    }
}
