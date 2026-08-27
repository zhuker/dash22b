# Plan: Make Graphs Follow Gauge Configuration

## Summary
Replace hardcoded graphs with dynamic graphs that automatically follow the gauge configuration from indices 2-10 (the 9 smaller gauges in the 3x3 grid).

## Current State
- **11 gauges** (IDs 0-10): Configurable via `GaugeConfig` and `PresetManager`
- **9 graphs**: Hardcoded in `GraphsContent()` with fixed parameter names

## Goal
Graphs should automatically display the same parameters as their corresponding gauges (IDs 2-10), respecting the user's display unit preference.

---

## Implementation

### File: [DashboardScreen.kt](app/src/main/java/com/example/dash22b/ui/DashboardScreen.kt)

#### 1. Update `GraphsContent()` signature (line 566)

Add `gaugeConfigs` parameter:
```kotlin
fun GraphsContent(
    data: EngineData,
    history: EngineDataHistory,
    gaugeConfigs: List<GaugeConfig>
)
```

#### 2. Update call sites to pass `gaugeConfigs`

- **Line 152** (portrait): `GraphsContent(engineData, history, gaugeConfigs)`
- **Line 189** (landscape): `GraphsContent(engineData, history, gaugeConfigs)`

#### 3. Add `DynamicLineGraph()` composable (before `GraphsContent`)

Create a new composable following the `DynamicCircularGauge` pattern:
- Handles disabled gauges ("None" parameter) with placeholder
- Looks up parameter definition from registry
- Applies unit conversion to history and current value
- Uses parameter name as label

#### 4. Rewrite `GraphsContent()` body (lines 566-675)

Replace hardcoded graphs with dynamic loop:
- Iterate gauge IDs 2-10 in 3x3 grid (3 rows of 3)
- Use `DynamicLineGraph` for each cell
- Colors cycle by column: Green, Teal, Orange

---

## Gauge ID to Graph Position Mapping

| Gauge ID | Grid Position | Color |
|----------|---------------|-------|
| 2 | Row 1, Col 1 | Green |
| 3 | Row 1, Col 2 | Teal |
| 4 | Row 1, Col 3 | Orange |
| 5 | Row 2, Col 1 | Green |
| 6 | Row 2, Col 2 | Teal |
| 7 | Row 2, Col 3 | Orange |
| 8 | Row 3, Col 1 | Green |
| 9 | Row 3, Col 2 | Teal |
| 10 | Row 3, Col 3 | Orange |

Each graph displays whatever parameter is configured for that gauge ID in the current preset.

---

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Disabled gauge (`"None"`) | Show placeholder graph with "—" label, dark gray color |
| Missing parameter data | Show empty graph (0 value, no history) |
| Unit conversion | Convert both history list and current value to target unit |

---

## Verification

1. Build the app: `./gradlew assembleDebug`
2. Run on device/emulator
3. Test scenarios:
   - Switch to Graphs view - should show same parameters as gauges 2-10
   - Long-press a gauge (e.g., ID 5) and change parameter - verify graph updates
   - Set a gauge to "None" - verify graph shows placeholder
   - Change a gauge's display unit (e.g., C to F) - verify graph converts values
   - Load a different preset - verify graphs update accordingly
