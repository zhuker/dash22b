package com.example.dash22b.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.PresetState

/**
 * Clickable label showing current preset name or "Untitled".
 * Placed between the two large gauges.
 */
@Composable
fun PresetLabel(
    presetState: PresetState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayText = when (presetState) {
        is PresetState.Saved -> presetState.preset.name
        is PresetState.Untitled -> "Untitled"
    }

    val textColor = when (presetState) {
        is PresetState.Saved -> Color.White
        is PresetState.Untitled -> Color(0xFFFFD54F)  // Amber/yellow for unsaved
    }

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelMedium,
            color = textColor
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Select preset",
            tint = textColor
        )
    }
}
