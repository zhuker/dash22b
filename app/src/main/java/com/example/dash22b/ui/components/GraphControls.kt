package com.example.dash22b.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * How much history the graphs show.
 *
 * [ALL] spans everything the HistoryStore still holds, which is bounded by its ring
 * capacity (~20 min) rather than by the whole drive.
 */
enum class GraphWindow(val label: String, val durationMs: Long?) {
    ALL("All", null),
    ONE_MIN("1 min", 60_000L),
    FIVE_MIN("5 min", 5 * 60_000L)
}

@Composable
fun GraphWindowSelector(
    selected: GraphWindow,
    onSelect: (GraphWindow) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GraphWindow.entries.forEach { window ->
            val active = window == selected
            Text(
                text = window.label,
                color = if (active) Color.Black else Color.LightGray,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) Color(0xFF4CAF50) else Color(0xFF262626))
                    .border(
                        width = 1.dp,
                        color = if (active) Color(0xFF4CAF50) else Color(0xFF3A3A3A),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable { onSelect(window) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}
