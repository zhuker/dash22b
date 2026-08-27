package com.example.dash22b.data.history

import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.ParameterCalibration
import com.example.dash22b.data.ParameterDefinition
import com.example.dash22b.data.UnitConverter

/**
 * Converts a raw [SeriesBuffer] from its stored source unit into [targetUnit],
 * writing into [out]. Never converts in place: [src] stays raw so a later unit
 * change reconverts from the original values rather than compounding.
 */
fun convertInto(
    src: SeriesBuffer,
    out: SeriesBuffer,
    def: ParameterDefinition?,
    targetUnit: DisplayUnit
) {
    require(src.buckets == out.buckets) { "buffer size mismatch" }

    out.copyMetaFrom(src)
    out.unit = targetUnit

    if (src.isEmpty) return

    val from = src.unit
    if (from == targetUnit || from == DisplayUnit.UNKNOWN) {
        System.arraycopy(src.min, 0, out.min, 0, src.buckets)
        System.arraycopy(src.max, 0, out.max, 0, src.buckets)
        System.arraycopy(src.mean, 0, out.mean, 0, src.buckets)
    } else {
        val name = def?.name
        for (b in 0 until src.buckets) {
            val lo = convert(name, src.min[b], from, targetUnit)
            val hi = convert(name, src.max[b], from, targetUnit)
            // A calibration may be monotonically decreasing -- GALLONS_TO_FILL rises as
            // sender voltage falls -- which swaps the endpoints. Re-order after
            // converting rather than assuming min stays min.
            out.min[b] = if (lo.isNaN() || hi.isNaN()) Float.NaN else minOf(lo, hi)
            out.max[b] = if (lo.isNaN() || hi.isNaN()) Float.NaN else maxOf(lo, hi)
            out.mean[b] = convert(name, src.mean[b], from, targetUnit)
        }
    }

    if (ParameterCalibration.shouldSmooth(def?.name, targetUnit)) {
        smoothInPlace(out.min)
        smoothInPlace(out.max)
        smoothInPlace(out.mean)
    }
}

private fun convert(name: String?, value: Float, from: DisplayUnit, to: DisplayUnit): Float {
    if (value.isNaN()) return Float.NaN
    return ParameterCalibration.convert(name, value, from, to)
        ?: UnitConverter.convert(value, from, to)
}

/**
 * [ParameterCalibration.FUEL_SMOOTHING_ALPHA] EMA over a bucketed series.
 *
 * Unlike ParameterCalibration.smoothSeries this skips NaN gaps instead of letting one
 * poison every later sample, and keeps the filter state across the gap so a brief
 * dropout does not restart the ramp.
 */
private fun smoothInPlace(values: FloatArray) {
    val a = ParameterCalibration.FUEL_SMOOTHING_ALPHA
    var s = Float.NaN
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) continue
        s = if (s.isNaN()) v else a * v + (1f - a) * s
        values[i] = s
    }
}
