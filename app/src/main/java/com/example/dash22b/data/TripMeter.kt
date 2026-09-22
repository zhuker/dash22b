package com.example.dash22b.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a stream of fixes into distance travelled.
 *
 * Distance from GPS is not just the sum of the gaps between fixes. A receiver standing
 * still still wanders, by metres per minute; summed at 1 Hz over a lunch stop that invents
 * kilometres the car never drove. So a step is counted only when there is evidence the car
 * actually moved, and every rejected step still re-anchors the position -- otherwise the
 * next accepted step would silently include all the movement that was skipped.
 *
 * Free of Android types so the rules can be tested directly.
 */
class TripMeter(startingOdometerMeters: Double = 0.0) {

    private var previous: GpsFix? = null

    /** Distance since this meter was created, in metres. */
    var tripMeters: Double = 0.0
        private set

    /** Lifetime distance, in metres, continuing from the value this meter started with. */
    var odometerMeters: Double = startingOdometerMeters
        private set

    /**
     * Offers a fix and returns the metres added, which is 0.0 when the step was rejected.
     */
    fun accept(fix: GpsFix): Double {
        // A fix this vague could be anywhere in a city block; it is not evidence of motion,
        // and it is not a trustworthy anchor for the next step either.
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return 0.0

        val from = previous
        previous = fix
        if (from == null) return 0.0

        val gapMillis = fix.elapsedRealtimeNanos.minus(from.elapsedRealtimeNanos) / 1_000_000L
        // Out of order or the boot clock reset: there is no interval to reason about.
        if (gapMillis <= 0L) return 0.0
        // After a long dropout the straight line between two points is a guess, not a path.
        // Re-anchor and start measuring again from here.
        if (gapMillis > MAX_GAP_MS) return 0.0

        val step = distanceMeters(from, fix)

        // Faster than any car on a road means the fix jumped, not the vehicle.
        val impliedSpeedKmh = step / (gapMillis / 1000.0) * 3.6
        if (impliedSpeedKmh > MAX_PLAUSIBLE_SPEED_KMH) return 0.0

        if (!isMoving(fix, step)) return 0.0

        tripMeters += step
        odometerMeters += step
        return step
    }

    /**
     * Whether the car was moving over this step.
     *
     * Doppler speed is the better witness where it exists: it reads a true zero when parked,
     * while position keeps drifting. Without it, the step has to be bigger than the drift.
     */
    private fun isMoving(fix: GpsFix, stepMeters: Double): Boolean {
        val speed = fix.speedKmh
        return if (speed != null) speed >= MIN_SPEED_KMH else stepMeters >= MIN_STEP_M
    }

    companion object {
        /** Worse than this and the fix is not evidence of anything. */
        const val MAX_ACCURACY_M = 25.0

        /** Below this, Doppler speed is indistinguishable from a parked receiver's noise. */
        const val MIN_SPEED_KMH = 3.0

        /** Fallback threshold when the receiver reports no speed at all. */
        const val MIN_STEP_M = 5.0

        /** Longer than this and the path between two fixes is unknown. */
        const val MAX_GAP_MS = 30_000L

        /** Anything quicker is a jump in the fix, not the car. */
        const val MAX_PLAUSIBLE_SPEED_KMH = 300.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Great-circle distance. Haversine rather than a flat-earth approximation: the
         * approximation is fine over one second of driving but drifts with latitude, and
         * this value is summed thousands of times per drive.
         */
        fun distanceMeters(from: GpsFix, to: GpsFix): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(to.longitude - from.longitude)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
