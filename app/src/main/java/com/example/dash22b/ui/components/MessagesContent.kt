package com.example.dash22b.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.DtcState
import com.example.dash22b.di.LocalDtcRepository
import com.example.dash22b.obd.SsmDtcCode

@Composable
fun MessagesContent() {
    val dtcRepository = LocalDtcRepository.current
    val dtcState by dtcRepository.dtcState.collectAsState()

    // Trigger DTC read when tab becomes active
    LaunchedEffect(Unit) {
        dtcRepository.requestDtcRead()
    }

    when (val state = dtcState) {
        is DtcState.Idle, is DtcState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Reading diagnostic codes...",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        is DtcState.Loaded -> {
            if (state.codes.isEmpty()) {
                // No DTCs - show "all clear" message
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ChatBubble(
                        code = null,
                        message = "All systems normal. No trouble codes detected.",
                        isTemporary = false,
                        isMemorized = false
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(state.codes) { dtc ->
                        ChatBubble(
                            code = dtc.code,
                            message = dtc.description,
                            isTemporary = dtc.isTemporary,
                            isMemorized = dtc.isMemorized
                        )
                    }
                }
            }
        }
        is DtcState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ChatBubble(
                    code = null,
                    message = "Error reading codes: ${state.message}",
                    isTemporary = false,
                    isMemorized = false
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    code: String?,
    message: String,
    isTemporary: Boolean,
    isMemorized: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Engine avatar
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF2D2D2D)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = "Engine",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Message bubble
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0xFF2D2D2D), shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            if (code != null) {
                Text(
                    text = code,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = message,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium
            )

            // Status chips
            if (isTemporary || isMemorized) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isTemporary && isMemorized) {
                        StatusChip("CURRENT", Color(0xFFE53935))
                        StatusChip("STORED", Color(0xFFE53935))
                    } else if (isTemporary) {
                        StatusChip("CURRENT", Color(0xFFFF9800))
                    } else {
                        StatusChip("STORED", Color(0xFF757575))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
