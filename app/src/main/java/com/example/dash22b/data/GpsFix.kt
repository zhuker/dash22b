package com.example.dash22b.data

/**
 * One GNSS fix, flattened into plain types.
 *
 * Deliberately free of Android classes so the CSV writer and its tests can run on the
 * JVM. [LocationSource] is the only place that knows about `android.location.Location`.
 *
 * Position is [Double]. A `Float` latitude resolves to roughly a metre, which is coarser
 * than the receiver itself, so position never travels through the `Float`-based
 * [EngineData] values map -- only speed does.
 *
 * Two clocks are kept on purpose:
 *  - [elapsedRealtimeNanos] is the fix instant on the monotonic boot clock. It is the only
 *    safe basis for "how old is this fix", because the wall clock can be stepped by NTP
 *    mid-drive.
 *  - [utcMillis] is the receiver's own UTC, which is satellite-derived and independent of
 *    the head unit's clock. Logging both is what lets a drive recorded with a wrong device
 *    clock be corrected afterwards.
 *
 * [recordedAtMillis] is the device wall clock when the fix was delivered. It shares a basis
 * with the monitor CSV's timestamp column, so the two files can be joined directly.
 */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long,
    val utcMillis: Long,
    val recordedAtMillis: Long,
    val speedKmh: Float? = null,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Float? = null,
    val accuracyMeters: Float? = null,
    val satellitesUsed: Int? = null
) {
    /** Age of the fix in milliseconds against a monotonic `elapsedRealtimeNanos` reading. */
    fun ageMillis(nowElapsedNanos: Long): Long =
        (nowElapsedNanos - elapsedRealtimeNanos) / 1_000_000L

    /**
     * Whether this fix is recent enough to stand in for "where the car is now".
     *
     * A stale fix is worse than no fix: held forever it would draw a car parked at the last
     * place it saw sky, which reads as data rather than as a dropout.
     */
    fun isFresh(nowElapsedNanos: Long, maxAgeMillis: Long = MAX_AGE_MS): Boolean {
        val age = ageMillis(nowElapsedNanos)
        return age in 0..maxAgeMillis
    }

    companion object {
        /**
         * How long a fix may stand in for the current position.
         *
         * Receivers deliver at 1 Hz on most hardware, so this tolerates four missed fixes
         * before the value is dropped rather than held.
         */
        const val MAX_AGE_MS = 5_000L
    }
}
