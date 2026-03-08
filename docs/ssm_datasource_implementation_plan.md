# Implementation Plan: SSM Real-Time Data Source

## Overview
Create an `SsmDataSource` that provides real-time ECU data via SSM protocol over USB serial, following the same architecture pattern as `LogFileDataSource`. Start with 8-15 hardcoded key parameters for smooth real-time display while keeping the parameter registry future-proof for full XML parser integration.

---

## Architecture Analysis

### Existing Patterns
- **Data Source Pattern:** `LogFileDataSource` returns `Flow<EngineData>`, uses `ParameterRegistry`
- **USB Infrastructure:** `SsmSerialManager` handles USB connection, `SsmPacket` handles protocol encoding/decoding
- **Data Model:** `EngineData` has typed fields (rpm, boost, etc.) + generic `values: Map<String, ValueWithUnit>`
- **Parameter Registry:** Currently loads CSV with Accessport naming conventions

### Design Decisions

**1. Unified Parameter Registry with Factory Methods**
- Refactor `ParameterRegistry` to support multiple parameter sources via static factory methods
- `ParameterRegistry.fromCsv()` - Existing CSV/Accessport data (log file playback)
- `ParameterRegistry.fromHardcodedSsm()` - Hardcoded SSM P-code parameters (live ECU data)
- `ParameterRegistry.fromXml()` - Future XML-based parameters
- All sources work through the same interface, populate `EngineData.values` map
- No changes needed to `ParameterSelectionDialog` or other consumers

**2. Direct DataSource Pattern (Not Service)**
- `SsmDataSource` manages its own `SsmSerialManager` instance
- Returns `Flow<EngineData>` with continuous polling loop
- Flow cancellation handles cleanup naturally
- Simpler than TpmsService pattern for initial implementation

**3. Simple Expression Evaluator**
- No external library needed for limited pattern set: `x/4`, `x-40`, `(x-128)/2`, `x*100/255`
- Regex-based pattern matching with fallback to identity

---

## Implementation Plan

### Phase 1: Core SSM Parameter System

#### File: `/app/src/main/java/com/example/dash22b/obd/SsmParameter.kt`
Create SSM parameter data model:
```kotlin
data class SsmParameter(
    val id: String,           // "P8", "P2", etc.
    val name: String,         // "Engine Speed", "Coolant Temp"
    val address: Int,         // 0x00000E (3-byte address as Int)
    val length: Int,          // 1, 2, or 4 bytes
    val expression: String,   // "x/4", "x-40", etc.
    val unit: String          // "rpm", "°C", etc.
)
```

**Key method:** `parseValue(bytes: ByteArray, offset: Int): Int`
- Parse 1, 2, or 4-byte big-endian values from response data
- Handle byte masking (0xFF for unsigned)

#### File: `/app/src/main/java/com/example/dash22b/obd/SsmHardcodedParameters.kt`
Hardcoded SSM parameter definitions:
```kotlin
object SsmHardcodedParameters {
    val parameters = listOf(
        SsmParameter("P8", "Engine Speed", 0x00000E, 2, "x/4", "rpm"),
        SsmParameter("P2", "Coolant Temp", 0x000008, 1, "x-40", "°C"),
        SsmParameter("P25", "Boost", 0x000024, 1, "x-128", "kPa"),
        SsmParameter("P13", "Throttle", 0x000015, 1, "x*100/255", "%"),
        SsmParameter("P10", "Ignition Timing", 0x000011, 1, "(x-128)/2", "deg"),
        SsmParameter("P23", "Knock Correction", 0x000022, 1, "(x-128)/2", "deg"),
        SsmParameter("P11", "Intake Air Temp", 0x000012, 1, "x-40", "°C"),
        SsmParameter("P17", "Battery Voltage", 0x00001C, 1, "x*8/100", "V"),
        // Optional additions:
        SsmParameter("P7", "MAP", 0x00000D, 1, "x", "kPa"),
        SsmParameter("P9", "Vehicle Speed", 0x000010, 1, "x", "km/h"),
        SsmParameter("P12", "Mass Airflow", 0x000013, 2, "x/100", "g/s")
    )
}
```

#### File: `/app/src/main/java/com/example/dash22b/obd/SsmExpressionEvaluator.kt`
Simple expression parser:
```kotlin
object SsmExpressionEvaluator {
    fun evaluate(expression: String, x: Int): Float
}
```

**Supported patterns:**
- `x` → identity
- `x+N`, `x-N` → addition/subtraction
- `x*N`, `x/N` → multiplication/division
- `(x+N)/M`, `(x-N)/M` → parenthesized operations

**Implementation:** Regex pattern matching with clear fallback to identity if unrecognized.

---

### Phase 2: USB Serial Communication Extension

#### File: `/app/src/main/java/com/example/dash22b/obd/SsmSerialManager.kt`
Add method for reading multiple parameters in a single batch request:

```kotlin
fun readParameters(parameters: List<SsmParameter>): SsmPacket? {
    // Build read request packet
    // Command: 0xA8 (read address)
    // Data: [0xA8, 0x00, addr1_h, addr1_m, addr1_l, addr2_h, ...]

    // For multi-byte parameters (length > 1), request each byte separately
    // Example: P8 (Engine Speed, length=2, addr=0x00000E)
    //   → Request addresses: 0x00000E, 0x00000F

    // Send packet, read response with echo filtering
    // Response format: [0x80, 0xF0, 0x10, len, 0xE8, value1, value2, ..., checksum]
    // Return parsed SsmPacket or null on error
}
```

**Key details:**
- Use existing echo filtering logic from `sendInit()`
- Batch all addresses into single request (more efficient than individual reads)
- Handle timeouts and checksum validation
- Return null on errors for graceful degradation

#### File: `/app/src/main/java/com/example/dash22b/obd/SsmPacket.kt`
Add constants:
```kotlin
const val CMD_READ_ADDRESS: Byte = 0xA8.toByte()
const val RSP_READ_ADDRESS: Byte = 0xE8.toByte()
```

---

### Phase 3: SSM Data Source

#### File: `/app/src/main/java/com/example/dash22b/data/SsmDataSource.kt`
Main data source implementation:

```kotlin
class SsmDataSource(private val context: Context) {
    private val serialManager = SsmSerialManager(context)
    private val parameters = SsmHardcodedParameters.parameters

    init {
        // Initialize ParameterRegistry with SSM parameters
        ParameterRegistry.fromHardcodedSsm()
    }

    fun getEngineData(): Flow<EngineData> = flow {
        // 1. Connection loop with exponential backoff retry
        connectWithRetry()

        // 2. Continuous polling loop
        var history = EngineData()
        while (true) {
            try {
                val response = serialManager.readParameters(parameters)
                if (response != null) {
                    val engineData = parseResponse(response, history)
                    history = engineData
                    emit(engineData)
                } else {
                    Timber.w("Read timeout, retrying")
                }
                delay(50) // ~20Hz target
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Error reading parameters")
                delay(1000)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun parseResponse(
        packet: SsmPacket,
        previousData: EngineData
    ): EngineData {
        // Parse response bytes into values
        // Apply expressions using SsmExpressionEvaluator
        // Map to EngineData fields
        // Update history lists (last 50 samples)
    }
}
```

**Data Flow:**
1. Connect to ECU via USB serial
2. Send batched read request for all parameters
3. Parse response bytes (skip 0xE8 marker, read values sequentially)
4. Apply conversion expressions
5. Map to `EngineData.values` and typed fields
6. Emit via Flow
7. Repeat at ~50ms intervals (20Hz target)

**Error Handling:**
- Connection failures: Exponential backoff retry (1s → 2s → 4s → max 10s)
- Read timeouts: Log warning, continue polling
- Checksum errors: Skip packet, continue
- Flow cancellation: Disconnect serial manager

**Unit Conversions:**
- SSM returns metric (°C, kPa)
- `EngineData` may expect imperial (°F, bar/psi)
- Convert in `parseResponse()`:
  - Coolant: `°C * 9/5 + 32 → °F`
  - Boost: `kPa * 0.01 → bar` (or `(kPa - 101.3) * 0.145 → psi` for gauge pressure)

---

### Phase 4: UI Integration (Optional)

The SsmDataSource follows the same interface as LogFileDataSource, so it can be dropped in as a replacement:

```kotlin
@Composable
fun DashboardScreen() {
    val context = LocalContext.current

    // Use SsmDataSource instead of LogFileDataSource
    val dataSource = remember { SsmDataSource(context) }

    val dataFlow = remember(dataSource) {
        dataSource.getEngineData()
    }
    val engineData by dataFlow.collectAsState(initial = EngineData())

    // ... existing dashboard rendering
}
```

**Note:** No toggle UI needed - just swap the data source implementation directly.

---

## Parameter Registry Refactoring

### Current Issue
`ParameterRegistry` is hardcoded to load from CSV. Need to support multiple parameter sources (CSV, hardcoded SSM, future XML) without changing consumers.

### Solution: Factory Methods

**Refactor `ParameterRegistry` to support multiple sources:**
```kotlin
object ParameterRegistry {
    private var definitions = sortedMapOf<String, ParameterDefinition>()
    private var isInitialized = false

    // Existing method - keep for backward compatibility
    fun initialize(assetLoader: AssetLoader) {
        fromCsv(assetLoader)
    }

    // Factory method 1: CSV (existing Accessport format)
    fun fromCsv(assetLoader: AssetLoader): ParameterRegistry {
        if (isInitialized) return this
        // ... existing CSV loading logic ...
        isInitialized = true
        return this
    }

    // Factory method 2: Hardcoded SSM parameters
    fun fromHardcodedSsm(): ParameterRegistry {
        if (isInitialized) return this

        SsmHardcodedParameters.parameters.forEach { ssmParam ->
            val def = ParameterDefinition(
                id = ssmParam.id,
                type = "float",
                unit = ssmParam.unit,
                name = ssmParam.name,
                description = ssmParam.id,
                minExpected = "0",
                maxExpected = "100",
                accessportName = ssmParam.name
            )
            definitions[ssmParam.name.lowercase()] = def
        }

        isInitialized = true
        return this
    }

    // Factory method 3: XML (future)
    fun fromXml(inputStream: InputStream): ParameterRegistry {
        // Parse logger_METRIC_EN_v370.xml
        // Build ParameterDefinition objects
        // Future implementation
        isInitialized = true
        return this
    }

    // Existing API remains unchanged
    fun getDefinition(accessportName: String): ParameterDefinition? { ... }
    fun getAllDefinitions(): List<ParameterDefinition> { ... }
}
```

**Usage in LogFileDataSource:**
```kotlin
init {
    ParameterRegistry.fromCsv(assetLoader)  // Or use existing initialize()
}
```

**Usage in SsmDataSource:**
```kotlin
init {
    ParameterRegistry.fromHardcodedSsm()
}
```

**No changes needed** to `ParameterSelectionDialog` or any other consumers - they continue using `ParameterRegistry.getAllDefinitions()` and `ParameterRegistry.getDefinition()`.

### Benefits
1. **No breaking changes:** Existing code continues to work
2. **Simple:** Single registry, multiple ways to populate it
3. **Future-proof:** Easy to add `fromXml()` later
4. **No interface complexity:** Avoid creating unnecessary abstractions

---

## Critical Files

### New Files to Create
1. `/app/src/main/java/com/example/dash22b/obd/SsmParameter.kt` - SSM parameter data model
2. `/app/src/main/java/com/example/dash22b/obd/SsmHardcodedParameters.kt` - Hardcoded SSM parameter list
3. `/app/src/main/java/com/example/dash22b/obd/SsmExpressionEvaluator.kt` - Expression parser
4. `/app/src/main/java/com/example/dash22b/data/SsmDataSource.kt` - Main data source

### Files to Modify
1. `/app/src/main/java/com/example/dash22b/obd/SsmSerialManager.kt` - Add `readParameters()` method
2. `/app/src/main/java/com/example/dash22b/obd/SsmPacket.kt` - Add read command constants
3. `/app/src/main/java/com/example/dash22b/data/ParameterRegistry.kt` - Add static factory methods for different parameter sources

---

## Testing & Verification

### Unit Tests
1. **SsmExpressionEvaluator:**
   - Test `x/4` with input 7000 → 1750.0
   - Test `x-40` with input 90 → 50.0
   - Test `(x-128)/2` with input 120 → -4.0
   - Test `x*100/255` with input 128 → 50.2

2. **SsmParameter.parseValue():**
   - 1-byte: `[0xE8, 0x5A]` at offset 1 → 90
   - 2-byte: `[0xE8, 0x1B, 0x58]` at offset 1 → 7000 (0x1B58)
   - 4-byte: Big-endian parsing

### Integration Testing
1. **Connection Test:**
   - Connect USB cable to ECU
   - Verify USB permission request appears
   - Check Logcat for "Connected to ECU" and ROM ID

2. **Parameter Reading:**
   - Enable live data mode
   - Verify gauges update at ~10-20 Hz
   - Check value ranges:
     - RPM: 700-1000 at idle, up to 7000 under load
     - Coolant: 80-95°C when warm
     - Battery: 13.5-14.5V with engine running
     - Boost: -100 to 0 kPa at idle (vacuum)
     - Throttle: 0-5% at idle, 100% at WOT

3. **Stress Testing:**
   - Run for 10+ minutes continuously
   - Monitor memory usage (Android Profiler)
   - Test disconnect/reconnect cable during operation
   - Verify graceful reconnection with exponential backoff

4. **Error Scenarios:**
   - Disconnect cable → should show connection lost, retry automatically
   - Turn off ignition → ECU stops responding, handle timeout gracefully
   - Checksum errors → log and skip, continue polling

### End-to-End Verification
1. Start with log file playback (verify existing functionality works)
2. Toggle to live ECU mode
3. Verify all gauges display real-time values
4. Check that RPM, boost, coolant match physical gauges/OBD-II scanner
5. Verify history graphs update smoothly
6. Toggle back to log playback (verify switching works)

---

## Future Enhancements

### XML Parser Integration
The `ParameterRegistry` is designed to support XML loading:

```kotlin
fun loadFromXml(inputStream: InputStream) {
    val parser = SsmXmlParser()  // Use Android XmlPullParser or SAX
    parameters = parser.parse(inputStream)
}
```

**XML Parser would:**
- Read `logger_METRIC_EN_v370.xml` from assets or external storage
- Parse `<parameter>` elements with all attributes
- Support capability checking via `ecubyteindex`/`ecubit` attributes
- Build `SsmParameter` objects dynamically
- Enable 300+ parameters instead of hardcoded 8-15

### Service-Based Architecture
If background logging is needed:
- Create `EcuDataService` similar to `TpmsService`
- Run as foreground service with notification
- Update central `EcuDataRepository` (like `TpmsRepository`)
- UI observes via StateFlow
- Enables logging while app is backgrounded

### Advanced Features
- **Parameter selection:** UI to choose which parameters to poll
- **Data logging:** Write values to CSV/SQLite for later analysis
- **Live graphing:** Real-time plots of boost, AFR, knock over time
- **Alerts:** Threshold warnings for critical parameters (coolant > 100°C, knock detected)
- **ROM switching:** Support different ECU ROMs with parameter address variations

---

## Performance Considerations

**Polling Rate:**
- USB serial at 4800 baud: ~600 bytes/sec theoretical
- Typical packet: ~50 bytes (request + response for 8-15 parameters)
- Target: 10-20 Hz achievable
- Actual: Depends on cable latency (~50ms round-trip typical)

**Memory:**
- History: 50 samples × 2 parameters = 400 bytes
- Each EngineData: ~1KB with all fields
- Total overhead: < 100KB

**CPU:**
- Expression evaluation: Simple arithmetic, negligible
- Main overhead: USB I/O handled by native library

---

## SSM Protocol Reference

### Read Address Command (0xA8)
**Request Format:**
```
[0x80, 0x10, 0xF0, len, 0xA8, 0x00, addr1_h, addr1_m, addr1_l, addr2_h, ..., checksum]
  ^     ^     ^     ^     ^     ^     ----3-byte address----
  |     |     |     |     |     Padding (single read mode)
  |     |     |     |     Read address command
  |     |     |     Data length (2 + num_addresses * 3)
  |     |     Source (0xF0 = diagnostic tool)
  |     Destination (0x10 = ECU)
  Header (always 0x80)
```

**Response Format:**
```
[0x80, 0xF0, 0x10, len, 0xE8, value1, value2, ..., checksum]
  ^     ^     ^     ^     ^     ------parameter values------
  |     |     |     |     Read response marker
  |     |     |     Data length
  |     |     Destination (our address, swapped)
  |     Source (ECU)
  Header
```

### Batching Strategy
- Request all 8-15 parameters in single packet
- Multi-byte parameters (length=2): Request consecutive addresses
  - Example: P8 (Engine Speed) at 0x00000E, length 2
  - Request addresses: 0x00000E, 0x00000F
- Response contains values in same order as request
- Skip first byte (0xE8 marker), then read values sequentially

---

## References
- [PiMonitor SSM Reference](pimonitor_ssm_reference.md) - Python implementation patterns
- [RomRaider SSM Reference](romraider_ssm_reference.md) - Java implementation details
- [Parameter Capability Matching](parameter_capability_matching.md) - ECU capability checking
- Existing codebase:
  - `LogFileDataSource.kt` - Data source pattern
  - `SsmSerialManager.kt` - USB serial communication
  - `SsmPacket.kt` - Protocol encoding/decoding
