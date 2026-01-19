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
import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.UnitConverter
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.dash22b.data.EngineData
import com.example.dash22b.ui.components.CircularGauge
import com.example.dash22b.ui.components.LineGraph
import com.example.dash22b.ui.theme.DashboardDarkBg
import com.example.dash22b.ui.theme.GaugeGreen
import com.example.dash22b.ui.theme.GaugeOrange
import com.example.dash22b.ui.theme.GaugeRed
import com.example.dash22b.ui.theme.GaugeTeal
import com.example.dash22b.ui.theme.Purple40

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.LaunchedEffect
import com.example.dash22b.data.GaugeConfig
import com.example.dash22b.data.PresetState
import com.example.dash22b.data.SsmDataSource
import com.example.dash22b.di.LocalParameterRegistry
import com.example.dash22b.di.LocalPresetManager
import com.example.dash22b.di.LocalTpmsRepository
import com.example.dash22b.service.TpmsService
import com.example.dash22b.ui.components.ParameterBottomSheet
import com.example.dash22b.ui.components.PresetBottomSheet
import com.example.dash22b.ui.components.PresetLabel
import com.example.dash22b.data.PresetManager.Companion.GAUGE_DISABLED_PARAM
import timber.log.Timber

enum class ScreenMode {
    GAUGES, GRAPHS, OTHER
}


@Composable
fun DashboardScreen() {
    // State for Data
    val context = androidx.compose.ui.platform.LocalContext.current
    val parameterRegistry = LocalParameterRegistry.current
    val dataSource = remember { SsmDataSource(context, parameterRegistry) }

    // Use Repository for TPMS data (populated by Background Service)
    val tpmsRepository = LocalTpmsRepository.current
    val tpmsData by tpmsRepository.tpmsState.collectAsState()

    // Preset Manager for gauge configurations
    val presetManager = LocalPresetManager.current
    val presetState by presetManager.presetState.collectAsState()
    val allPresets by presetManager.allPresets.collectAsState()
    val gaugeConfigs by presetManager.currentConfigs.collectAsState()
    val subscribedParams by presetManager.subscribedParameters.collectAsState()

    // Subscribe to parameters when they change
    LaunchedEffect(subscribedParams) {
        dataSource.subscribeToParameters(subscribedParams)
    }

    val dataFlow = remember(dataSource) { dataSource.getEngineData() }
    val engineDataRaw by dataFlow.collectAsState(initial = EngineData())

    val engineData = remember(engineDataRaw, tpmsData) {
        engineDataRaw.copy(tpms = tpmsData)
    }

    // State for Navigation
    var currentMode by remember { mutableStateOf(ScreenMode.GAUGES) }

    // State for parameter selection dialog
    var showDialogForId by remember { mutableStateOf<Int?>(null) }

    // State for preset bottom sheet
    var showPresetSheet by remember { mutableStateOf(false) }

    // Parameter selection bottom sheet
    ParameterBottomSheet(
        isVisible = showDialogForId != null,
        onDismiss = { showDialogForId = null },
        onParameterSelected = { newParam, newUnit ->
            presetManager.updateGaugeConfig(showDialogForId!!, newParam, newUnit)
            showDialogForId = null
        }
    )

    // Preset bottom sheet
    PresetBottomSheet(
        isVisible = showPresetSheet,
        presets = allPresets,
        currentState = presetState,
        onDismiss = { showPresetSheet = false },
        onPresetSelected = { preset ->
            presetManager.loadPreset(preset.id)
            showPresetSheet = false
        },
        onSaveAsNew = { name ->
            presetManager.saveCurrentAsPreset(name)
            showPresetSheet = false
        },
        onRename = { id, newName ->
            presetManager.renamePreset(id, newName)
        },
        onDelete = { id ->
            presetManager.deletePreset(id)
        }
    )

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
                            presetState = presetState,
                            onGaugeLongClick = { id -> showDialogForId = id },
                            onPresetClick = { showPresetSheet = true }
                        )
                        ScreenMode.GRAPHS -> GraphsContent(engineData)
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
                            presetState = presetState,
                            onGaugeLongClick = { id -> showDialogForId = id },
                            onPresetClick = { showPresetSheet = true }
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
    val tempText = if (isLocallyStale) "NA" else String.format("%.0f${state.temp.unit.displayName()}", state.temp.value)
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
    // Handle disabled gauge
    if (config.parameterName == GAUGE_DISABLED_PARAM) {
        CircularGauge(
            value = com.example.dash22b.data.ValueWithUnit(0f, DisplayUnit.UNKNOWN),
            minValue = 0f,
            maxValue = 100f,
            label = "—",
            color = Color.DarkGray,
            modifier = modifier,
            onLongClick = onLongClick
        )
        return
    }

    val parameterRegistry = LocalParameterRegistry.current

    // Look up definition
    var key = config.parameterName
    val def = parameterRegistry.getDefinition(key)
    key = def?.name ?: key
    val vwu =
    if (!data.values.containsKey(key)) {
        Timber.w("no value for '$key' '${def?.name}' ${data.values.keys}")
        com.example.dash22b.data.ValueWithUnit(0f, DisplayUnit.UNKNOWN)
    } else {
        data.values[key]!!
    }

    // Heuristic or Default Target Unit
    // If configuration has a preferred unit, use it.
    // Otherwise, if definition has a preferred unit, use it.
    // Otherwise use the log unit.
    var targetUnit = config.getDisplayUnit() ?: def?.unit ?: vwu.unit

    // Heuristic overrides ONLY if no specific unit was selected in config
    if (config.displayUnitName == null) {
        if (key.contains("Boost") || key.contains("Pressure")) {
            targetUnit = DisplayUnit.BAR
        } else if (targetUnit == DisplayUnit.F || vwu.unit == DisplayUnit.F) {
            targetUnit = DisplayUnit.C // Always prefer C for display?
        }
    }

    val displayValue = vwu.to(targetUnit)

    // Fallbacks if not found
    val label = def?.name ?: key

    // Heuristic Min/Max
    val min = def?.maxExpected ?: 0f
    val max = def?.maxExpected ?: 100f

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
        color = color,
        modifier = modifier,
        onLongClick = onLongClick
    )
}

@Composable
fun PortraitGaugesContent(
    engineData: EngineData,
    gaugeConfigs: List<GaugeConfig>,
    presetState: PresetState,
    onGaugeLongClick: (Int) -> Unit,
    onPresetClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top Row: Big Gauges (IDs 0, 1)
        Row(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DynamicCircularGauge(
                config = gaugeConfigs.find { it.id == 0 } ?: GaugeConfig(0, "Engine Speed"),
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

        // Preset label between big gauges and small gauges
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            PresetLabel(
                presetState = presetState,
                onClick = onPresetClick
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
    presetState: PresetState,
    onGaugeLongClick: (Int) -> Unit,
    onPresetClick: () -> Unit
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
                config = gaugeConfigs.find { it.id == 0 } ?: GaugeConfig(0, "Engine Speed"),
                data = engineData,
                modifier = Modifier.weight(1f).padding(16.dp),
                isBig = true,
                onLongClick = { onGaugeLongClick(0) }
            )

            // Preset label between large gauges
            PresetLabel(
                presetState = presetState,
                onClick = onPresetClick
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
     fun convertHist(history: List<Float>, fromUnit: DisplayUnit, toUnit: DisplayUnit): List<Float> {
         return history.map { UnitConverter.convert(it, fromUnit, toUnit) }
     }

     fun convertVal(value: Float, fromUnit: DisplayUnit, toUnit: DisplayUnit): Float {
         return UnitConverter.convert(value, fromUnit, toUnit)
     }

    Column(modifier = Modifier.fillMaxSize()) {
        // Row 1
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = data.rpmHistory, // RPM usually needs no conversion
                label = "RPM",
                 unit = DisplayUnit.RPM,
                currentValue = data.rpm.value,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Spark Advance History TBD
                label = "Spark Adv",
                unit = DisplayUnit.DEGREES,
                currentValue = data.sparkLines.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Coolant History TBD
                label = "Coolant",
                unit = DisplayUnit.C,
                currentValue = convertVal(data.coolantTemp.value, data.coolantTemp.unit,
                    DisplayUnit.C
                ),
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
        // Row 2
        Row(modifier = Modifier.weight(1f)) {
            val boostUnit = DisplayUnit.BAR
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
                unit = DisplayUnit.AFR,
                currentValue = data.afr.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // IAT History TBD
                label = "IAT",
                unit = DisplayUnit.C,
                currentValue = convertVal(data.iat.value, data.iat.unit, DisplayUnit.C),
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
        // Row 3
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = listOf(), // Pulse History TBD
                label = "Pulse Width",
                 unit = DisplayUnit.MILLISECONDS,
                currentValue = data.pulseWidth.value,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Duty History TBD
                label = "Duty Cycle",
                unit = DisplayUnit.PERCENT,
                currentValue = data.dutyCycle.value,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             LineGraph(
                dataPoints = listOf(), // MAF History TBD
                label = "MAF",
                 unit = DisplayUnit.GRAMS_PER_SEC,
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
