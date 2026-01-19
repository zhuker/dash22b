package com.example.dash22b.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.PresetManager.Companion.GAUGE_DISABLED_PARAM
import com.example.dash22b.di.LocalParameterRegistry
import com.example.dash22b.ui.theme.DashboardDarkBg

/**
 * Modal bottom sheet for selecting SSM parameters for gauges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterBottomSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onParameterSelected: (String) -> Unit
) {
    if (!isVisible) return

    val parameterRegistry = LocalParameterRegistry.current
    var searchQuery by remember { mutableStateOf("") }
    
    val allOptions = remember(parameterRegistry) { parameterRegistry.getAllDefinitions() }
    val filteredOptions = remember(allOptions, searchQuery) {
        if (searchQuery.isBlank()) {
            allOptions
        } else {
            val query = searchQuery.lowercase()
            allOptions.filter {
                it.name.lowercase().contains(query) || 
                it.description.lowercase().contains(query)
            }
        }
    }
    
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DashboardDarkBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Select Parameter",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by name or description...", color = Color.Gray) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                // "None" option at the top to disable the gauge
                if (searchQuery.isBlank()) {
                    item {
                        Text(
                            text = "None (disable gauge)",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onParameterSelected(GAUGE_DISABLED_PARAM) }
                                .padding(16.dp),
                            color = Color.Gray
                        )
                        Divider(color = Color.DarkGray)
                    }
                }
                
                items(filteredOptions) { param ->
                    Text(
                        text = "${param.name} (${param.unit.displayName()})",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onParameterSelected(param.accessportName) }
                            .padding(16.dp)
                    )
                    Divider(color = Color.DarkGray)
                }
            }
        }
    }
}
