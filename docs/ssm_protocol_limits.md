# SSM Protocol Limits & Performance

Reference for understanding the practical limits of streaming ECU data via SSM protocol.

---

## Packet Size Limits

### Data Length Field: 1 Byte (max 255)

The SSM packet format uses a single byte for `data_length`, limiting payload to ~250 bytes.

```
Packet: 0x80 [dst] [src] [len] [command] [data...] [checksum]
                         ^^^
                    1 byte = max 255
```

---

## Read Address Request Limits

**Command `0xA8`** - Read multiple addresses individually:

```
Request:  0x80 [dst] [src] [len] 0xA8 0x00 [addr1] [addr2] ... [addrN] [checksum]
Response: 0x80 [src] [dst] [len] 0xE8 [val1] [val2] ... [valN] [checksum]
```

### Calculation
- Each address = 3 bytes
- Overhead = 2 bytes (command + padding)
- **Max addresses ≈ (253 - 2) / 3 ≈ 83 addresses per request**

### Response
- Each value = 1 byte (for single-byte params like temp)
- 2-byte params (RPM, MAF) = 2 bytes
- Response limit also ~250 bytes

---

## Read Memory Request Alternative

**Command `0xA0`** - Read contiguous memory block:

```
Request:  0x80 [dst] [src] 0x06 0xA0 0x00 [start_addr_3bytes] [num_bytes-1] [checksum]
Response: 0x80 [src] [dst] [len] 0xE0 [data...] [checksum]
```

- More efficient for consecutive addresses
- RomRaider uses **128 byte max range** for this command
- Only works if your parameters are in contiguous memory (rarely the case)

---

## Baud Rate Bottleneck

SSM over ISO-9141 (K-Line) uses **4800 baud**:

| Metric | Value |
|--------|-------|
| Bits per byte | 10 (8N1 + start/stop) |
| Time per byte | ~2.08 ms |
| Bytes per second | ~480 |

### Example: 10 Parameters

| Direction | Bytes | Time |
|-----------|-------|------|
| Request | 6 + (10 × 3) + 1 = 37 bytes | ~77 ms |
| Response | 6 + 10 + 1 = 17 bytes | ~35 ms |
| **Total** | 54 bytes | **~112 ms** |

**Update rate: ~9 Hz** for 10 parameters

### Example: 30 Parameters

| Direction | Bytes | Time |
|-----------|-------|------|
| Request | 6 + (30 × 3) + 1 = 97 bytes | ~202 ms |
| Response | 6 + 30 + 1 = 37 bytes | ~77 ms |
| **Total** | 134 bytes | **~279 ms** |

**Update rate: ~3.6 Hz** for 30 parameters

---

## Practical Parameter Limits

| Parameters | Approx. Update Rate | Use Case |
|------------|---------------------|----------|
| 5-10 | 8-10 Hz | Real-time gauges |
| 15-20 | 4-6 Hz | Dashboard display |
| 30-40 | 2-3 Hz | Comprehensive logging |
| 50-80 | 1-2 Hz | Full data capture |

---

## Fast Polling Mode

Some ECUs support **continuous polling** (`READ_ADDRESS_CONTINUOUS = 0x01`):

```
Request:  0x80 [dst] [src] [len] 0xA8 0x01 [addresses...] [checksum]
                                      ^^^^
                                  Continuous flag
```

- Send request once
- ECU responds continuously until you send a new request
- RomRaider checks `fastpoll="true"` in module definition
- Reduces request overhead, improves throughput

---

## Recommended Parameters for Real-Time Dashboard

Start with these **10 key parameters** (~8-10 Hz refresh):

| ID | Name | Address | Length | Unit |
|----|------|---------|--------|------|
| P8 | Engine Speed | 0x00000E | 2 | rpm |
| P2 | Coolant Temp | 0x000008 | 1 | °C |
| P25 | Boost (Relative) | 0x000024 | 1 | kPa |
| P13 | Throttle Position | 0x000015 | 1 | % |
| P10 | Ignition Timing | 0x000011 | 1 | deg |
| P23 | Knock Correction | 0x000022 | 1 | deg |
| P11 | Intake Air Temp | 0x000012 | 1 | °C |
| P17 | Battery Voltage | 0x00001C | 1 | V |
| P58 | AFR Sensor #1 | 0x000046 | 1 | λ |
| P36 | Wastegate Duty | 0x000030 | 1 | % |

### Total bytes needed
- Addresses: (9 × 1-byte) + (1 × 2-byte) = 11 data bytes
- Request: 37 bytes (10 params)
- Response: 17 bytes
- **Cycle time: ~112 ms → ~9 Hz**

---

## Optimization Strategies

### 1. Batch by Priority
```kotlin
// High-priority: every cycle
val criticalParams = listOf(P8, P2, P25, P10)  // 4 params

// Low-priority: every 5th cycle
val extendedParams = listOf(P17, P11, P31, ...)  // 10 params

// Alternate: 9 Hz for critical, 2 Hz for extended
```

### 2. Address Sorting
Sort addresses numerically to potentially enable range reads if addresses happen to be contiguous.

### 3. Skip Unchanged Values
For slowly-changing values (coolant temp, battery), only update UI when value changes.

---

## Summary

| Limit | Value |
|-------|-------|
| Max addresses per request | ~83 |
| Max response data bytes | ~250 |
| Baud rate | 4800 |
| Practical real-time params | 10-15 |
| Practical logging params | 30-50 |
