package com.example.dash22b.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.EngineData
import com.example.dash22b.data.LogFileDataSource
import com.example.dash22b.ui.components.CircularGauge
import com.example.dash22b.ui.components.LineGraph
import com.example.dash22b.ui.theme.DashboardDarkBg
import com.example.dash22b.ui.theme.GaugeBlue
import com.example.dash22b.ui.theme.GaugeGreen
import com.example.dash22b.ui.theme.GaugeOrange
import com.example.dash22b.ui.theme.GaugeRed
import com.example.dash22b.ui.theme.GaugeTeal
import com.example.dash22b.ui.theme.Purple40

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import com.example.dash22b.data.ParameterRegistry
import com.example.dash22b.data.SsmDataSource
import com.example.dash22b.service.TpmsService
import timber.log.Timber

enum class ScreenMode {
    GAUGES, GRAPHS, OTHER
}

data class GaugeConfig(
    val id: Int,
    val parameterName: String
)

@Composable
fun ParameterSelectionDialog(
    onDismiss: () -> Unit,
    onParameterSelected: (String) -> Unit
) {
    val options = remember { ParameterRegistry.getAllDefinitions() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Parameter") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                items(options) { param ->
                    Text(
                        text = "${param.name} (${param.unit})",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onParameterSelected(param.accessportName) }
                            .padding(16.dp)
                    )
                    androidx.compose.material3.Divider(color = Color.DarkGray)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DashboardScreen() {
    // State for Data
    val context = androidx.compose.ui.platform.LocalContext.current
    // Use the concrete Android implementation here at the UI boundary
//    val assetLoader = remember { com.example.dash22b.data.AndroidAssetLoader(context) }
    val dataSource = remember { SsmDataSource(context) }
    
    // Use Repository for TPMS data (populated by Background Service)
    val tpmsData by com.example.dash22b.data.TpmsRepository.tpmsState.collectAsState()
    
    val dataFlow = remember(dataSource) { dataSource.getEngineData() }
    val engineDataRaw by dataFlow.collectAsState(initial = EngineData())

    val engineData = remember(engineDataRaw, tpmsData) {
        engineDataRaw.copy(tpms = tpmsData)
    }
    
    // State for Navigation
    var currentMode by remember { mutableStateOf(ScreenMode.GAUGES) }

    // State for Dynamic Gauges (ID -> Config)
    // IDs: 
    // 0, 1: Big Gauges (Top in Portrait, Left in Landscape)
    // 2..10: Small Grid Gauges
    val initialConfigs = remember {
        listOf(
            GaugeConfig(0, "RPM"),
            GaugeConfig(1, "Vehicle Speed"),
            GaugeConfig(2, "Boost"), // or "Manifold Relative Pressure" depending on registry
            GaugeConfig(3, "Battery Voltage"),
            GaugeConfig(4, "Inj Pulse Width"), 
            GaugeConfig(5, "Coolant Temp"),
            GaugeConfig(6, "Ignition Timing"), 
            GaugeConfig(7, "Inj Duty Cycle"), 
            GaugeConfig(8, "Intake Temp"), 
            GaugeConfig(9, "Comm Fuel Final"), // "AFR" usually mapped to Comm Fuel Final or AF Sens 1 Ratio
            GaugeConfig(10, "Mass Airflow")
        )
    }
    
    var gaugeConfigs by remember { mutableStateOf(initialConfigs) }
    var showDialogForId by remember { mutableStateOf<Int?>(null) }

    if (showDialogForId != null) {
        ParameterSelectionDialog(
            onDismiss = { showDialogForId = null },
            onParameterSelected = { newParam ->
                gaugeConfigs = gaugeConfigs.map { 
                    if (it.id == showDialogForId) it.copy(parameterName = newParam) else it 
                }
                showDialogForId = null
            }
        )
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardDarkBg)
    ) {
        val isPortrait = maxHeight > maxWidth
        
        if (isPortrait) {
            Column(modifier = Modifier
                .fillMaxSize()
                /*.windowInsetsPadding(WindowInsets.safeDrawing)*/) { // Add padding for system bars
                // Main Content
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)) {
                    
                    when (currentMode) {
                        ScreenMode.GAUGES -> PortraitGaugesContent(
                            engineData = engineData,
                            gaugeConfigs = gaugeConfigs,
                            onGaugeLongClick = { id -> showDialogForId = id }
                        )
                        ScreenMode.GRAPHS -> GraphsContent(engineData) // Graphs reuse for now
                        ScreenMode.OTHER -> OtherContent(engineData)
                    }
                }
                
                // Bottom Status Bar (Now stacked below content in Portrait)
                BottomStatusBar(
                    engineData = engineData,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Bottom Navigation
                BottomNavigationBar(
                    currentMode = currentMode,
                    onModeSelected = { currentMode = it }
                )
            }
        } else {
            // Landscape Layout (Original)
            Row(modifier = Modifier
                .fillMaxSize()
                /*.windowInsetsPadding(WindowInsets.safeDrawing)*/) { // Add padding for system bars
                // Sidebar
                NavigationSidebar(
                    currentMode = currentMode,
                    onModeSelected = { currentMode = it }
                )

                // Main Content
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)) {
                    
                    when (currentMode) {
                        ScreenMode.GAUGES -> GaugesContent(
                            engineData = engineData,
                            gaugeConfigs = gaugeConfigs,
                            onGaugeLongClick = { id -> showDialogForId = id }
                        )
                        ScreenMode.GRAPHS -> GraphsContent(engineData)
                        ScreenMode.OTHER -> OtherContent(engineData)
                    }
                    
                    // Bottom Status Bar
                    BottomStatusBar(
                        engineData = engineData,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
fun OtherContent(engineData: EngineData) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Car Image
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.example.dash22b.R.drawable.car_tpms),
            contentDescription = "Car TPMS",
            modifier = Modifier
                .fillMaxHeight(0.8f) // Scale to fit nicely
                .padding(16.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )

        // TPMS Values - Positioned roughly around the car (Conceptually)
        // Since we don't have exact pixel coordinates, we'll use a Column/Row box structure
        // Or absolute offsets if we want to be precise, but relative layout is safer.
        // Let's use a Column with Rows for FL/FR and RL/RR
        
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
             // Top Row (FL, FR)
             Row(
                 modifier = Modifier
                 .fillMaxWidth()
                 .padding(top = 40.dp, start = 20.dp, end = 20.dp), // Adjust padding as needed
                 horizontalArrangement = Arrangement.SpaceBetween
             ) {
                 TpmsValueDisplay(state = engineData.tpms["RL"], label = "RL")
                 TpmsValueDisplay(state = engineData.tpms["RR"], label = "RR")
             }
             
             // Bottom Row (RL, RR)
             Row(
                 modifier = Modifier
                 .fillMaxWidth()
                 .padding(bottom = 60.dp, start = 20.dp, end = 20.dp), // Adjust padding as needed
                 horizontalArrangement = Arrangement.SpaceBetween
             ) {
                 TpmsValueDisplay(state = engineData.tpms["FL"], label = "FL")
                 TpmsValueDisplay(state = engineData.tpms["FR"], label = "FR")
             }
        }
    }
}

@Composable
fun TpmsValueDisplay(state: com.example.dash22b.data.TpmsState?, label: String) {
    if (state == null) return
    
    // Calculate staleness locally so UI updates even if Service stops
    val timeSinceUpdate = System.currentTimeMillis() - state.timestamp
    val isLocallyStale = timeSinceUpdate > TpmsService.STALE_TIMEOUT_MS
    
    val pressureText = if (isLocallyStale) "--" else String.format("%.1f", state.pressure.value)
    val tempText = if (isLocallyStale) "NA" else String.format("%.0f${state.temp.unit}", state.temp.value)
    val contentColor = if (isLocallyStale) Color.Gray else Color.White

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Label (e.g. FL)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.LightGray
        )
        // Pressure (Big)
        Text(
            text = pressureText,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = contentColor
        )
        // Temp (Smaller)
        Text(
            text = tempText,
            style = MaterialTheme.typography.titleLarge,
            color = Color.Gray
        )
    }
}

@Composable
fun NavigationSidebar(
    currentMode: ScreenMode,
    onModeSelected: (ScreenMode) -> Unit
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E)), // Slightly lighter dark
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        NavItem(
            icon = Icons.Default.Home, // Gauges
            label = "Gauges",
            isSelected = currentMode == ScreenMode.GAUGES,
            onClick = { onModeSelected(ScreenMode.GAUGES) }
        )
        Spacer(modifier = Modifier.height(32.dp))
        NavItem(
            icon = Icons.Default.Menu, // Graphs
            label = "Graphs",
            isSelected = currentMode == ScreenMode.GRAPHS,
            onClick = { onModeSelected(ScreenMode.GRAPHS) }
        )
        Spacer(modifier = Modifier.height(32.dp))
        NavItem(
            icon = Icons.Default.Settings, // Other
            label = "Other",
            isSelected = currentMode == ScreenMode.OTHER,
            onClick = { onModeSelected(ScreenMode.OTHER) }
        )
    }
}

@Composable
fun BottomNavigationBar(
    currentMode: ScreenMode,
    onModeSelected: (ScreenMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp) // Reduced height
            .background(Color(0xFF1E1E1E)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(
            icon = Icons.Default.Home,
            label = "Gauges",
            isSelected = currentMode == ScreenMode.GAUGES,
            onClick = { onModeSelected(ScreenMode.GAUGES) }
        )
        NavItem(
            icon = Icons.Default.Menu,
            label = "Graphs",
            isSelected = currentMode == ScreenMode.GRAPHS,
            onClick = { onModeSelected(ScreenMode.GRAPHS) }
        )
        NavItem(
            icon = Icons.Default.Settings,
            label = "Other",
            isSelected = currentMode == ScreenMode.OTHER,
            onClick = { onModeSelected(ScreenMode.OTHER) }
        )
    }
}

@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp) // Reduced padding
    ) {
        Box(
            modifier = Modifier
                .size(32.dp) // Reduced icon container size
                .background(
                    if (isSelected) Purple40 else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White
            )
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp) // Reduced gap
        )
    }
}


@Composable
fun DynamicCircularGauge(
    config: GaugeConfig,
    data: EngineData,
    modifier: Modifier = Modifier,
    isBig: Boolean = false,
    onLongClick: () -> Unit
) {
    // Look up definition
    var key = config.parameterName
    val def = ParameterRegistry.getDefinition(key)
    key = def?.name ?: key
    val vwu =
    if (!data.values.containsKey(key)) {
        Timber.w("no value for '$key' '${def?.name}' ${data.values.keys}")
        com.example.dash22b.data.ValueWithUnit(0f, "")
    } else {
        data.values[key]!!
    }

    // Heuristic or Default Target Unit
    // If definition has a preferred unit, use it. Otherwise use the log unit.
    var targetUnit = def?.unit ?: vwu.unit
    
    // Overrides for specific gauges if registry defaults aren't what we want for display
    // e.g. defined as 'psi' but we want 'bar'
    if (key.contains("Boost") || key.contains("Pressure")) {
        targetUnit = "bar"
    } else if (def?.unit == "F" || vwu.unit.equals("F", ignoreCase = true)) {
        targetUnit = "C" // Always prefer C for display?
    }

    val displayValue = com.example.dash22b.data.UnitConverter.convert(vwu.value, vwu.unit, targetUnit)
    
    // Fallbacks if not found
    val label = def?.name ?: key
    
    // Heuristic Min/Max
    val min = 0f 
    val max = if (def?.maxExpected?.contains("100") == true) 100f 
              else if (def?.name?.contains("RPM") == true) 8000f
              else if (def?.name?.contains("Boost") == true) 2.5f // bar
              else if (def?.name?.contains("Voltage") == true) 16f
              else 100f 

    val color = if (isBig) {
         if (config.id == 0) GaugeGreen else GaugeRed
    } else {
        when(config.id % 3) {
            0 -> GaugeGreen
            1 -> GaugeTeal
            else -> GaugeOrange
        }
    }

    CircularGauge(
        value = displayValue,
        minValue = min,
        maxValue = max,
        label = label,
        unit = targetUnit,
        color = color,
        modifier = modifier,
        onLongClick = onLongClick
    )
}

@Composable
fun PortraitGaugesContent(
    engineData: EngineData,
    gaugeConfigs: List<GaugeConfig>,
    onGaugeLongClick: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top Row: Big Gauges (IDs 0, 1)
        Row(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DynamicCircularGauge(
                config = gaugeConfigs.find { it.id == 0 } ?: GaugeConfig(0, "RPM"),
                data = engineData,
                modifier = Modifier.weight(1f).padding(8.dp),
                isBig = true,
                onLongClick = { onGaugeLongClick(0) }
            )
            DynamicCircularGauge(
                config = gaugeConfigs.find { it.id == 1 } ?: GaugeConfig(1, "Vehicle Speed"),
                data = engineData,
                modifier = Modifier.weight(1f).padding(8.dp),
                isBig = true,
                onLongClick = { onGaugeLongClick(1) }
            )
        }
        
        // Bottom Grid: Smaller gauges (IDs 2..10)
        Column(
            modifier = Modifier.weight(0.6f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val gridIds = (2..10).toList().chunked(3)
            gridIds.forEach { rowIds ->
                Row(modifier = Modifier.weight(1f)) {
                    rowIds.forEach { id ->
                         DynamicCircularGauge(
                            config = gaugeConfigs.find { it.id == id } ?: GaugeConfig(id, "Unknown"),
                            data = engineData,
                            modifier = Modifier.weight(1f),
                            onLongClick = { onGaugeLongClick(id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GaugesContent(
    engineData: EngineData,
    gaugeConfigs: List<GaugeConfig>,
    onGaugeLongClick: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left Column (Large Gauges IDs 0, 1)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DynamicCircularGauge(
                config = gaugeConfigs.find { it.id == 0 } ?: GaugeConfig(0, "RPM"),
                data = engineData,
                modifier = Modifier.weight(1f).padding(16.dp),
                isBig = true,
                onLongClick = { onGaugeLongClick(0) }
            )
            DynamicCircularGauge(
                config = gaugeConfigs.find { it.id == 1 } ?: GaugeConfig(1, "Vehicle Speed"),
                data = engineData,
                modifier = Modifier.weight(1f).padding(16.dp),
                isBig = true,
                onLongClick = { onGaugeLongClick(1) }
            )
        }
        
        // Right Grid (Smaller Gauges IDs 2..10)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
             verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val gridIds = (2..10).toList().chunked(3)
            gridIds.forEach { rowIds ->
                Row(modifier = Modifier.weight(1f)) {
                    rowIds.forEach { id ->
                         DynamicCircularGauge(
                            config = gaugeConfigs.find { it.id == id } ?: GaugeConfig(id, "Unknown"),
                            data = engineData,
                            modifier = Modifier.weight(1f),
                            onLongClick = { onGaugeLongClick(id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GraphsContent(data: EngineData) {
     // A grid of graphs
     // Helper to convert history
     fun convertHist(history: List<Float>, fromUnit: String, toUnit: String): List<Float> {
         return history.map { com.example.dash22b.data.UnitConverter.convert(it, fromUnit, toUnit) }
     }
     
     fun convertVal(value: Float, fromUnit: String, toUnit: String): Float {
         return com.example.dash22b.data.UnitConverter.convert(value, fromUnit, toUnit)
     }

    Column(modifier = Modifier.fillMaxSize()) {
        // Row 1
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = data.rpmHistory, // RPM usually needs no conversion
                label = "RPM",
                unit = "", 
                currentValue = data.rpm.value,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Spark Advance History TBD
                label = "Spark Adv",
                unit = "deg", 
                currentValue = data.sparkLines.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             val coolantUnit = "C"
             LineGraph(
                dataPoints = listOf(), // Coolant History TBD
                label = "Coolant",
                unit = coolantUnit, 
                currentValue = convertVal(data.coolantTemp.value, data.coolantTemp.unit, coolantUnit),
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
        // Row 2
        Row(modifier = Modifier.weight(1f)) {
            val boostUnit = "bar"
             LineGraph(
                dataPoints = convertHist(data.boostHistory, data.boost.unit, boostUnit),
                label = "Boost",
                unit = boostUnit, 
                currentValue = convertVal(data.boost.value, data.boost.unit, boostUnit),
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // AFR History TBD
                label = "AFR",
                unit = "", 
                currentValue = data.afr.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             val iatUnit = "C"
             LineGraph(
                dataPoints = listOf(), // IAT History TBD
                label = "IAT",
                unit = iatUnit, 
                currentValue = convertVal(data.iat.value, data.iat.unit, iatUnit),
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
        // Row 3
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = listOf(), // Pulse History TBD
                label = "Pulse Width",
                unit = "ms", 
                currentValue = data.pulseWidth.value,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Duty History TBD
                label = "Duty Cycle",
                unit = "%", 
                currentValue = data.dutyCycle.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             LineGraph(
                dataPoints = listOf(), // MAF History TBD
                label = "MAF",
                unit = "g/s", 
                currentValue = data.maf.value,
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
    }
}

@Composable
fun BottomStatusBar(
    engineData: EngineData,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dateObj = Date(engineData.timestamp)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd.M.yyyy", Locale.getDefault())

        // Using smaller text styles and ensuring single line
        Text(text = "000006 km", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(text = "005.6 km", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(text = timeFormat.format(dateObj), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(text = dateFormat.format(dateObj), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
