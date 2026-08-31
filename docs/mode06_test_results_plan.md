# Mode $06 on-board test results — implementation plan

Read generic OBD-II service $06 ("request on-board monitoring test results") over the
K-line and surface the ECM's own per-test measured values and thresholds.

## Why

The car's EVAP readiness bit reads complete, but FTP logging shows the ECM's **0.02-inch
small-leak diagnosis never finishes** — it cancels during Mode B, roughly 0.12 kPa short
of its −3.05 kPa target, on every attempt (3 of 3 on 2026-08-30). The 0.04-inch diagnosis
completes and passes on the same drives. A single readiness bit cannot distinguish those
two situations, so `ObdReadiness` reports "ready" either way.

Mode $06 reports results per test, with the value the ECM measured and the limit it
compared against. It is the only way to see from the car — rather than by inference from
tank-pressure curves — whether the 0.020" test has ever produced a result, and what it was.

The FSM says this ECU stores exactly that. GD(STi)-140, closing the EVAP DTC section:

> **9. ECM OPERATION AT DTC SETTING** — Memorize the freeze frame data. (For test mode
> $02) — **Memorize the diagnostic value and trouble standard value. (For test mode $06)**

"Diagnostic value and trouble standard value" is measured-value plus threshold: for the
EVAP leak diagnosis, `P2 − 1.5·P1` and the Map 7 / Map 8 cell it is judged against.

Background on the EVAP diagnosis itself lives in the 22b repo:
`datalogs/dash22b-week-2026-05-22/ftp_no_leak_schematic.html` (0.04-inch),
`ftp_no_leak_schematic_002.html` (0.02-inch), and the analyzer `evap_fsm_test.py`.

## Transport — reuse what exists

Same K-line session as the readiness feature. No new transport work:

- `Obd2SerialManager.connect()` for init and format negotiation.
- `Obd2Frame.buildRequest(mode, pid, format)` — Mode $06's second byte is a TID, so it
  fits the existing two-byte service request unchanged.
- The same SSM port swap `SsmDataSource.requestReadiness` already performs. Mode $06 is a
  one-shot request, not a poll, so it is handled exactly like readiness: release the SSM
  port, run the exchange, re-establish SSM.
- Positive response mode is `0x46`. A negative response `7F 06 xx` (service or
  sub-function not supported) means "not available on this ECU" and is a normal outcome,
  not an error to surface as a crash or a red banner.

One difference from PID $01: **the response can span several messages**, each carrying a
few test records. Keep draining until the bus goes quiet — the existing
`RESPONSE_SETTLE_MS` / `RESPONSE_TOTAL_TIMEOUT_MS` loop should cover it, but do not assume
a single frame.

## The part that needs discovery, not assumption

This car is pre-CAN, and that matters more than it sounds:

- **On CAN**, Mode $06 records are fully standardised — 9 bytes each: OBDMID, TID, UAS ID,
  2-byte value, 2-byte min, 2-byte max — with units and scaling from the published UAS
  table.
- **On pre-CAN (this car)**, the TID and Component ID assignments and the value scaling are
  **manufacturer-defined**. There is no public Subaru map. Do not code against an assumed
  byte layout; a wrong guess costs more than a discovery pass.

So phase 1 is a spike, not a feature.

### Phase 1 — discovery

1. Request **`06 00`**. As with PID $00, TID $00 returns a bitmask of supported TIDs
   $01–$20, with $20 / $40 continuing the ranges. This alone answers "does this ECU
   implement Mode $06 at all".
2. For each supported TID, request it and **log the raw response bytes verbatim** — no
   parsing. Timber at info level, plus a file in the app's external files dir:
   `mode06_<yyyy-MM-dd_HH-mm-ss>.json` holding `{tid, requestHex, responseHex}` per entry.
3. Ship behind a debug affordance — long-press the readiness button, or a "Dump Mode 06"
   item on the Messages tab. No value UI yet.

### Phase 2 — map TIDs to tests empirically

The mapping falls out of correlation, and this project has an unusual advantage: **the
exact times the EVAP diagnoses ran are known**, from the solenoid channels in the monitor
CSVs (`Ventilation Solenoid Valve` high = the diagnosis window).

- Take a Mode $06 dump. Drive until the FTP log shows a *completed* 0.04-inch diagnosis —
  a vent-high window with a full Mode C/D plateau, not one that aborts. Take a second dump.
- Diff the two. Whichever TID's value changed is the EVAP leak test.
- Cross-check against `evap_fsm_test.py` for that same event: it prints `P2 − 1.5·P1` and
  the interpolated Map 7 threshold. If the ECM's stored value/limit pair lands near those
  two numbers, the TID is identified *and* the scaling is solved in one step. For
  reference, the two completed runs on 2026-08-30 were +0.164 kPa against a 0.74 threshold
  (45 L @ 27 °C) and +0.241 against 0.62 (33 L @ 35 °C).
- Repeat for the 0.02-inch test. **A TID that never updates is the answer to the original
  question.**

Snapshots therefore need timestamps and must persist, so a later pass can diff against an
earlier one offline.

### Phase 3 — the feature

```kotlin
data class ObdTestResult(
    val tid: Int,
    val componentId: Int,
    val value: Int,          // raw
    val minLimit: Int?,
    val maxLimit: Int?,
    val scaled: Double?,     // null until scaling is known for this TID
    val unit: DisplayUnit,
    val label: String?,      // from the discovered TID map; null = unknown
    val passed: Boolean?     // null when limits are absent
)
```

Render like the readiness report: one labelled row per test, value against limits, unknown
TIDs shown by number with their raw hex. Keep the raw-hex disclosure permanently — on an
undocumented pre-CAN map it is what makes a surprising reading debuggable.

Put the discovered TID map in a small table keyed by TID, in the spirit of
`SsmHardcodedParameters`. This is car-specific knowledge, not protocol knowledge, and it
will accumulate a row at a time.

## Gotchas

- **Results reset when DTCs are cleared.** Record clearing events, or a stale dump reads as
  "this test never ran".
- **Results update at the end of a monitor run**, so a dump taken mid-drive can lag the FTP
  log by a minute or more.
- **Absence of a record is a real answer**, not a bug — it is the finding being hunted.
- Mode $06 may not be supported at all. If `06 00` returns a negative response, say so
  plainly and stop. The FSM quote above says it should work, but the FSM also says things
  about this ECU that its capability bits contradict.
- A dump takes the K-line away from SSM for its duration, same as readiness — do not let
  logging stall silently while it runs.

## Definition of done

A dump on the Messages tab listing every supported TID with its raw bytes, persisting a
timestamped JSON snapshot, and — for TIDs identified in phase 2 — showing a labelled
value-against-limit row. Success is answering one question: **has the 0.020-inch leak test
ever produced a result on this car, and what was it?**
