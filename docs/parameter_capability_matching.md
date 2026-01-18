# Parameter Capability Matching in SSM Implementations

This document explains how SSM implementations determine which parameters/monitors are available for a specific ECU.

---

## Overview

Both RomRaider (Java) and PiMonitor (Python) use **capability bit checking** to filter parameters based on the ECU's init response. The init response contains capability flags that indicate which parameters the ECU supports.

---

## RomRaider Implementation

### How It Works

RomRaider filters parameters **during XML parsing** by checking capability bits from the init response.

### Key Components

**1. EcuInit Interface**
- **File**: [EcuInit.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/comms/query/EcuInit.java)
- Provides access to ECU ID and init response bytes

```java
public interface EcuInit {
    String getEcuId();           // ECU ROM ID string
    byte[] getEcuInitBytes();     // Full init response for capability checking
}
```

**2. SSMEcuInit Implementation**
- **File**: [SSMEcuInit.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/comms/query/SSMEcuInit.java#L38-L40)
- Extracts ECU ID from bytes 3-7 of init response
- Stores full init response for later capability checks

```java
public SSMEcuInit(byte[] ecuInitBytes) {
    this.ecuInitBytes = ecuInitBytes;
    byte[] ecuIdBytes = new byte[5];
    arraycopy(ecuInitBytes, 3, ecuIdBytes, 0, 5);  // Extract ROM ID
    ecuId = asHex(ecuIdBytes);
}
```

**3. Capability Checking During XML Parsing**
- **File**: [LoggerDefinitionHandler.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/xml/LoggerDefinitionHandler.java)
- Constructor accepts `EcuInit` object for filtering
- Checks each parameter's capability bits during parsing

**Parameter Filtering** (lines 344-354):
```java
if (ecuByteIndex == null || ecuBit == null || ecuInit == null ||
    isSupportedParameter(ecuInit, ecuByteIndex, ecuBit)) {
    // Add parameter to the list
    if (convertorList.isEmpty()) {
        convertorList.add(new EcuParameterConvertorImpl());
    }
    EcuParameter param = new EcuParameterImpl(
        id, name, desc, address, group, subgroup, groupsize,
        convertorList.toArray(new EcuDataConvertor[convertorList.size()]));
    params.add(param);
    ecuDataMap.put(param.getId(), param);
}
```

**Capability Check Method** (lines 462-472):
```java
private boolean isSupportedParameter(EcuInit ecuInit, String ecuByteIndex, String ecuBit) {
    byte[] ecuInitBytes = ecuInit.getEcuInitBytes();
    int index = Integer.parseInt(ecuByteIndex);
    if (index < ecuInitBytes.length) {
        byte[] bytes = new byte[1];
        System.arraycopy(ecuInitBytes, index, bytes, 0, 1);
        return (bytes[0] & 1 << Integer.parseInt(ecuBit)) > 0;
    } else {
        return false;
    }
}
```

### XML Parameter Attributes

Each parameter in the XML has capability check attributes:

```xml
<parameter id="P8" name="Engine Speed" desc="P8"
           ecubyteindex="8" ecubit="0" target="3">
    <address length="2">0x00000E</address>
    <conversions>
        <conversion units="rpm" expr="x/4" format="0" />
    </conversions>
</parameter>
```

- `ecubyteindex`: Which byte in the init response to check (offset from start)
- `ecubit`: Which bit in that byte (0-7)
- If both are present, the bit must be set for the parameter to be supported

### Filtering Logic

| Condition | Result |
|-----------|--------|
| `ecubyteindex` and `ecubit` are **null** | Parameter **always included** (universal) |
| `ecuInit` is **null** | Parameter **always included** (no ECU to check against) |
| Bit check **passes** | Parameter **included** |
| Bit check **fails** | Parameter **skipped** |

### ECU-Specific Parameters (`<ecuparam>`)

RomRaider also supports parameters with different addresses for different ROM IDs:

**XML Example**:
```xml
<ecuparam id="E1" name="ECU-Specific Param" desc="E1">
    <ecu id="A2WC522S">
        <address>0x001234</address>
    </ecu>
    <ecu id="A2ZC900T">
        <address>0x005678</address>
    </ecu>
</ecuparam>
```

**Filtering Logic** (lines 366-377):
```java
if (ecuInit != null && ecuAddressMap.containsKey(ecuInit.getEcuId())) {
    // Use the address specific to this ECU ID
    EcuParameter param = new EcuParameterImpl(
        id, name, desc, ecuAddressMap.get(ecuInit.getEcuId()),
        group, subgroup, groupsize,
        convertorList.toArray(new EcuDataConvertor[convertorList.size()]));
    params.add(param);
}
```

---

## PiMonitor Implementation

### How It Works

PiMonitor **separates parsing and filtering** into distinct steps:
1. Parse all parameters from XML
2. Explicitly call `ecu_context.match_parameters(defined_parameters)`

### Key Components

**1. PMCUContext**
- **File**: [PMCUContext.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUContext.py)
- Stores init response packet
- Provides `match_parameters()` method

**ROM ID Extraction** (lines 142-156):
```python
def get_rom_id(self):
    data = self._packet.to_bytes()

    # Verify init response marker
    if data[4] != 0xFF:
        raise Exception("not valid init response")

    # Extract 5-byte ROM ID (bytes 8-12)
    rom_id = ((data[8] << 32) |
              (data[9] << 24) |
              (data[10] << 16) |
              (data[11] << 8) |
              data[12]) & 0xFFFFFFFFFF

    return hex(rom_id).upper()
```

**Parameter Matching** (lines 161-171):
```python
def match_parameters(self, parameters):
    matched = []
    rom_id = self.get_rom_id()

    for parameter in parameters:
        if parameter.get_target() not in self._targets:
            continue
        if parameter.is_supported(self._packet.to_bytes()):
            matched.append(parameter)

    return matched
```

**2. PMCUStandardParameter**
- **File**: [PMCUStandardParameter.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUStandardParameter.py)
- Each parameter has `_byte_index` and `_bit_index` attributes
- Implements `is_supported()` method

**Properties** (lines 199-206):
```python
self._id = "P8"           # Parameter ID
self._name = "Engine Speed"
self._desc = "P8"
self._byte_index = 8      # Capability check byte
self._bit_index = 0       # Capability check bit
self._target = 3          # 1=ECU, 2=TCU, 3=both
self._address = PMCUAddress(0x00000E, 2)
self._conversions = [PMCUConversion("rpm", "x/4", "0")]
```

**Capability Check** (lines 236-240):
```python
def is_supported(self, init_response_data):
    offset = 5 + self._byte_index  # After header + mark
    cu_byte = init_response_data[offset]
    bit_mask = 1 << self._bit_index
    return (cu_byte & bit_mask) == bit_mask
```

### Usage Flow

```python
# 1. Parse XML definitions (all parameters)
parser = PMXmlParser()
defined_parameters = parser.parse("logger_METRIC_EN_v352.xml")

# 2. Init ECU and get response
connection = PMConnection()
ecu_packet = connection.init(1)  # target=1 for ECU

# 3. Create context with init response
ecu_context = PMCUContext(ecu_packet, [1, 3])

# 4. Filter parameters based on capability bits
ecu_parameters = ecu_context.match_parameters(defined_parameters)

# 5. Use only the matched (supported) parameters
engine_speed = find_by_id(ecu_parameters, "P8")
coolant_temp = find_by_id(ecu_parameters, "P2")
```

---

## Comparison: RomRaider vs PiMonitor

| Aspect | RomRaider | PiMonitor |
|--------|-----------|-----------|
| **When filtering happens** | During XML parsing (integrated) | After parsing (separate step) |
| **Entry point** | `LoggerDefinitionHandler` constructor accepts `EcuInit` | Explicit `ecu_context.match_parameters()` call |
| **Storage** | Only stores supported parameters | Stores all, then returns filtered list |
| **Capability check** | `isSupportedParameter(ecuInit, ecuByteIndex, ecuBit)` | `parameter.is_supported(init_response_data)` |
| **ECU-specific params** | `<ecuparam>` tag with ECU ID map | `PMCUFixedAddressParameter` class |
| **Separation of concerns** | Tightly coupled (parsing + filtering) | Loosely coupled (parse, then filter) |

### Byte Offset Difference

**Important**: The capability byte indexing is slightly different:

- **RomRaider**: Uses `ecubyteindex` as direct offset into init response bytes
- **PiMonitor**: Adds 5 to `_byte_index` to skip header + marker: `offset = 5 + self._byte_index`

Both refer to the same physical bytes in the init response, just with different base offsets.

---

## Init Response Structure

```
[0x80] [dst] [src] [len] [0xFF] [cap0] [cap1] [cap2] ... [romid0-4] ... [checksum]
   ^      ^     ^     ^     ^      ^      ^      ^           ^
   |      |     |     |     |      |      |      |           ROM ID (5 bytes)
   |      |     |     |     |      |      |      Capability bytes
   |      |     |     |     |      Capability byte 0
   |      |     |     |     Init response marker
   |      |     |     Data length
   |      |     Source (ECU = 0x10)
   |      Destination (tester = 0xF0)
   Header byte

RomRaider: ecubyteindex=8 → checks byte at offset 8
PiMonitor: byte_index=8 → checks byte at offset 5+8=13 (different base!)
```

**Capability Flags Location**:
- Start after the 0xFF marker (byte 4)
- Bytes 5+ contain capability flags
- Each bit indicates whether a specific parameter is supported

---

## Android Implementation Recommendations

### Option 1: RomRaider-Style (Filter During Parsing)

```kotlin
class SsmParameterParser(private val ecuInit: SsmEcuInit?) {
    fun parse(xmlFile: String): List<SsmParameter> {
        val parameters = mutableListOf<SsmParameter>()

        // Parse XML and filter inline
        xml.parameters.forEach { xmlParam ->
            if (isSupportedParameter(xmlParam)) {
                parameters.add(createParameter(xmlParam))
            }
        }

        return parameters
    }

    private fun isSupportedParameter(xmlParam: XmlParameter): Boolean {
        // If no capability check defined, always include
        if (xmlParam.ecuByteIndex == null || xmlParam.ecuBit == null) {
            return true
        }

        // If no ECU init response, include everything
        val initBytes = ecuInit?.initBytes ?: return true

        // Check capability bit
        val index = xmlParam.ecuByteIndex.toInt()
        if (index >= initBytes.size) return false

        val byte = initBytes[index]
        val bitMask = 1 shl xmlParam.ecuBit.toInt()
        return (byte.toInt() and bitMask) != 0
    }
}
```

### Option 2: PiMonitor-Style (Parse Then Filter)

```kotlin
// 1. Parse all parameters
val allParameters = SsmParameterParser.parseAll("logger_METRIC_EN_v370.xml")

// 2. Get ECU init response
val initResponse = ssmConnection.sendInit()
val ecuContext = SsmEcuContext(initResponse)

// 3. Filter supported parameters
val supportedParameters = ecuContext.matchParameters(allParameters)
```

### Recommendation

**Use Option 1 (RomRaider-style)** for Android:
- More memory efficient (doesn't store unsupported parameters)
- Single-pass processing
- Simpler API for callers (no separate filtering step)
- Better for resource-constrained mobile devices

---

## Example Parameter Capability Bits

From `logger_METRIC_EN_v370.xml`:

| Parameter ID | Name | ecubyteindex | ecubit | Meaning |
|--------------|------|--------------|--------|---------|
| P2 | Coolant Temp | 8 | 1 | Byte 8, bit 1 |
| P7 | MAP | 8 | 6 | Byte 8, bit 6 |
| P8 | Engine Speed | 8 | 0 | Byte 8, bit 0 |
| P9 | Vehicle Speed | 8 | 2 | Byte 8, bit 2 |
| P10 | Ignition Timing | 8 | 3 | Byte 8, bit 3 |
| P12 | Mass Airflow | 8 | 4 | Byte 8, bit 4 |
| P13 | Throttle % | 8 | 5 | Byte 8, bit 5 |

Most common parameters use **byte 8** for capability checking, with different bits for each parameter.

---

## References

- [RomRaider LoggerDefinitionHandler.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/definition/xml/LoggerDefinitionHandler.java)
- [RomRaider SSMEcuInit.java](file:///Users/zhukov/git/dash22b/example/RomRaider/src/main/java/com/romraider/logger/ecu/comms/query/SSMEcuInit.java)
- [PiMonitor PMCUContext.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUContext.py)
- [PiMonitor PMCUStandardParameter.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUStandardParameter.py)
- [RomRaider Reference](file:///Users/zhukov/git/dash22b/docs/romraider_ssm_reference.md)
- [PiMonitor Reference](file:///Users/zhukov/git/dash22b/docs/pimonitor_ssm_reference.md)
