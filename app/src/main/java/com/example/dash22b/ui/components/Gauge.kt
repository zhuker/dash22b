package com.example.dash22b.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dash22b.data.ValueWithUnit
import com.example.dash22b.ui.theme.GaugeGreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CircularGauge(
    value: ValueWithUnit,
    minValue: Float = 0f,
    maxValue: Float = 100f,
    format: String = "%.1f",
    label: String,
    color: Color = GaugeGreen,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(8.dp)
            .combinedClickable(
                onClick = {}, // No-op for normal click, or maybe focus?
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val startAngle = 135f
            val sweepAngle = 270f
            
            // Background Arc
            drawArc(
                color = color.copy(alpha = 0.2f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Foreground Arc
            val range = maxValue - minValue
            val progress = ((value.value - minValue) / range).coerceIn(0f, 1f)
            val currentSweep = sweepAngle * progress

            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-4).dp) // Visual correction
        ) {
            Text(
                text = String.format(format, value.value),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.LightGray
                )
            )
            Text(
                text = value.unit.displayName(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            )
            
        }
    }
}
