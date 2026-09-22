# Fast poll (SSM continuous read) — implementation plan

Status: **implemented on branch `ssm-fastpoll`, not yet verified on the car.**
Unit tests: `app/src/test/java/com/example/dash22b/obd/SsmFastPollTest.kt`.
Where the code went beyond this plan:

- Serial I/O goes through `obd/SsmLink.kt` (`UsbSsmLink` wraps the USB port),
  so the state machine runs against a fake ECU in plain JVM tests.
- `writeAddress()` (ECU reset) and `sendInit()` break the stream too, not just
  `readParameters()` and `disconnect()`.
- An empty subscription stops the stream, because nothing reads it while idle.
- A read timeout mid-stream is handled like a bad frame (replay once, break,
  re-request). The Python port stays in STATE_1 on a timeout.
- The fallback to single reads resets on every reconnect.
- The break is 21 ms (RomRaider's formula, the value the Python port proved on
  the car), not the ~5 ms suggested in step 4.
- The on/off switch is `FAST_POLL_ENABLED` in `SsmDataSource`.
- `HistoryStore.DEFAULT_CAPACITY` went from 12k to 36k samples. Its capacity is
  counted in samples, and at 22 Hz 12k would have covered only ~9 minutes.
  36k is ~20 min at 30 Hz.
- **Graph redraws are deliberately not throttled yet**, so we can see first
  what it looks like. Every sample bumps `historyStore.version` and each visible
  graph re-queries (`DashboardScreen.kt`, the `remember(version, ...)` block).
  At ~22 Hz, a graph on "All" scans all 36k samples per redraw, under the lock
  the recorder writes with. If graphs stutter or the head unit runs hot, cap
  redraws at 5-10 Hz. One bucket on "All" is ~2.5 s wide, so faster redraws
  show nothing new there.
- After a drive, `grep "Poll:"` in the debug log. Once a minute it shows the
  mode, the rate in Hz and how many stream starts, bad frames, replays and
  unrecoverable frames there were. Individual events (restarts, bad frames with
  their bytes, the fallback) are logged by `SsmSerialManager` and
  `SsmDataSource` as they happen.

Reference implementation: **`~/git/r22b/r22b/ssm/serial_manager.py`** — a working
Python port, proven against this car on 2026-09-22. Port *from that*, not from
first principles; it already has the state machine, the validation and the
teardown, and its structure deliberately mirrors this module's.

## What this buys

SSM2 has a continuous-read mode selected by the padding byte after `0xA8`. Send
the address list once with `0x01` and the ECU streams responses until the line
is broken, so every sample after the first pays only for the response leg.

At 4800 baud the request is the expensive half: each address costs 3 bytes out
and returns 1 byte back. Measured on this car (ROM `3D12594006`, Pi + FT232R
KKL cable, ignition on):

| parameters | slow (`0xA8 0x00`) | fast (`0xA8 0x01`) | gain |
|---|---|---|---|
| 13 (the gauge set) | 6.3 Hz | 21.8 Hz | 3.5x |
| 10 | 7.0 Hz | 25.0 Hz | 3.6x |
| 1 × 4-byte | — | 43.0 Hz | — |

The gain grows with parameter count, because only the request scales with it.

Zero frame errors across 171 consecutive fast samples, and every value matched
the slow-mode reading of the same parameter.

## The ECU supports it

Not an inference — captured on the wire. After one `0xA8 0x01` request, with
nothing further sent:

```
req      80 10 F0 0B A8 01 00 00 08 00 00 0E 00 00 0F 59
t=0.3s   <echo> 80 F0 10 04 E8 52 00 00 BE  80 F0 10 04 E8 52 00 00 BE  ...
t=0.6s   ... still streaming, ~430 B/s = ~48 frames/s for 3 addresses ...
--- line break ---
         7 bytes (tail of the in-flight frame), then silence
```

RomRaider agrees: `fastpoll="true"` on the K-line engine ECU module,
`"false"` on the TCU, in `r22b/ssm/PiMonitor/data/logger_METRIC_EN_v370.xml`
(the attribute is on `<module>`, so it is not ECU-ID-specific).

## Current state in this codebase

Single-read only. The padding byte is hardcoded:

```kotlin
// obd/SsmSerialManager.kt:280-281
data.add(SsmPacket.CMD_READ_ADDRESS)  // Read command (0xA8)
data.add(0x00.toByte())                // Padding (single read mode)
```

`SsmPacket.kt` has no `READ_ADDRESS_CONTINUOUS` constant, and there is no poll
state anywhere in the module.

The good news: `readParameters` already computes `requestSize`,
`expectedResponseSize` and `expectedTotalRead` up front and reads exactly that
many bytes (`SsmSerialManager.kt:271-275`, `:318-331`). That is the discipline
fast poll needs; the change is a split of that logic by state, not a rewrite.

**The break is available.** The stream stops only on a serial line break, and
`CommonUsbSerialPort.java:334` throws `UnsupportedOperationException` for
`setBreak` — but the FTDI driver implements it for real
(`FtdiSerialDriver.java:400`), and the car's cable is an FT232R.

## Design

Two states, following RomRaider (`SSMProtocol`, `SerialConnectionManager`,
`QueryManagerImpl`):

| | STATE_0 | STATE_1 |
|---|---|---|
| writes request | yes, padding `0x01` | **no** |
| reads | `requestSize + responseSize` (echo + response) | `responseSize` |
| entered when | first sample, list change, bad frame | after one good STATE_0 sample |

Everything else is about leaving the stream safely.

## Steps

### 1. `obd/SsmPacket.kt`

```kotlin
const val READ_ADDRESS_ONCE: Byte = 0x00
const val READ_ADDRESS_CONTINUOUS: Byte = 0x01
```

### 2. `obd/SsmSerialManager.kt` — state

Add `private var pollState = STATE_0`, `private var lastResponse: ByteArray? = null`,
`private var streaming = false`, and `private var streamKey: List<Int>? = null`
(the address list the ECU is currently streaming).

### 3. `obd/SsmSerialManager.kt` — `readParametersFast(parameters)`

Mirror `read_parameters_fast` in the Python. Order matters:

1. Build the address list; `responseSize = addresses.size + 6`.
2. **If `pollState == STATE_1` and the address list differs from `streamKey`,
   call `clearLine()`** (which resets to STATE_0). See gotcha 1.
3. STATE_0: write the request with `READ_ADDRESS_CONTINUOUS`, read
   `requestSize + responseSize`, drop the leading `requestSize` echo bytes,
   set `streaming = true` and `streamKey = addresses`.
   STATE_1: read `responseSize`, write nothing.
4. Validate the frame: length, `frame[1] == SOURCE_DIAG`,
   `frame[2] in (0x10, 0x18)`, `frame[4] == RSP_READ_ADDRESS`, checksum.
5. Valid → `lastResponse = frame`, `pollState = STATE_1`.
   Invalid → capture `lastResponse`, `clearLine()`, and return the captured
   sample **only if its length matches `responseSize`**; otherwise fail. See
   gotcha 2.

### 4. `obd/SsmSerialManager.kt` — `clearLine()`

```kotlin
port.setBreak(true)
Thread.sleep(BREAK_MS)   // ~5 ms is ample at 4800 baud
port.setBreak(false)
// then drain: read with a short timeout until nothing arrives
```

Reset `streaming`, `pollState = STATE_0`, `lastResponse = null`,
`streamKey = null`.

Call it from `disconnect()`, from `readParameters()` (the slow path) when
`streaming`, and on every list change.

### 5. `data/SsmDataSource.kt` — use it

In the poll loop, call `readParametersFast` instead of `readParameters` for
gauge reads. Keep `readParameters` as a fallback: if fast poll errors more than
a few times in a row, fall back and log it, rather than stalling the gauges.

Remove the per-iteration `delay(POLL_DELAY_MS)` from the fast path entirely;
keep it only for the idle, slow and error branches. See gotcha 5.

## Gotchas — each of these bit the Python port

### 1. A parameter-list change must break the stream first

**This is the one that matters most here.** `getParametersToRead()` is
re-evaluated every loop iteration (`SsmDataSource.kt:514`) and driven by
`_subscribedParams` (`:127`, `:153`), which the preset system changes at
runtime. If the list changes while the ECU is streaming the old layout, the old
frames are read against the new layout and **every value silently decodes into
the wrong parameter** — no exception, no checksum failure, just plausible
numbers in the wrong gauges.

In the Python port this produced identical bytes (`3f 00 00 00`) for 38
different addresses in a row, and it looked like real data until cross-checked.
Regression tests:
`test_changing_the_parameter_list_restarts_the_stream`,
`test_a_stale_frame_from_another_list_is_not_replayed`
(`~/git/r22b/tests/test_offline.py`).

### 2. Only replay a last-good sample of the same length

The bad-frame path returns the previous sample so one glitch costs a stale
reading rather than the session. But a sample from a *different* parameter list
decodes into nonsense, so length must be checked before replaying — and note
that `clearLine()` clears `lastResponse`, so capture it **before** calling.

### 3. DTC reads change the address list

`readDtcCodes` → `readAddressBytes` (`SsmDataSource.kt:386`) chunks addresses
30 at a time and calls `readParameters` for each chunk — several different
address lists in a row, mid-session. The slow path must break the stream before
its first request (step 4 covers this), otherwise the first chunk reads
streamed gauge frames.

### 4. OBD-II takeovers must break the stream before `disconnect()`

`readReadiness`, `runObdCapture` and the sweep all call
`serialManager.disconnect()` to hand the K-line to `Obd2SerialManager` at
10400 baud (`SsmDataSource.kt:~415`). A still-streaming ECU would be clocking
4800-baud frames onto the wire while the OBD stack tries its init. Handling
this in `disconnect()` covers all callers.

### 5. Never sleep while the ECU is streaming — `POLL_DELAY_MS` is a correctness bug, not just a throughput one

`POLL_DELAY_MS = 50L` (`SsmDataSource.kt:38`, "Target ~20Hz") is a `delay()` on
every loop iteration (`:555`). In slow mode that is harmless: nothing happens on
the wire until the next request. In fast mode the ECU keeps sending whether or
not we read, and nothing reads the port between our blocking `port.read()`
calls (no `SerialInputOutputManager`). Frames pile up in the FT232R's 256-byte
RX FIFO while we sleep:

- a 13-parameter frame is 19 bytes, about 40 ms at 4800 baud;
- a loop is about 40 ms of read plus 50 ms of sleep, so about two frames arrive
  for each one consumed, and the backlog grows by about one frame per loop;
- 256 bytes is about 13 frames. After about a second the gauges show data half
  a second old, and then the FIFO overflows and drops bytes, which misframes
  the stream. That means checksum failures, `clearLine()` and a re-request, in
  a loop.

This comes from how the FIFO works; it was not measured on the car. The
arithmetic is simple enough to trust, and a regression test can pin it down.
Lowering the delay to "about 0-5 ms" or `max(0, target - elapsed)` is not the
fix. Any sleep in the streaming path adds backlog. The rule:

1. **Fast path, successful sample: no delay at all.** The blocking read is the
   pacing, because the ECU cannot send faster than the line carries.
2. **Keep a delay only where there is no stream:** the idle branch when nothing
   is subscribed (`:518`), the slow fallback path, and the error path as a
   short backoff (errors break the stream anyway). Rename the constant to
   `IDLE_POLL_DELAY_MS` so it does not drift back into the streaming path.
3. **Never lower the rate by sleeping.** If a lower rate is wanted (smaller
   CSV, less CPU), read every frame and emit every Nth one, or break the stream
   and go idle.
4. **Do not copy `readParameters`' post-write `Thread.sleep(50)`**
   (`SsmSerialManager.kt:315`) into STATE_1. It is harmless once in STATE_0
   because the bytes it delays are the echo and the first frame. STATE_1 writes
   nothing, so it has nothing to wait for.
5. **Keep leftover bytes.** `readParameters` reads into a 256-byte buffer and
   drops everything past the expected count (`bytesToCopy = minOf(...)`,
   `SsmSerialManager.kt:328`). That is right for request/response, where
   nothing should follow. In fast mode those bytes are the start of the next
   frame. Dropping them misaligns every frame after it, so keep them in a
   carry-over buffer and read from it before calling `port.read()`.
   `clearLine()` empties it.

**The same backlog can come from downstream.** `getEngineData()` is
`.flowOn(Dispatchers.IO)`, which puts a 64-element channel between the reader
and `DashService`'s collector (CSV write, history, repository update, on every
sample). A short stall is absorbed. A stall longer than about 3 s at 22 Hz
fills the channel, `emit()` suspends the reader, and the backlog above happens
again. That is unlikely, but if it shows up, fix it on the collector side, not
by conflating (the CSV wants every sample).

### 6. 4-byte parameters work, and `storagetype="float"` is real

Confirmed on the car: a 4-byte parameter is just four consecutive addresses,
and fast poll handles it (43 Hz for one alone). Decode as **float32**, not
uint32 — e.g. E36 Target Boost reads `43 e5 40 48`, which is 61.13 kPa as a
float and 1.5x10^8 as an integer. This holds even for conversions whose XML
entry omits the `storagetype` attribute.

Values that read sensibly with **ignition on, engine off** — useful for testing
without running the car:

| id | name | value | cross-check |
|---|---|---|---|
| E51 | Manifold Absolute Pressure (4-byte) | 100.46 kPa abs | 1-byte P7 MAP = 100 |
| E38 | Throttle Plate Opening Angle (4-byte) | 5.80 % | 1-byte P13 = 5.88 |
| E91 | A/F Sensor #1 (4-byte) | 14.70 AFR | 1-byte P58 = 1.00 λ |
| E36 | Target Boost (4-byte) | 61.13 kPa abs | E35 = E36 − E51 exactly |

Most other 4-byte parameters read 0 with the engine off.

## Testing

Mirror the Python tests — they run with no hardware against a fake serial port
that echoes the request and then streams frames, with injectable corruption.
See `FakeEcu` and the SSM block in `~/git/r22b/tests/test_offline.py` (22 tests).
The ones worth porting first:

- the request's padding byte is `0x01`
- ten samples produce exactly **one** write
- consecutive samples yield **different** frames (the stream is being consumed)
- multi-byte values split across parameters correctly
- a corrupt frame replays the last good sample, breaks the line, and re-sends
- a corrupt *first* frame raises rather than inventing a value
- changing the parameter list breaks the stream and re-requests
- `disconnect()` breaks the stream
- a `read()` that returns more than one frame keeps the extra bytes: the next
  sample is the *next* frame, correctly aligned (fake port delivers 1.5 frames
  per read)
- a reader that falls behind gets the oldest frame, not the newest: this pins
  down why the streaming loop must not sleep (gotcha 5)

On the car, the check that matters is not the Hz — it is that **fast and slow
report the same values for the same parameters**. A fast path that silently
misframes looks like a 3.5x win until someone reads the gauges.
`~/git/r22b/tools/ssm_fastpoll_bench.py` prints both columns side by side and
is the model for what to verify.

## Reference

- Python port: `~/git/r22b/r22b/ssm/serial_manager.py`, tests in
  `~/git/r22b/tests/test_offline.py`, bench in
  `~/git/r22b/tools/ssm_fastpoll_bench.py`
- RomRaider (`~/git/RomRaider`, 1.1.0): request byte at
  `io/protocol/ssm/iso9141/SSMProtocol.java:214`; per-state response sizing at
  `io/protocol/ssm/iso9141/SSMLoggerProtocol.java:70-74`; "write only in
  STATE_0" and frame validation at
  `io/serial/connection/SerialConnectionManager.java:72-102`; the line break at
  `:132`; the state machine at
  `logger/ecu/comms/manager/QueryManagerImpl.java:304-334`
- Capability declaration:
  `~/git/r22b/ssm/PiMonitor/data/logger_METRIC_EN_v370.xml:137`
