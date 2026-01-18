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

The application automatically logs all debug information to a local file, which is rotated on every app launch.

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
