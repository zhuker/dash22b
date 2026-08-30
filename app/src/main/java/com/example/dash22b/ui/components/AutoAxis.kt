package com.example.dash22b.ui.components

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Y-axis bounds for a graph with no confident fixed range.
 *
 * Three rules, each fixing a specific way a naive data-derived axis misbehaves:
 *
 *  - **Never clip.** The bounds always contain the data. Statistical windows such as
 *    median +- 2 sigma look tidy but cut off exactly the rare event worth seeing --
 *    on real logs from this car they hide the wastegate duty peak, the boost error
 *    extremes and the EVAP pulldown.
 *  - **Snap to nice numbers.** Bounds land on 1/2/5 x 10^n so the axis reads as
 *    0..70 rather than 3.7..65.1, and stops twitching every frame.
 *  - **Hysteresis.** Grow the moment data exceeds the axis, shrink only once the data
 *    has stayed well inside for [SHRINK_DELAY_MS]. Without this the scale breathes
 *    continuously as samples scroll off and magnitudes become impossible to judge.
 *
 * Stateful across frames, so hold one per graph in a `remember`. Time is passed in
 * (data timestamps, not wall clock) to keep it deterministic and testable.
 */
class AutoAxis {

    private var lo = Float.NaN
    private var hi = Float.NaN
    private var insideSince = Long.MIN_VALUE

    /** Current bounds, or null before any data has been seen. */
    val bounds: Pair<Float, Float>?
        get() = if (lo.isNaN() || hi.isNaN()) null else lo to hi

    /**
     * Folds a new data extent in and returns the bounds to draw with.
     *
     * [dataMin]/[dataMax] are the extremes actually in view; [nowMs] should be the
     * newest sample's timestamp.
     */
    fun update(dataMin: Float?, dataMax: Float?, nowMs: Long): Pair<Float, Float> {
        if (dataMin == null || dataMax == null) {
            return bounds ?: (0f to 1f)
        }

        val target = niceBounds(dataMin, dataMax)

        // Nothing yet: adopt.
        if (lo.isNaN() || hi.isNaN()) {
            lo = target.first
            hi = target.second
            insideSince = nowMs
            return lo to hi
        }

        // Data has escaped the current axis: grow at once, in both directions if needed.
        if (dataMin < lo || dataMax > hi) {
            lo = minOf(lo, target.first)
            hi = maxOf(hi, target.second)
            insideSince = nowMs
            return lo to hi
        }

        // Data fits. Shrink only when a meaningfully smaller axis has been available
        // for long enough. Comparing target span against current span rather than
        // measuring the data's distance from the edges matters: a zero-floored axis
        // has dataMin sitting exactly on lo, so any margin test would never pass and
        // the graph could never shrink at all.
        val span = hi - lo
        val targetSpan = target.second - target.first
        val worthShrinking = targetSpan < span * SHRINK_FRACTION

        if (!worthShrinking) {
            insideSince = nowMs
        } else if (nowMs - insideSince >= SHRINK_DELAY_MS) {
            lo = target.first
            hi = target.second
            insideSince = nowMs
        }

        return lo to hi
    }

    companion object {
        /** How long the data must stay well inside the axis before it shrinks. */
        const val SHRINK_DELAY_MS = 3_000L

        /** The axis shrinks only if a nice axis this much smaller is available. */
        const val SHRINK_FRACTION = 0.7f

        /**
         * Rounds an extent outward onto 1/2/5 x 10^n boundaries.
         *
         * Data that never goes negative gets a zero floor: padding a duty cycle or an
         * airflow reading below zero wastes plot height on values the sensor cannot
         * produce. The same applies mirrored for data that never goes positive, which
         * is the normal case for knock correction.
         */
        fun niceBounds(dataMin: Float, dataMax: Float): Pair<Float, Float> {
            var lo = dataMin
            var hi = dataMax

            if (lo >= 0f) lo = 0f
            if (hi <= 0f) hi = 0f

            if (hi - lo < 1e-6f) {
                // Dead flat. Give it a symmetric sliver so the line sits mid-plot
                // instead of being amplified into noise by a zero-width axis.
                val pad = if (abs(hi) > 1e-6f) abs(hi) * 0.1f else 1f
                return (lo - pad) to (hi + pad)
            }

            val step = niceStep((hi - lo) / TARGET_DIVISIONS)
            return (floor(lo / step) * step) to (ceil(hi / step) * step)
        }

        private const val TARGET_DIVISIONS = 4f

        /** Nearest 1, 2 or 5 times a power of ten, at or above [raw]. */
        fun niceStep(raw: Float): Float {
            if (raw <= 0f || !raw.isFinite()) return 1f
            val exp = floor(log10(raw.toDouble()))
            val pow = 10.0.pow(exp)
            val mantissa = raw / pow
            val nice = when {
                mantissa <= 1.0 -> 1.0
                mantissa <= 2.0 -> 2.0
                mantissa <= 5.0 -> 5.0
                else -> 10.0
            }
            return (nice * pow).toFloat()
        }
    }
}
