# Refactoring Plan: Singleton Objects to Dependency Injection

## Summary
Convert `ParameterRegistry` and `TpmsRepository` from Kotlin singleton `object`s to regular classes with constructor-based dependency injection. Use manual DI with an `AppContainer` pattern (no framework like Hilt/Koin needed for this small project).

## Scope

### Will Refactor:
1. **ParameterRegistry** - Primary target, holds mutable state (`definitions` map)
2. **TpmsRepository** - Holds mutable `StateFlow`, shared between Service and UI

### Will NOT Refactor (stateless utilities):
- `UnitConverter` - Pure functions, no state
- `SsmExpressionEvaluator` - Pure functions, no state

---

## Implementation Steps

### Step 1: Convert ParameterRegistry to Class
**Modify:** `app/src/main/java/com/example/dash22b/data/ParameterRegistry.kt`

- Change `object ParameterRegistry` → `class ParameterRegistry`
- Keep static factory methods via `companion object`:

```kotlin
class ParameterRegistry private constructor(
    private val definitions: SortedMap<String, ParameterDefinition>
) {
    companion object {
        fun fromCsv(assetLoader: AssetLoader): ParameterRegistry { ... }
        fun fromHardcodedSsm(): ParameterRegistry { ... }
        fun fromXml(inputStream: InputStream): ParameterRegistry { ... }
    }

    fun getDefinition(accessportName: String): ParameterDefinition? { ... }
    fun getAllDefinitions(): List<ParameterDefinition> { ... }
}
```

- Remove `isInitialized` flag - each factory creates a fresh instance
- Move initialization logic into factory methods, return new instance

### Step 2: Convert TpmsRepository to Class
**Modify:** `app/src/main/java/com/example/dash22b/data/TpmsRepository.kt`

- Change `object TpmsRepository` → `class TpmsRepository`
- No other changes needed (just `object` → `class`)

### Step 3: Create AppContainer
**New file:** `app/src/main/java/com/example/dash22b/di/AppContainer.kt`

```kotlin
class AppContainer(context: Context) {
    val assetLoader: AssetLoader = AndroidAssetLoader(context)

    val parameterRegistry: ParameterRegistry by lazy {
        ParameterRegistry.fromHardcodedSsm()  // Default to SSM mode
    }

    val tpmsRepository: TpmsRepository by lazy {
        TpmsRepository()
    }
}
```

### Step 4: Create CompositionLocal Providers
**New file:** `app/src/main/java/com/example/dash22b/di/LocalDependencies.kt`

```kotlin
val LocalParameterRegistry = staticCompositionLocalOf<ParameterRegistry> { ... }
val LocalTpmsRepository = staticCompositionLocalOf<TpmsRepository> { ... }
```

### Step 5: Update DashApplication
**Modify:** `app/src/main/java/com/example/dash22b/DashApplication.kt`

- Add `lateinit var appContainer: AppContainer`
- Initialize in `onCreate()` before other operations

### Step 6: Update MainActivity
**Modify:** `app/src/main/java/com/example/dash22b/MainActivity.kt`

- Wrap `setContent` with `CompositionLocalProvider` to provide dependencies

### Step 7: Update Consumers

| File | Change |
|------|--------|
| `SsmDataSource.kt` | Add `parameterRegistry: ParameterRegistry` constructor param, remove init block |
| `LogFileDataSource.kt` | Add `parameterRegistry: ParameterRegistry` constructor param, remove init block |
| `DashboardScreen.kt` | Use `LocalParameterRegistry.current` and `LocalTpmsRepository.current` |
| `TpmsService.kt` | Get `tpmsRepository` from `DashApplication.appContainer` |

### Step 8: Update Tests
**Modify:** `app/src/test/java/com/example/dash22b/data/LogFileDataSourceTest.kt`

- Create test-specific `ParameterRegistry` instances instead of using global singleton

---

## Files Summary

### New Files (2):
- `app/src/main/java/com/example/dash22b/di/AppContainer.kt`
- `app/src/main/java/com/example/dash22b/di/LocalDependencies.kt`

### Modified Files (9):
- `app/src/main/java/com/example/dash22b/data/ParameterRegistry.kt`
- `app/src/main/java/com/example/dash22b/data/TpmsRepository.kt`
- `app/src/main/java/com/example/dash22b/data/SsmDataSource.kt`
- `app/src/main/java/com/example/dash22b/data/LogFileDataSource.kt`
- `app/src/main/java/com/example/dash22b/DashApplication.kt`
- `app/src/main/java/com/example/dash22b/MainActivity.kt`
- `app/src/main/java/com/example/dash22b/ui/DashboardScreen.kt`
- `app/src/main/java/com/example/dash22b/service/TpmsService.kt`
- `app/src/test/java/com/example/dash22b/data/LogFileDataSourceTest.kt`

---

## Verification

1. **Build:** Run `./gradlew assembleDebug` - should compile without errors
2. **Tests:** Run `./gradlew test` - existing tests should pass
3. **Manual test:** Launch app on device/emulator, verify:
   - Dashboard loads with gauge data
   - Parameter selection dialog shows all parameters
   - TPMS data displays correctly
