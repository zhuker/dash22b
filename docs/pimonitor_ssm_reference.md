# PiMonitor SSM Implementation Reference

Python implementation of SSM protocol for Raspberry Pi. Simpler and more readable than RomRaider's Java - good for understanding the core protocol.

---

## Project Structure

```
example/PiMonitor/
├── data/                    # XML definition files
│   └── logger_METRIC_EN_v370.xml
└── pimonitor/
    ├── climain.py           # CLI entry point
    ├── PMConnection.py      # Serial connection & packet I/O
    ├── PMPacket.py          # Packet structure & checksum
    ├── PMXmlParser.py       # XML parameter parser
    └── cu/                   # Control Unit classes
        ├── PMCUAddress.py
        ├── PMCUContext.py
        ├── PMCUConversion.py
        ├── PMCUStandardParameter.py
        ├── PMCUFixedAddressParameter.py
        ├── PMCUCalculatedParameter.py
        └── PMCUSwitchParameter.py
```

---

## Serial Connection
**File**: [PMConnection.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/PMConnection.py)

### Connection Properties
```python
port = '/dev/ttyUSB0'
baudrate = 4800
timeout = 2000
writeTimeout = 55
parity = serial.PARITY_NONE
stopbits = serial.STOPBITS_ONE
bytesize = serial.EIGHTBITS
```

### Key Methods

| Method | Purpose |
|--------|---------|
| `open()` | Opens serial port at 4800 baud |
| `close()` | Closes serial port |
| `init(target)` | Sends ECU init (0xBF command) |
| `send_packet(packet)` | Writes packet, reads response, filters echo |
| `read_parameters(parameters)` | Batches multiple address reads |

### Init Flow
```python
def init(self, target):
    # Build init packet: destination=0x10 (ECU), source=0xF0, data=[0xBF]
    request_packet = PMPacket(self.get_destination(target), 0xF0, [0xBF])
    return self.send_packet(request_packet)
```

### Reading Parameters
```python
def read_parameters(self, parameters):
    data = [0xA8, 0x00]  # Read command + padding
    for parameter in parameters:
        address = parameter.get_address().get_address()
        address_len = parameter.get_address().get_length()
        for i in range(0, address_len):
            target_address = address + i
            data.append((target_address & 0xffffff) >> 16)  # High byte
            data.append((target_address & 0xffff) >> 8)     # Mid byte  
            data.append(target_address & 0xff)               # Low byte
    
    request_packet = PMPacket(destination, 0xf0, data)
    return self.send_packet(request_packet)
```

---

## Packet Structure
**File**: [PMPacket.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/PMPacket.py)

### Format
```
[0x80] [dst] [src] [len] [data...] [checksum]
  ^      ^     ^     ^      ^          ^
  |      |     |     |      |          Sum of all bytes & 0xFF
  |      |     |     |      Payload bytes
  |      |     |     Number of data bytes
  |      |     Source (0xF0 = tester)
  |      Destination (0x10 = ECU, 0x18 = TCU)
  Header byte (always 0x80)
```

### Key Constants
```python
_header_byte = 0x80
# Valid response codes
_valid_bytes = [0xFF, 0xA8, 0xE8]  # Init, ReadAddr, ReadResp
```

### Packet Building
```python
def to_bytes(self):
    packet = [0x80, self._dst, self._src, len(self._data)]
    packet.extend(self._data)
    
    checksum = 0
    for b in packet:
        checksum = (checksum + b) & 0xFF
    
    packet.append(checksum)
    return packet
```

### Validation
```python
@classmethod
def is_valid(cls, data):
    # Check header is 0x80
    # Check length matches
    # Verify checksum
    checksum = sum(data[:-1]) & 0xFF
    return checksum == data[-1]
```

---

## ECU Context & ROM ID
**File**: [PMCUContext.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUContext.py)

### Init Response Offsets
```python
RESPONSE_MARK_OFFSET = 4     # 0xFF marker position
RESPONSE_ROM_ID_OFFSET = 8   # ROM ID starts here (5 bytes)
INITIAL_RESPONSE_MIN_LEN = 13
```

### ROM ID Extraction
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

### Parameter Matching
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

---

## Parameter Address
**File**: [PMCUAddress.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUAddress.py)

```python
class PMCUAddress:
    def __init__(self, address, length):
        self._address = address    # 3-byte ECU address as int
        self._length = length      # Bytes to read (1, 2, or 4)
    
    def get_address(self):
        return self._address
    
    def get_length(self):
        return self._length
```

---

## Standard Parameter
**File**: [PMCUStandardParameter.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUStandardParameter.py)

### Properties
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

### Value Extraction
```python
def get_value(self, packet, unit=None):
    # Skip response marker (index 1)
    value_bytes = packet.get_data()[1:1 + self.get_address().get_length()]
    
    # Combine bytes based on length
    address_length = self.get_address().get_length()
    if address_length == 1:
        x = value_bytes[0]
    elif address_length == 2:
        x = (value_bytes[0] << 8) | value_bytes[1]
    elif address_length == 4:
        x = (value_bytes[0] << 24) | (value_bytes[1] << 16) | \
            (value_bytes[2] << 8) | value_bytes[3]
    
    x = float(x)
    
    # Apply conversion expression
    value = eval(expr)  # e.g., "x/4" for RPM
    
    # Format output
    return format_value(value, value_format)
```

### Capability Check
```python
def is_supported(self, init_response_data):
    offset = 5 + self._byte_index  # After header + mark
    cu_byte = init_response_data[offset]
    bit_mask = 1 << self._bit_index
    return (cu_byte & bit_mask) == bit_mask
```

---

## Conversion
**File**: [PMCUConversion.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/cu/PMCUConversion.py)

```python
class PMCUConversion:
    def __init__(self, unit, expr, format):
        self._unit = unit      # "rpm", "C", "kPa", etc.
        self._expr = expr      # "x/4", "x-40", "(x-128)/2"
        self._format = format  # "0", "0.0", "0.00"
```

### Common Expressions
| Parameter | Expression | Unit |
|-----------|------------|------|
| Engine Speed | `x/4` | rpm |
| Coolant Temp | `x-40` | °C |
| MAP | `x` | kPa |
| Boost | `x-128` | kPa |
| Ignition Timing | `(x-128)/2` | deg |
| Throttle | `x*100/255` | % |
| Battery | `x*8/100` | V |

---

## XML Parser
**File**: [PMXmlParser.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/PMXmlParser.py)

Uses Python's `xml.sax` to parse `logger_METRIC_EN_v370.xml`.

### Parsed Element Types
| XML Element | Python Class | Purpose |
|-------------|--------------|---------|
| `<parameter>` | `PMCUStandardParameter` | Standard ECU parameter |
| `<ecuparam>` | `PMCUFixedAddressParameter` | ECU-specific parameter |
| `<switch>` | `PMCUSwitchParameter` | Boolean on/off switch |
| `<conversion>` | `PMCUConversion` | Unit conversion |
| `<address>` | `PMCUAddress` | Memory address |

### Usage
```python
parser = PMXmlParser()
parameters = parser.parse("logger_METRIC_EN_v370.xml")
# Returns set of PMCUParameter objects
```

---

## CLI Example
**File**: [climain.py](file:///Users/zhukov/git/dash22b/example/PiMonitor/pimonitor/climain.py)

### Full Flow
```python
# 1. Parse XML definitions
parser = PMXmlParser()
defined_parameters = parser.parse("logger_METRIC_EN_v352.xml")

# 2. Open serial connection
connection = PMConnection()
connection.open()

# 3. Init ECU
ecu_packet = connection.init(1)  # target=1 for ECU

# 4. Match supported parameters
ecu_context = PMCUContext(ecu_packet, [1, 3])
ecu_parameters = ecu_context.match_parameters(defined_parameters)

# 5. Select parameters to read
engine_speed = find_by_id(ecu_parameters, "P8")
coolant_temp = find_by_id(ecu_parameters, "P2")

# 6. Read loop
while True:
    packets = connection.read_parameters([engine_speed, coolant_temp])
    rpm = engine_speed.get_value(packets[0])
    temp = coolant_temp.get_value(packets[1])
    print(f"RPM: {rpm}, Coolant: {temp}")
```

---

## Target Addresses

| Target | Value | Module |
|--------|-------|--------|
| ECU | `0x10` | Engine Control Unit |
| TCU | `0x18` | Transmission Control Unit |
| Tester | `0xF0` | Diagnostic tool (us) |

```python
def get_destination(target):
    if target == 1:
        return 0x10  # ECU
    if target == 2:
        return 0x18  # TCU
    if target == 3:
        return 0x10  # Both (use ECU)
```

---

## Data Files
**Location**: `example/PiMonitor/data/`

| File | Description |
|------|-------------|
| `logger_METRIC_EN_v370.xml` | Latest parameter definitions (metric) |
| `data.pkl` | Cached parsed parameters (pickle) |

---

## Android Implementation Mapping

| PiMonitor (Python) | Android (Kotlin) |
|--------------------|------------------|
| `PMConnection` | `SsmSerialManager` |
| `PMPacket` | `SsmPacket` |
| `PMCUStandardParameter` | `SsmParameter` |
| `PMCUAddress` | `address: Int, length: Int` |
| `PMCUConversion` | `SsmConversion` |
| `PMCUContext` | `SsmEcuContext` |
| `PMXmlParser` | `SsmParameterParser` |

### Key Simplifications for Android
1. **Skip capability check initially** - Just use known working parameters
2. **Hardcode common parameters** - Don't need full XML parser for MVP
3. **Simple expression eval** - Use regex or exp4j for `x-40`, `x/4`, etc.
4. **Batch reads** - Request multiple addresses per packet (more efficient)
