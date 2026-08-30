package com.example.dash22b.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.history.SeriesBuffer

/**
 * Draws a bucketed series: a translucent min/max band with the mean stroked over it.
 *
 * At live zoom each bucket holds one sample, so min == max == mean and the band
 * collapses onto the line -- visually identical to a plain line graph. Zoomed out,
 * the band is what keeps a knock spike or boost overshoot visible instead of being
 * averaged away.
 *
 * NaN buckets are gaps (no samples, e.g. an ECU dropout) and break the path rather
 * than interpolating across them.
 */
@Composable
fun LineGraph(
    series: SeriesBuffer,
    label: String,
    unit: DisplayUnit,
    color: Color,
    currentValue: Float,
    modifier: Modifier = Modifier,
    /** Fixed Y bounds, or null to autoscale to the data in view. */
    range: Pair<Float, Float>? = null,
    // Redraw key: Canvas reads mutable arrays, so it needs an explicit reason to
    // recompose when the buffer is refilled in place.
    revision: Long = 0L
) {
    Box(
        modifier = modifier
            .background(Color(0xFF181818))
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!series.isEmpty) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_EXPRESSION") revision

                        val width = size.width
                        val height = size.height
                        val n = series.count

                        val bounds = range ?: series.autoBounds()
                        val min = bounds.first
                        val max = bounds.second
                        val span = (max - min).coerceAtLeast(MIN_Y_SPAN)

                        fun xOf(index: Int) =
                            (index.toFloat() / (n - 1).coerceAtLeast(1)) * width

                        // Clamp rather than let an out-of-range excursion draw outside
                        // the plot box: it parks on the edge, where it stays visible.
                        fun yOf(value: Float) =
                            height - ((((value - min) / span).coerceIn(0f, 1f)) * height)

                        // Min/max band
                        val band = Path()
                        var runStart = -1
                        for (i in 0 until n) {
                            val hasData = !series.min[i].isNaN()
                            if (hasData && runStart < 0) runStart = i
                            val runEnds = !hasData || i == n - 1
                            if (runStart >= 0 && runEnds) {
                                val last = if (hasData) i else i - 1
                                for (j in runStart..last) {
                                    val x = xOf(j)
                                    val y = yOf(series.max[j])
                                    if (j == runStart) band.moveTo(x, y) else band.lineTo(x, y)
                                }
                                for (j in last downTo runStart) {
                                    band.lineTo(xOf(j), yOf(series.min[j]))
                                }
                                band.close()
                                runStart = -1
                            }
                        }
                        drawPath(path = band, color = color.copy(alpha = 0.25f))

                        // Mean line, broken across gaps
                        val line = Path()
                        var penDown = false
                        for (i in 0 until n) {
                            val v = series.mean[i]
                            if (v.isNaN()) {
                                penDown = false
                                continue
                            }
                            val x = xOf(i)
                            val y = yOf(v)
                            if (!penDown) {
                                line.moveTo(x, y)
                                penDown = true
                            } else {
                                line.lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = line,
                            color = color,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = unit.displayName(),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = String.format("%.1f", currentValue),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

private fun SeriesBuffer.finiteMin(): Float? {
    var m = Float.MAX_VALUE
    var found = false
    for (i in 0 until count) {
        val v = min[i]
        if (!v.isNaN() && v < m) { m = v; found = true }
    }
    return if (found) m else null
}

private fun SeriesBuffer.finiteMax(): Float? {
    var m = -Float.MAX_VALUE
    var found = false
    for (i in 0 until count) {
        val v = max[i]
        if (!v.isNaN() && v > m) { m = v; found = true }
    }
    return if (found) m else null
}

/** Y span floor, so a dead-flat trace does not get amplified into noise. */
private const val MIN_Y_SPAN = 1f

/**
 * Bounds for a graph with no confident fixed range: the data's own extent with 10%
 * headroom, and zero included whenever the trace is close to it so the sign of a
 * value stays readable.
 */
private fun SeriesBuffer.autoBounds(): Pair<Float, Float> {
    val lo = finiteMin() ?: return 0f to 100f
    val hi = finiteMax() ?: return 0f to 100f

    var min = lo
    var max = hi
    if (min > 0f && min < (max - min)) min = 0f
    if (max < 0f && -max < (max - min)) max = 0f

    val pad = ((max - min) * 0.1f).coerceAtLeast(MIN_Y_SPAN / 2f)
    return (min - pad) to (max + pad)
}
