package com.example.dash22b.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the GPS-derived readouts live: the latest fix, trip distance and the odometer.
 *
 * The service writes here, the status bar reads. Separate from [SsmRepository] because none
 * of this needs the ECU -- the whole point is that the trip counter and the clock keep
 * working with the cable unplugged.
 */
class GpsRepository(
    private val odometerStore: OdometerStore = OdometerStore.None
) {

    private val tripMeter = TripMeter(odometerStore.readMeters())

    private val _state = MutableStateFlow(
        GpsTripState(odometerMeters = odometerStore.readMeters())
    )
    val state: StateFlow<GpsTripState> = _state.asStateFlow()

    /** Metres of odometer already written to storage, so saves stay rare. */
    private var savedMeters: Double = odometerStore.readMeters()

    fun record(fix: GpsFix) {
        tripMeter.accept(fix)
        _state.value = GpsTripState(
            latest = fix,
            tripMeters = tripMeter.tripMeters,
            odometerMeters = tripMeter.odometerMeters
        )

        // Persisted every hundred metres rather than every fix: at 1 Hz that is one write a
        // few seconds apart at speed instead of one a second forever, and the most a crash
        // can lose is 100 m of odometer.
        if (tripMeter.odometerMeters - savedMeters >= SAVE_EVERY_METERS) {
            savedMeters = tripMeter.odometerMeters
            odometerStore.writeMeters(savedMeters)
        }
    }

    /** Writes the odometer out, for service shutdown. */
    fun persist() {
        if (tripMeter.odometerMeters > savedMeters) {
            savedMeters = tripMeter.odometerMeters
            odometerStore.writeMeters(savedMeters)
        }
    }

    companion object {
        const val SAVE_EVERY_METERS = 100.0
    }
}

/**
 * The GPS readouts as the status bar needs them.
 *
 * [latest] is kept whole rather than reduced to a timestamp so the reader can judge whether
 * the fix is recent enough to trust -- see [GpsFix.isFresh].
 */
data class GpsTripState(
    val latest: GpsFix? = null,
    val tripMeters: Double = 0.0,
    val odometerMeters: Double = 0.0
) {
    /**
     * The satellite clock if there is a recent fix, else null so the caller can fall back.
     *
     * GPS time is derived from the satellites, so it is right even when the head unit's own
     * clock has never been set or has drifted.
     */
    fun clockMillis(nowElapsedNanos: Long, maxAgeMillis: Long = CLOCK_MAX_AGE_MS): Long? =
        latest?.takeIf { it.isFresh(nowElapsedNanos, maxAgeMillis) }?.utcMillis

    companion object {
        /**
         * Longer than the fix age GPS speed tolerates: a clock that is ten seconds stale is
         * still a better clock than a device that thinks it is 1970, whereas a speed that
         * old is a lie.
         */
        const val CLOCK_MAX_AGE_MS = 10_000L
    }
}

/** Somewhere to keep the odometer between runs. */
interface OdometerStore {
    fun readMeters(): Double
    fun writeMeters(meters: Double)

    /** For tests and for a build with nowhere to write. */
    object None : OdometerStore {
        override fun readMeters(): Double = 0.0
        override fun writeMeters(meters: Double) = Unit
    }
}
