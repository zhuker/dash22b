# Plan: Messages Tab with CEL Indicator & DTC Chat View

## Context
The app currently has 3 tabs (Gauges, Graphs, Other). We want a 4th "Messages" tab that acts like a Check Engine Light indicator — the icon turns red when the MIL flag is active, and tapping it reads all DTC codes from the ECU and displays them as chat-style messages from the engine.

The MIL flag already exists in the XML as switch S155 (address `0x000196`, bit 7). DTCs (603 entries) are defined in `<dtcodes>` but not yet parsed by the app.

## Implementation Steps

### 1. Add MIL to always-polled parameters
**File:** `app/src/main/java/com/example/dash22b/data/SsmRepository.kt`
- Add MIL parameter name as always-subscribed in `subscribeToParameters()`:
  ```kotlin
  fun subscribeToParameters(params: Set<String>) {
      _subscribedParams.value = params + setOf("Malfunction Indicator Light (MIL) ON Flag")
  }
  ```
- This costs 1 extra byte per poll cycle — negligible overhead

### 2. Create DTC data model
**New file:** `app/src/main/java/com/example/dash22b/obd/SsmDtcCode.kt`
- Data class with: `id`, `name`, `tmpAddr`, `memAddr`, `bit`, `isTemporary`, `isMemorized`

### 3. Add DTC parsing to SsmLoggerDefinitionParser
**File:** `app/src/main/java/com/example/dash22b/obd/SsmLoggerDefinitionParser.kt`
- Add `parseDtcCodes()` method that parses `<dtcodes>` section (same DOM pattern as existing `parse()`)
- Filter by ECU init response length (from RomRaider): `<56` → up to D256, `<104` → up to D488, else all
- Add `initResponseLength` property to `SsmEcuInit.kt` (`packet.data.size`)
- Refactor XML loading/DOCTYPE stripping into shared private method to avoid duplication

### 4. Add DTC read capability to SsmDataSource
**File:** `app/src/main/java/com/example/dash22b/data/SsmDataSource.kt`
- Add `readDtcCodes(dtcDefinitions: List<SsmDtcCode>): List<SsmDtcCode>` suspend function
- Groups unique tmpAddr and memAddr bytes, reads in 2 batches via `serialManager.readParameters()`
- Uses `CompletableDeferred` pattern to serialize with polling loop (no concurrent serial access):
  - UI sets a deferred request via `requestDtcRead()`
  - Polling loop checks for pending request, pauses polling, executes DTC read, completes the deferred
  - Polling resumes after

### 5. Create DtcRepository
**New file:** `app/src/main/java/com/example/dash22b/data/DtcRepository.kt`
- `dtcState: StateFlow<DtcState>` — sealed class: `Idle`, `Loading`, `Loaded(codes)`, `Error(msg)`
- `milActive: StateFlow<Boolean>` — derived from MIL parameter in engine data
- Method to trigger DTC read coordinating with SsmDataSource

### 6. Wire DI
**Files:**
- `di/AppContainer.kt` — add `dtcRepository` lazy singleton
- `di/LocalDependencies.kt` — add `LocalDtcRepository` CompositionLocal
- `MainActivity.kt` — add to `CompositionLocalProvider`

### 7. Add MIL observation in DashService
**File:** `app/src/main/java/com/example/dash22b/service/DashService.kt`
- In `startSsmPolling()`, add coroutine that observes `ssmRepository.engineData` for MIL value
- Updates `dtcRepository.updateMilStatus()` when MIL value changes

### 8. Add MESSAGES tab to navigation
**File:** `app/src/main/java/com/example/dash22b/ui/DashboardScreen.kt`
- Add `MESSAGES` to `ScreenMode` enum
- Modify `NavItem` to accept optional `iconTint: Color = Color.White` parameter
- Add 4th NavItem in both `BottomNavigationBar` and `NavigationSidebar`:
  - Icon: `Icons.Default.Email`
  - Label: "Messages"
  - iconTint: red when `milActive`, white otherwise
- Add `ScreenMode.MESSAGES -> MessagesContent()` to the content switch

### 9. Create MessagesContent composable
**New file:** `app/src/main/java/com/example/dash22b/ui/components/MessagesContent.kt`
- `LaunchedEffect` triggers DTC read when tab becomes active
- **Loading state:** spinner + "Reading diagnostic codes..."
- **Empty state:** single bubble "All systems normal. No trouble codes detected."
- **DTC list:** `LazyColumn` of chat bubbles:
  - Left-aligned (incoming message style), dark surface bubble
  - Engine icon avatar on left (e.g., `Icons.Default.Build`)
  - DTC code bold (e.g., "P0335"), description below
  - Status chips: "CURRENT" (orange), "STORED" (gray), or both (red)
- Re-reads on tab re-entry (reset to Idle when leaving)

## Files to Create
- `app/src/main/java/com/example/dash22b/obd/SsmDtcCode.kt`
- `app/src/main/java/com/example/dash22b/data/DtcRepository.kt`
- `app/src/main/java/com/example/dash22b/ui/components/MessagesContent.kt`

## Files to Modify
- `app/src/main/java/com/example/dash22b/ui/DashboardScreen.kt`
- `app/src/main/java/com/example/dash22b/obd/SsmLoggerDefinitionParser.kt`
- `app/src/main/java/com/example/dash22b/obd/SsmEcuInit.kt`
- `app/src/main/java/com/example/dash22b/data/SsmDataSource.kt`
- `app/src/main/java/com/example/dash22b/data/SsmRepository.kt`
- `app/src/main/java/com/example/dash22b/service/DashService.kt`
- `app/src/main/java/com/example/dash22b/di/AppContainer.kt`
- `app/src/main/java/com/example/dash22b/di/LocalDependencies.kt`
- `app/src/main/java/com/example/dash22b/MainActivity.kt`

## Key Technical Details

### MIL Switch (S155)
- Address: `0x000196`, bit 7, ecubyteindex 56
- Already parsed as a switch by `SsmLoggerDefinitionParser`
- Expression: `"bit:7"` → returns 1f (CEL on) or 0f (CEL off)

### DTC Address Ranges
- 75 unique tmpaddr bytes: `0x00008E`–`0x0000AD`, `0x0000F0`–`0x0000F3`, `0x000123`–`0x00012A`, etc.
- Corresponding memaddr bytes offset from tmpaddr
- Each byte holds up to 8 DTCs (one per bit)

### Serial Concurrency
- `SsmSerialManager` is NOT thread-safe
- DTC reads must be serialized with the polling loop via `CompletableDeferred`
- Polling pauses during DTC read, resumes after

### SSM Packet Size Limit
- Data length field is 1 byte (max 255)
- 77 addresses × 3 bytes = 231 + 2 header = 233 bytes — fits in one packet
- Split into 2 requests: one for tmpaddr, one for memaddr

## Verification
1. Build the app and verify no compilation errors
2. Check that MIL switch S155 is included in polled parameters (Timber logs)
3. Verify Messages icon appears in nav bar, white by default
4. Test with hardcoded MIL=true to verify icon turns red
5. Test DTC parsing with unit test against real XML (verify count, filtering)
6. Test DTC read flow end-to-end when connected to ECU
