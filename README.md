# Dash22b

Dash22b is an Android application designed to serve as a digital car dashboard, providing real-time engine data visualization and Tire Pressure Monitoring System (TPMS) integration.

## Features

*   **Real-time Dashboard**: Displays critical engine parameters such as RPM, Boost, Vehicle Speed, Coolant Temperature, and more using dynamic circular gauges.
*   **TPMS Integration**: Connects to BLE (Bluetooth Low Energy) TPMS sensors to monitor tire pressure and temperature in real-time.
*   **Graphing**: Visualizes historical data for key metrics like Boost and RPM.
*   **Background Service**: A foreground service ensures TPMS data collection continues even when the app is in the background.

## Technology Stack

*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose
*   **Connectivity**: Android Bluetooth Low Energy (BLE) for TPMS communication.

## Getting Started

1.  **Permissions**: Ensure Bluetooth and Location permissions are granted for BLE scanning.
2.  **TPMS Hardware**: The app is designed to work with specific BLE TPMS sensors (Manufacturer ID 0x0100).
3.  **Data Source**: The app currently supports reading engine data from log files (for development/demo) and anticipates integration with an OBDAdapter for live vehicle data.

## Debugging & Logging

The application writes app diagnostics and monitored ECU values to separate files under its external files directory.

- `app_logs.txt` contains human-readable app diagnostics and is rotated on every app launch.
- `monitor_yyyy-MM-dd_HH-mm-ss-SSS.csv` contains converted ECU values with one polling response per row.

When the monitored parameter list or one of its units changes, the app closes the current monitor CSV and starts a new one with the new columns. Units are included in each column heading, for example `Engine Speed [rpm]` and `Manifold Relative Pressure [kPa]`.

### Retrieving Logs
You can retrieve the logs directly using `adb pull`:

```bash
adb pull /sdcard/Android/data/com.example.dash22b/files/app_logs.txt
```

Or pull the entire directory to see rotated logs:

```bash
adb pull /sdcard/Android/data/com.example.dash22b/files/
```

### Log Rotation
On every app launch, the existing `app_logs.txt` is renamed to `app_logs_yyyy-MM-dd_HH-mm-ss.txt` to preserve history. You can retrieve these specific files by listing the directory contents:

```bash
adb shell run-as com.example.dash22b ls files/
```

### Monitored ECU data

List and pull the monitor CSV files with ADB:

```bash
adb shell 'ls /sdcard/Android/data/com.example.dash22b/files/monitor_*.csv'
adb pull /sdcard/Android/data/com.example.dash22b/files/
```

Each file is a regular wide CSV and can be loaded directly with pandas:

```python
import pandas as pd

data = pd.read_csv("monitor_2026-05-25_16-02-14-125.csv", parse_dates=["timestamp"])
```
