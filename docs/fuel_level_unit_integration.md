# Fuel Level Gauge: Voltage → Gallons Integration

How the GC8 fuel-sender cubic from `fuel_sensor_calibration.md` was wired into
the gauge stack so the "Fuel Level" gauge can be displayed in gallons or percent
in addition to its raw voltage.

## Design choice: per-parameter calibration, not a generic unit conversion

The cubic is **specific to this car's float sender**, not a universal V→gal
conversion (a different sender would have a different curve). So the work
deliberately did NOT extend `UnitConverter` with a `VOLTS → GALLONS` factor —
that would have made every voltage gauge in the app (battery, MAF, IAT sensor,
etc.) offer gallons in the unit dropdown and would have applied the fuel
formula to e.g. a 13.8 V battery reading.

Instead, a new `ParameterCalibration` object keyed by **parameter name** holds
sensor-specific calibrations. Three call sites in the existing pipeline consult
it before falling back to default behaviour:

1. **Value conversion** (`DashboardScreen.kt`) — calibration runs first; if it
   returns non-null, that value is used; otherwise `vwu.to(targetUnit)` does
   the generic conversion.
2. **Compatible-units dropdown** (`ParameterBottomSheet.kt`) — calibration
   contributes extra units to the dropdown only for parameters it knows about.
3. **Min/max gauge range** (`ParameterRegistry.kt`) — calibration supplies the
   per-unit display range so the arc auto-scales (0–15.9 gal in gallons, 0–100
   in percent, 0.3–4.3 in volts).

Adding another calibrated sensor in the future = one extra entry in
`ParameterCalibration`. Nothing else needs to change.

## The three new display modes

The cubic outputs *gallons to fill* (empty space). The user wanted all three
common interpretations available, selectable per gauge:

| DisplayUnit       | Formula                                         | Gauge reads     |
|-------------------|-------------------------------------------------|-----------------|
| `GALLONS`         | `TANK_CAPACITY − cubic(V)`                      | Gallons remaining |
| `GALLONS_TO_FILL` | `cubic(V)`                                      | Gallons needed to fill up |
| `PERCENT`         | `100 × (TANK_CAPACITY − cubic(V)) / TANK_CAPACITY` | % full |

`TANK_CAPACITY = 15.9 gal` (factory spec for 2001 Impreza 2.5RS). The cubic
output is clamped to `[0, TANK_CAPACITY]` to absorb the formula's ±0.6 gal
error near the endpoints.

`PERCENT` was reused from the existing enum rather than introducing
`PERCENT_FUEL`, since the value is parameter-disambiguated (Fuel Level + PERCENT
means fuel %; Throttle + PERCENT still means throttle position).

## Files changed

### New
- **`app/src/main/java/com/example/dash22b/data/ParameterCalibration.kt`**
  - `voltageToGallonsToFill / Remaining / PercentFuel` — the cubic and its
    derivations
  - `convert(paramName, value, from, to)` — returns the calibrated value or
    null to fall through
  - `getExtraUnits(paramName, baseUnit)` — extra dropdown units for known
    parameters
  - `getRange(paramName, targetUnit)` — per-unit display range

### Modified
- **`app/src/main/java/com/example/dash22b/data/UnitConverter.kt`** — added
  `GALLONS("gal")` and `GALLONS_TO_FILL("gal to fill")` to the `DisplayUnit`
  enum. Deliberately not added to `getCompatibleUnits()`; gating is per-parameter
  via `ParameterCalibration.getExtraUnits`.
- **`app/src/main/java/com/example/dash22b/ui/DashboardScreen.kt`**
  - In `DynamicCircularGauge`: try `ParameterCalibration.convert(...)` before
    the generic `vwu.to(targetUnit)`.
  - At the bottom-sheet call site: look up the long-pressed gauge's current
    `GaugeConfig` and pass `parameterName` + `displayUnitName` into the sheet.
- **`app/src/main/java/com/example/dash22b/data/ParameterRegistry.kt`** —
  `getMinExpected` / `getMaxExpected` consult `ParameterCalibration.getRange`
  before the existing `minMaxMap` lookup.
- **`app/src/main/java/com/example/dash22b/ui/components/ParameterBottomSheet.kt`**
  - New optional `currentParamName` / `currentUnitName` params; `selectedParam`
    and `selectedUnit` are seeded from them so long-press opens with the
    current parameter already selected and the unit dropdown ready.
  - `compatibleUnits` now merges in `ParameterCalibration.getExtraUnits(...)`,
    so only "Fuel Level" sees the new gallon options.

## UX fix bundled in

Previously the bottom sheet always started with no parameter selected: to change
just the unit on an existing gauge you had to re-pick the parameter from the
list first. Now long-press → sheet opens already showing "Selected: Fuel Level"
with the unit dropdown active → one tap to switch Volts → Gallons → Confirm.
Picking a different parameter from the list still works exactly as before.

## Known limitation

When `Fuel Level` is displayed as `VOLTS`, the gauge arc reads "low" when the
tank is full (the sender outputs 0.3V at full, 4.3V at empty). Range is left as
the natural 0.3–4.3 V so debugging the raw sensor still makes sense; pick
`GALLONS` or `PERCENT` to get a conventional fuel-gauge feel.

## Where the formula lives

The cubic and the tank capacity are constants in `ParameterCalibration.kt`. If
the sender is replaced or refit later, update them there. The source data and
fit notes are in [`fuel_sensor_calibration.md`](fuel_sensor_calibration.md).
