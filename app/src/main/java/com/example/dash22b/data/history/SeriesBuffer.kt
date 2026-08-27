package com.example.dash22b.data.history

import com.example.dash22b.data.DisplayUnit

/**
 * Reusable result of a [HistoryStore.query].
 *
 * One instance is remembered per graph and refilled in place, so redrawing at frame
 * rate allocates nothing. Bucket i covers [fromTs + i*step, fromTs + (i+1)*step);
 * empty buckets are NaN in min/max/mean.
 *
 * Not thread-safe: each buffer belongs to exactly one composable.
 */
class SeriesBuffer(val buckets: Int) {

    var param: String = ""
        internal set
    var unit: DisplayUnit = DisplayUnit.UNKNOWN
        internal set
    var fromTs: Long = 0L
        internal set
    var toTs: Long = 0L
        internal set

    /** Buckets carrying data. 0 means the query matched nothing. */
    var count: Int = 0
        internal set

    /** Bucket center timestamps. */
    val ts = LongArray(buckets)
    val min = FloatArray(buckets)
    val max = FloatArray(buckets)
    val mean = FloatArray(buckets)

    private val sum = DoubleArray(buckets)
    private val n = IntArray(buckets)

    /** True when every bucket is empty — nothing to draw. */
    val isEmpty: Boolean
        get() = count == 0

    internal fun reset(param: String, unit: DisplayUnit, fromTs: Long, toTs: Long) {
        this.param = param
        this.unit = unit
        this.fromTs = fromTs
        this.toTs = toTs
        this.count = 0
        java.util.Arrays.fill(min, Float.NaN)
        java.util.Arrays.fill(max, Float.NaN)
        java.util.Arrays.fill(mean, Float.NaN)
        java.util.Arrays.fill(sum, 0.0)
        java.util.Arrays.fill(n, 0)

        if (toTs > fromTs) {
            val step = (toTs - fromTs).toDouble() / buckets
            for (b in 0 until buckets) {
                ts[b] = fromTs + ((b + 0.5) * step).toLong()
            }
        } else {
            java.util.Arrays.fill(ts, fromTs)
        }
    }

    internal fun accumulate(bucket: Int, value: Float) {
        if (n[bucket] == 0) {
            min[bucket] = value
            max[bucket] = value
        } else {
            if (value < min[bucket]) min[bucket] = value
            if (value > max[bucket]) max[bucket] = value
        }
        sum[bucket] += value.toDouble()
        n[bucket]++
    }

    internal fun finish() {
        var filled = 0
        for (b in 0 until buckets) {
            if (n[b] > 0) {
                mean[b] = (sum[b] / n[b]).toFloat()
                filled++
            }
        }
        count = if (filled > 0) buckets else 0
    }

    /** Copies [src] into this buffer wholesale; used by the unit-conversion pass. */
    internal fun copyMetaFrom(src: SeriesBuffer) {
        param = src.param
        fromTs = src.fromTs
        toTs = src.toTs
        count = src.count
        System.arraycopy(src.ts, 0, ts, 0, buckets)
    }
}
