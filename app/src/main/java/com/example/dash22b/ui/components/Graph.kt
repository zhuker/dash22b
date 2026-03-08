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

@Composable
fun LineGraph(
    dataPoints: List<Float>,
    label: String,
    unit: DisplayUnit,
    color: Color,
    currentValue: Float,
    modifier: Modifier = Modifier,
    minY: Float? = null,
    maxY: Float? = null
) {
    Box(
        modifier = modifier
            .background(Color(0xFF181818))
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Label and Line
            // Actually in the screenshot, the graph is mostly the line with label/value at bottom.
            // Let's mimic screenshot 3 layout.
            // It has graph area, and at bottom: Label Name ----- Value
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (dataPoints.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val path = Path()
                        val width = size.width
                        val height = size.height
                        
                        val min = minY ?: dataPoints.minOrNull() ?: 0f
                        val max = maxY ?: dataPoints.maxOrNull() ?: 100f
                        val range = (max - min).coerceAtLeast(1f)

                        dataPoints.forEachIndexed { index, value ->
                            val x = (index.toFloat() / (dataPoints.size - 1).coerceAtLeast(1)) * width
                            val normalizedValue = (value - min) / range
                            val y = height - (normalizedValue * height)
                            
                            if (index == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }

                        drawPath(
                            path = path,
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
