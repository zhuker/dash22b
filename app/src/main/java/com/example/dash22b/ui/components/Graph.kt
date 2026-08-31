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
import androidx.compose.ui.text.style.TextOverflow
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
    /** Y bounds to draw against. Resolved by the caller: a confident fixed range, or
     *  an [AutoAxis] result when there is none. */
    range: Pair<Float, Float>,
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

                        val min = range.first
                        val max = range.second
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
                    // A long name must not wrap onto a second line: the row is laid out
                    // against the plot above it, and growing it would shrink the trace.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

/** Y span floor, guarding against a zero-width axis. */
private const val MIN_Y_SPAN = 0.0001f
