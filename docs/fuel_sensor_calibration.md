# Fuel Sensor Calibration Data

## Vehicle: 2001 Subaru Impreza 2.5RS (GC8)

## Sensor Characteristics
- Higher voltage = less fuel (empty)
- Lower voltage = more fuel (full)
- 0.3V = full

## Measured Data Points

| # | Starting Voltage | Fuel Added (gal) | Ending Voltage |
|---|-----------------|-------------------|----------------|
| 1 | 4.0–4.1V        | 13.6              | 0.3V           |
| 2 | 2.7–2.8V        | 6.2               | 0.3V           |
| 3 | 2.5–2.6V        | 5.3               | 0.3V           |
| 4 | 1.4–1.5V        | 2.8               | 0.3V           |
| 5 | 1.9V            | 3.5               | 0.3V           |
| 6 | 0.5–0.6V        | 1.6               | 0.3V           |
| 7 | 3.2V            | 8.0               | 0.3V           |
| 8 | 4.3V            | 14.6              | 0.3V           |
| 9 | 4.2V            | 14.0              | 0.3V           |
| 10| 4.05V           | 13.6              | 0.3V (confirmed 2x) |
| 11| 3.5V            | 9.1               | 0.3V           |

## Voltage-to-Gallons Lookup (midpoints used for ranges)

| Voltage (V) | Gallons to Fill |
|-------------|-----------------|
| 0.30        | 0.0             |
| 0.55        | 1.6             |
| 1.45        | 2.8             |
| 1.90        | 3.5             |
| 2.55        | 5.3             |
| 2.75        | 6.2             |
| 3.20        | 8.0             |
| 3.50        | 9.1             |
| 4.05        | 13.6            |
| 4.20        | 14.0            |
| 4.30        | 14.6            |

## Current Best-Fit Formula (Cubic)

Gallons to fill = 0.238V³ − 0.891V² + 3.004V − 0.401

RMSE: 0.34

## Notes
- 4.05V measurement confirmed twice at 13.6 gal
- 1.45V midpoint chosen over 1.35V based on better fit (lower RMSE)
- Biggest outliers: 0.55V (0.58 gal off) and 4.05V (0.66 gal off)
- S-shaped response: steep near full, flatter mid-range, steep again near empty
- Weakest region: 0.7–1.0V (no data points yet)
