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

enum class ScreenMode {
    GAUGES, GRAPHS, OTHER
}

@Composable
fun DashboardScreen() {
    // State for Data
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataSource = remember { LogFileDataSource(context) }
    val dataFlow = remember(dataSource) { dataSource.getEngineData() }
    val engineData by dataFlow.collectAsState(initial = EngineData())
    
    // State for Navigation
    var currentMode by remember { mutableStateOf(ScreenMode.GAUGES) }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardDarkBg)
    ) {
        val isPortrait = maxHeight > maxWidth
        
        if (isPortrait) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Main Content
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)) {
                    
                    when (currentMode) {
                        ScreenMode.GAUGES -> PortraitGaugesContent(engineData)
                        ScreenMode.GRAPHS -> GraphsContent(engineData) // Graphs reuse for now
                        ScreenMode.OTHER -> Text("Other Settings", color = Color.White)
                    }
                    
                    // Bottom Status Bar
                    BottomStatusBar(
                        engineData = engineData,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
                
                // Bottom Navigation
                BottomNavigationBar(
                    currentMode = currentMode,
                    onModeSelected = { currentMode = it }
                )
            }
        } else {
            // Landscape Layout (Original)
            Row(modifier = Modifier.fillMaxSize()) {
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
                        ScreenMode.GAUGES -> GaugesContent(engineData)
                        ScreenMode.GRAPHS -> GraphsContent(engineData)
                        ScreenMode.OTHER -> Text("Other Settings", color = Color.White)
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
            .height(80.dp)
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
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (isSelected) Purple40 else Color.Transparent,
                    shape = RoundedCornerShape(24.dp)
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
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}


@Composable
fun PortraitGaugesContent(data: EngineData) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top Row: Big RPM and Speed
        Row(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CircularGauge(
                value = data.rpm.toFloat(),
                maxValue = 8000f,
                label = "RPM",
                unit = "",
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(8.dp)
            )
            CircularGauge(
                value = data.speed.toFloat(),
                maxValue = 300f,
                label = "Speed",
                unit = "km/h",
                color = GaugeRed,
                modifier = Modifier.weight(1f).padding(8.dp)
            )
        }
        
        // Bottom Grid: Smaller gauges
        Column(
            modifier = Modifier.weight(0.6f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Row 1
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.boost,
                    maxValue = 2.5f,
                    label = "Boost",
                    unit = "bar",
                    color = GaugeGreen,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.batteryVoltage,
                    minValue = 10f,
                    maxValue = 16f,
                    label = "Battery",
                    unit = "V",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.pulseWidth,
                    maxValue = 25f,
                    label = "Pulse",
                    unit = "",
                    color = GaugeOrange,
                    modifier = Modifier.weight(1f)
                )
            }
             // Row 2
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.coolantTemp.toFloat(),
                    minValue = 0f,
                    maxValue = 150f,
                    label = "Coolant",
                    unit = "°C",
                    color = GaugeGreen,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.sparkLines,
                    maxValue = 60f,
                    label = "Spark",
                    unit = "",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.dutyCycle,
                    maxValue = 100f,
                    label = "Duty",
                    unit = "%",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
            }
             // Row 3
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.iat.toFloat(),
                    minValue = 0f,
                    maxValue = 100f,
                    label = "IAT",
                    unit = "°C",
                    color = GaugeOrange,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.afr,
                    minValue = 10f,
                    maxValue = 20f,
                    label = "AFR",
                    unit = "",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.maf,
                    maxValue = 200f,
                    label = "MAF",
                    unit = "g/s",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun GaugesContent(data: EngineData) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left Column (Large Gauges)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularGauge(
                value = data.rpm.toFloat(),
                maxValue = 8000f,
                label = "RPM",
                unit = "", // Unit is just RPM
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(16.dp)
            )
            CircularGauge(
                value = data.speed.toFloat(),
                maxValue = 300f,
                label = "Speed",
                unit = "km/h",
                color = GaugeRed,
                modifier = Modifier.weight(1f).padding(16.dp)
            )
        }
        
        // Right Grid (Smaller Gauges)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
             verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Row 1
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.boost,
                    maxValue = 2.5f,
                    label = "Boost",
                    unit = "bar",
                    color = GaugeGreen,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.batteryVoltage,
                    minValue = 10f,
                    maxValue = 16f,
                    label = "Battery",
                    unit = "V",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.pulseWidth,
                    maxValue = 25f,
                    label = "Pulse",
                    unit = "Width",
                    color = GaugeOrange,
                    modifier = Modifier.weight(1f)
                )
            }
             // Row 2
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.coolantTemp.toFloat(),
                    minValue = 0f,
                    maxValue = 150f,
                    label = "Coolant",
                    unit = "°C",
                    color = GaugeGreen,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.sparkLines, // Using spark lines as value
                    maxValue = 60f,
                    label = "Spark",
                    unit = "",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.dutyCycle,
                    maxValue = 100f,
                    label = "Duty",
                    unit = "Cycle",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
            }
             // Row 3
            Row(modifier = Modifier.weight(1f)) {
                 CircularGauge(
                    value = data.iat.toFloat(),
                    minValue = 0f,
                    maxValue = 100f,
                    label = "IAT",
                    unit = "°C",
                    color = GaugeOrange,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.afr,
                    minValue = 10f,
                    maxValue = 20f,
                    label = "AFR",
                    unit = "",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
                 CircularGauge(
                    value = data.maf,
                    maxValue = 200f,
                    label = "MAF",
                    unit = "g/s",
                    color = GaugeTeal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun GraphsContent(data: EngineData) {
     // A grid of graphs
    Column(modifier = Modifier.fillMaxSize()) {
        // Row 1
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = data.rpmHistory,
                label = "RPM",
                unit = "", 
                currentValue = data.rpm.toFloat(),
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Spark Advance History TBD
                label = "Spark Adv",
                unit = "deg", 
                currentValue = data.sparkLines,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             LineGraph(
                dataPoints = listOf(), // Coolant History TBD
                label = "Coolant",
                unit = "°C", 
                currentValue = data.coolantTemp.toFloat(),
                color = GaugeOrange,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
        }
        // Row 2
        Row(modifier = Modifier.weight(1f)) {
             LineGraph(
                dataPoints = data.boostHistory,
                label = "Boost",
                unit = "bar", 
                currentValue = data.boost,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // AFR History TBD
                label = "AFR",
                unit = "", 
                currentValue = data.afr,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             LineGraph(
                dataPoints = listOf(), // IAT History TBD
                label = "IAT",
                unit = "°C", 
                currentValue = data.iat.toFloat(),
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
                currentValue = data.pulseWidth,
                color = GaugeGreen,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
            LineGraph(
                dataPoints = listOf(), // Duty History TBD
                label = "Duty Cycle",
                unit = "%", 
                currentValue = data.dutyCycle,
                color = GaugeTeal,
                modifier = Modifier.weight(1f).padding(4.dp)
            )
             LineGraph(
                dataPoints = listOf(), // MAF History TBD
                label = "MAF",
                unit = "g/s", 
                currentValue = data.maf,
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
        Text("000006 km", color = Color.White)
        Text("005.6 km", color = Color.White)
        
        val dateObj = Date(engineData.timestamp)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd.M.yyyy", Locale.getDefault())
        
        Text(timeFormat.format(dateObj), color = Color.White)
        Text(dateFormat.format(dateObj), color = Color.White)
    }
}
