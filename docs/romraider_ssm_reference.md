# RomRaider SSM Implementation Reference

Quick reference for implementing an SSM (Subaru Select Monitor) DataSource based on RomRaider's codebase.

---

## SSM Protocol Constants
**File**: [SSMProtocol.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/io/protocol/ssm/iso9141/SSMProtocol.java)

```java
HEADER = 0x80
ECU_INIT_COMMAND = 0xBF     // Request ECU capabilities
ECU_INIT_RESPONSE = 0xFF
READ_ADDRESS_COMMAND = 0xA8 // Read parameter values
READ_ADDRESS_RESPONSE = 0xE8
ADDRESS_SIZE = 3            // Each address is 3 bytes
```

**Baud**: 4800, 8N1, timeout 2000ms

---

## Packet Building
**File**: [SSMProtocol.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/io/protocol/ssm/iso9141/SSMProtocol.java#L198-229)

| Method | Purpose |
|--------|---------|
| `constructEcuInitRequest(Module)` | Creates `0xBF` init packet |
| `constructReadAddressRequest(Module, byte[][])` | Creates `0xA8` read request |
| `buildRequest(command, padContent, content...)` | Core packet builder |

**Checksum**: Sum of all bytes & 0xFF (see [SSMChecksumCalculator.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/io/protocol/ssm/iso9141/SSMChecksumCalculator.java))

---

## Response Processing
**File**: [SSMResponseProcessor.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/io/protocol/ssm/iso9141/SSMResponseProcessor.java)

| Method | Purpose |
|--------|---------|
| `filterRequestFromResponse()` | Removes echo (KKL cables echo sent data) |
| `validateResponse()` | Checks header, length, checksum |
| `extractResponseData()` | Gets payload bytes after header (skips 5 bytes) |

---

## ECU Init Response
**File**: [SSMEcuInit.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/comms/query/SSMEcuInit.java)

Init response contains:
- **Bytes 0-2**: Unknown/flags
- **Bytes 3-7**: ECU ID (5 bytes) - becomes ROM ID string
- **Bytes 8+**: Capability flags (which parameters are supported)

---

## Module Addresses
**File**: [logger_METRIC_EN_v370.xml](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/logger_METRIC_EN_v370.xml#L136-143)

| Module | Address | Tester | Description |
|--------|---------|--------|-------------|
| ECU | `0x10` | `0xF0` | Engine Control Unit |
| TCU | `0x18` | `0xF0` | Transmission Control Unit |

---

## Parameter Definitions
**File**: [logger_METRIC_EN_v370.xml](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/logger_METRIC_EN_v370.xml) (40k lines!)

### XML Structure
```xml
<parameter id="P8" name="Engine Speed" desc="P8" ecubyteindex="8" ecubit="0" target="3">
    <address length="2">0x00000E</address>
    <conversions>
        <conversion units="rpm" expr="x/4" format="0" />
    </conversions>
</parameter>
```

### Key Attributes
- `id`: Unique identifier (P1, P2, etc.)
- `name`: Human-readable name
- `address`: ECU memory address (3 bytes)
- `length`: Bytes to read (default 1)
- `ecubyteindex`/`ecubit`: Capability check against init response
- `expr`: Conversion formula (x = raw value)

### Common Parameters
| ID | Name | Address | Length | Unit | Formula |
|----|------|---------|--------|------|---------|
| P2 | Coolant Temp | 0x000008 | 1 | °C | x-40 |
| P7 | MAP | 0x00000D | 1 | kPa | x |
| P8 | Engine Speed | 0x00000E | 2 | rpm | x/4 |
| P9 | Vehicle Speed | 0x000010 | 1 | km/h | x |
| P10 | Ignition Timing | 0x000011 | 1 | deg | (x-128)/2 |
| P11 | Intake Air Temp | 0x000012 | 1 | °C | x-40 |
| P12 | Mass Airflow | 0x000013 | 2 | g/s | x/100 |
| P13 | Throttle % | 0x000015 | 1 | % | x*100/255 |
| P17 | Battery Voltage | 0x00001C | 1 | V | x*8/100 |
| P23 | Knock Correction | 0x000022 | 1 | deg | (x-128)/2 |
| P25 | Boost (Relative) | 0x000024 | 1 | kPa | x-128 |

---

## XML Parsing
**File**: [LoggerDefinitionHandler.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/xml/LoggerDefinitionHandler.java)

SAX parser that builds `EcuParameter` objects from XML. Key methods:
- `startElement()` - Parses parameter/conversion attributes
- `getEcuParameters()` - Returns parsed parameters list

---

## Parameter Data Model
**File**: [EcuParameterImpl.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/EcuParameterImpl.java)

```java
class EcuParameter {
    String id;
    String name;
    String description;
    EcuAddress address;      // Memory address + length
    EcuDataConvertor[] convertors;  // Unit conversions
}
```

**File**: [EcuAddressImpl.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/EcuAddressImpl.java) - Stores 3-byte address + length

---

## Conversion/Expression Evaluation  
**File**: [EcuParameterConvertorImpl.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/EcuParameterConvertorImpl.java)

Uses JEP (Java Expression Parser) for formulas like `x/4`, `(x-128)/2`, etc.

---

## Logger Connection Flow
**File**: [SSMLoggerConnection.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/comms/io/connection/SSMLoggerConnection.java)

1. `ecuInit(callback, module)` - Sends init, gets capabilities
2. `sendAddressReads(queries, module, pollState)` - Reads parameter values repeatedly
3. `protocol.processReadAddressResponses()` - Maps bytes to query objects

---

## Serial Connection
**File**: [SerialConnectionImpl.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/io/serial/connection/SerialConnectionImpl.java)

Uses jSerialComm library. Key methods:
- `write(bytes)` - Send to port
- `read(bytes)` - Read from port
- `readStaleData()` - Clear buffer

---

## Android Implementation Strategy

### Files to Create

1. **`SsmParameter.kt`** - Data class for parameter definition
   ```kotlin
   data class SsmParameter(
       val id: String,
       val name: String,
       val address: Int,      // 3-byte address as Int
       val length: Int = 1,
       val expression: String, // "x/4"
       val unit: String
   )
   ```

2. **`SsmParameterParser.kt`** - Parse `logger_METRIC_EN_v370.xml`
   - Use Android XML parser or include simplified JSON version
   - Load subset of commonly used parameters

3. **`SsmDataSource.kt`** - Main data source (like `LogFileDataSource`)
   - `connect()` - Open USB serial, send init
   - `getEngineData(): Flow<EngineData>` - Continuous parameter reading
   - Build read request for selected parameters
   - Parse response, apply conversions
   - Emit `EngineData` objects

### Read Request Building
```kotlin
fun buildReadRequest(addresses: List<Int>): ByteArray {
    // 0x80 DST SRC LEN 0xA8 0x00 addr1 addr2 ... addrN checksum
    val data = mutableListOf<Byte>()
    data.add(0x80.toByte())
    data.add(0x10.toByte())  // ECU
    data.add(0xF0.toByte())  // Tester
    data.add((2 + addresses.size * 3).toByte())  // Length
    data.add(0xA8.toByte())  // Read command
    data.add(0x00.toByte())  // Padding (single read)
    addresses.forEach { addr ->
        data.add(((addr shr 16) and 0xFF).toByte())
        data.add(((addr shr 8) and 0xFF).toByte())
        data.add((addr and 0xFF).toByte())
    }
    data.add(calculateChecksum(data))
    return data.toByteArray()
}
```

### Expression Evaluation
Use a simple expression evaluator for formulas like `x/4`, `x-40`, `(x-128)/2`.
Consider using [exp4j](https://github.com/fasseg/exp4j) or inline Kotlin evaluation.

---

## XML Definition Files
Located in: `example/RomRaider/definitions/`

| File | Purpose |
|------|---------|
| [logger_METRIC_EN_v370.xml](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/logger_METRIC_EN_v370.xml) | Full parameter definitions (metric units) |
| [logger_IMP_EN_v370.xml](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/logger_IMP_EN_v370.xml) | Imperial units version |
| [logger.dtd](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/logger.dtd) | XML schema definition |
| [ecu_defs.xml](file:///Users/zhukov/git/dash22b/example/RomRaider/definitions/ecu_defs.xml) | ECU ROM definitions (8MB!) |
