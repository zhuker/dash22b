# Implementation Plan: Preset System for Dashboard

## Overview
Add a "Preset" system that allows users to save/load gauge configurations. Critical constraint: only poll parameters currently displayed (SSM protocol runs at 4800 baud, limiting refresh rates).

---

## UI Design

### Preset Label (between large gauges)
- Small clickable label: `"Default View ▾"` or `"Untitled ▾"`
- Positioned in empty space between the two large gauges and 9 smaller gauges
- Tapping opens Modal Bottom Sheet

### Bottom Sheet Contents
1. **Save Section** (only if "Untitled"): Text field + save button
2. **Preset List**: Scrollable list with:
   - Checkmark on active preset
   - Tap to load
   - Overflow menu (⋮) for Rename/Delete

### "Untitled" Behavior
- When user long-clicks a gauge and changes the parameter → label becomes "Untitled"
- Tracks that configs have diverged from the loaded preset

---

## Data Models

### New File: `data/Preset.kt`
```kotlin
@Serializable
data class Preset(
    val id: String,           // UUID
    val name: String,         // User-visible name
    val gaugeConfigs: List<GaugeConfig>,
    val createdAt: Long,
    val modifiedAt: Long
)

// Move from DashboardScreen.kt
@Serializable
data class GaugeConfig(
    val id: Int,              // 0-10
    val parameterName: String, // Parameter name (e.g., "Engine Speed")
    val displayUnit: Unit? = null  // Optional unit override (null = use parameter default)
)
```

---

## Architecture

### State Flow
```
PresetRepository (SharedPreferences + JSON)
       ↓
PresetManager (StateFlow<PresetState>)
       ↓
   ┌───┴───┐
   ↓       ↓
DashboardScreen   SsmDataSource
(UI updates)      (subscribes to displayed params only)
```

### PresetState
```kotlin
sealed class PresetState {
    data class Saved(val preset: Preset) : PresetState()
    data class Untitled(val configs: List<GaugeConfig>, val basePresetId: String?) : PresetState()
}
```

---

## Files to Create

### 1. `data/Preset.kt`
- `Preset` data class
- `GaugeConfig` data class (move from DashboardScreen)

### 2. `data/PresetRepository.kt`
Persistence layer using SharedPreferences + kotlinx.serialization:
```kotlin
class PresetRepository(context: Context) {
    fun getAllPresets(): List<Preset>
    fun savePreset(preset: Preset)
    fun deletePreset(id: String)
    fun renamePreset(id: String, newName: String)
    fun getLastActivePresetId(): String?
    fun setLastActivePresetId(id: String?)
}
```

### 3. `data/PresetManager.kt`
Business logic + state:
```kotlin
class PresetManager(private val repository: PresetRepository) {
    val presetState: StateFlow<PresetState>
    val allPresets: StateFlow<List<Preset>>
    val currentConfigs: StateFlow<List<GaugeConfig>>  // Derived
    val subscribedParameters: StateFlow<Set<String>>  // For SsmDataSource

    fun loadPreset(id: String)
    fun updateGaugeConfig(gaugeId: Int, parameterName: String)  // → Untitled
    fun saveCurrentAsPreset(name: String): Preset
    fun renamePreset(id: String, newName: String)
    fun deletePreset(id: String)
}
```

### 4. `ui/components/PresetBottomSheet.kt`
Modal bottom sheet with:
- Save section (visible when Untitled)
- Scrollable preset list
- Rename/Delete via dropdown menu

### 5. `ui/components/PresetLabel.kt`
Clickable label composable showing preset name or "Untitled"

---

## Files to Modify

### 1. `ui/DashboardScreen.kt`
- Remove local `gaugeConfigs` state → use `PresetManager.currentConfigs`
- Add `PresetLabel` between large gauges in both portrait and landscape layouts
- Add `PresetBottomSheet` with show/hide state
- Connect gauge parameter changes to `PresetManager.updateGaugeConfig()`
- Subscribe to parameters via LaunchedEffect

**Portrait layout change (lines 509-530):**
```kotlin
Row(...) {
    DynamicCircularGauge(/* gauge 0 */)

    // NEW: Preset label in the middle
    PresetLabel(
        state = presetState,
        onClick = { showPresetSheet = true }
    )

    DynamicCircularGauge(/* gauge 1 */)
}
```

**Landscape layout change (lines 561-583):**
```kotlin
Column(/* left column with large gauges */) {
    DynamicCircularGauge(/* gauge 0 */)

    // NEW: Preset label between gauges
    PresetLabel(state = presetState, onClick = { showPresetSheet = true })

    DynamicCircularGauge(/* gauge 1 */)
}
```

### 2. `data/SsmDataSource.kt`
Add parameter subscription:
```kotlin
class SsmDataSource(context: Context) {
    // NEW: Which parameters to poll
    private val _subscribedParams = MutableStateFlow<Set<String>>(emptySet())

    fun subscribeToParameters(params: Set<String>) {
        _subscribedParams.value = params
    }

    fun getEngineData(): Flow<EngineData> = flow {
        // ... existing connection logic ...

        while (true) {
            // Filter parameters to only those subscribed
            val paramsToRead = parameters.filter {
                _subscribedParams.value.contains(it.name)
            }

            if (paramsToRead.isEmpty()) {
                delay(POLL_DELAY_MS)
                continue
            }

            val response = serialManager.readParameters(paramsToRead)
            // ... parse with paramsToRead instead of all parameters ...
        }
    }
}
```

### 3. `di/AppContainer.kt`
Add new dependencies:
```kotlin
val presetRepository: PresetRepository by lazy {
    PresetRepository(context)
}

val presetManager: PresetManager by lazy {
    PresetManager(presetRepository)
}
```

### 4. `di/LocalDependencies.kt`
Add CompositionLocal:
```kotlin
val LocalPresetManager = staticCompositionLocalOf<PresetManager> {
    error("PresetManager not provided")
}
```

### 5. `MainActivity.kt`
Provide PresetManager in CompositionLocalProvider

### 6. `app/build.gradle.kts`
Add kotlinx-serialization:
```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
```

---

## Default Preset
On first launch, create "Default View" preset with current hardcoded configs:
- Gauge 0: Engine Speed
- Gauge 1: Vehicle Speed
- Gauge 2-10: Boost, Battery Voltage, Throttle, Coolant Temp, Ignition Timing, Knock Correction, Intake Air Temp, MAP, Mass Airflow

---

## Parameter Subscription Flow

1. User loads preset or modifies gauge → `PresetManager.currentConfigs` updates
2. `subscribedParameters` derives unique parameter names from configs
3. `DashboardScreen` observes `subscribedParameters` via LaunchedEffect
4. Calls `SsmDataSource.subscribeToParameters(params)`
5. `SsmDataSource` polls only those parameters
6. Fewer parameters = faster refresh rate (e.g., 5 params at ~10Hz vs 11 params at ~6Hz)

---

## Implementation Order

1. **Add kotlinx-serialization dependency** to build.gradle.kts
2. **Create data models** - Preset.kt with Preset and GaugeConfig
3. **Create PresetRepository** - SharedPreferences persistence
4. **Create PresetManager** - State management and business logic
5. **Update DI** - AppContainer, LocalDependencies, MainActivity
6. **Create UI components** - PresetLabel, PresetBottomSheet
7. **Update DashboardScreen** - Integrate preset system, remove local state
8. **Update SsmDataSource** - Add parameter subscription
9. **Test end-to-end** - Load/save presets, verify parameter filtering

---

## Verification

1. **UI Test**: Long-click gauge → change parameter → label shows "Untitled"
2. **Save Test**: Open sheet → type name → save → appears in list with checkmark
3. **Load Test**: Tap different preset → gauges update → label shows preset name
4. **Persistence Test**: Kill app → reopen → same preset active
5. **Subscription Test**: With ECU connected, check Logcat for reduced parameter count in requests
6. **Refresh Rate Test**: With 5 params, should see ~10Hz; with 11 params, ~6Hz
