package com.example.dash22b.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.ParameterDefinition
import com.example.dash22b.data.PresetManager.Companion.GAUGE_DISABLED_PARAM
import com.example.dash22b.data.DisplayUnit
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
    onParameterSelected: (String, String?) -> Unit
) {
    if (!isVisible) return

    val parameterRegistry = LocalParameterRegistry.current
    var searchQuery by remember { mutableStateOf("") }
    
    // Selection state
    var selectedParam by remember { mutableStateOf<ParameterDefinition?>(null) }
    var selectedUnit by remember { mutableStateOf<DisplayUnit?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

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
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                text = "Configure Gauge",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Selection and Confirmation Area
            if (selectedParam != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Selected: ${selectedParam!!.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Unit:", color = Color.Gray)
                        
                        Box {
                            OutlinedButton(
                                onClick = { dropdownExpanded = true },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(selectedUnit?.displayName() ?: selectedParam!!.unit.displayName())
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("▼", style = MaterialTheme.typography.labelSmall)
                            }
                            
                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier.background(Color.DarkGray)
                            ) {
                                selectedParam!!.unit.getCompatibleUnits().forEach { unit ->
                                    DropdownMenuItem(
                                        text = { Text(unit.displayName(), color = Color.White) },
                                        onClick = {
                                            selectedUnit = unit
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { 
                                selectedParam = null
                                selectedUnit = null
                            }
                        ) {
                            Text("Clear")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onParameterSelected(selectedParam!!.accessportName, selectedUnit?.name)
                                selectedParam = null
                                selectedUnit = null
                            }
                        ) {
                            Text("Confirm")
                        }
                    }
                }
                
//                HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
            }

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
                if (searchQuery.isBlank() && selectedParam == null) {
                    item {
                        Text(
                            text = "None (disable gauge)",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onParameterSelected(GAUGE_DISABLED_PARAM, null) }
                                .padding(16.dp),
                            color = Color.Gray
                        )
//                        HorizontalDivider(color = Color.DarkGray)
                    }
                }
                
                items(filteredOptions) { param ->
                    val isSelected = selectedParam?.accessportName == param.accessportName
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                selectedParam = param
                                selectedUnit = param.unit
                            }
                            .background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = param.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) Color.White else Color.LightGray
                        )
                        Text(
                            text = param.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
//                    HorizontalDivider(color = Color.DarkGray)
                }
            }
        }
    }
}
