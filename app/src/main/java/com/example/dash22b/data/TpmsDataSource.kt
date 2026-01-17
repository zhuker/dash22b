package com.example.dash22b.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TpmsDataSource(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    // Store latest state for each tire
    private val tpmsState = mutableMapOf<String, TpmsState>(
        "FL" to TpmsState(),
        "FR" to TpmsState(),
        "RL" to TpmsState(),
        "RR" to TpmsState()
    )

    @SuppressLint("MissingPermission") // Permissions handled by caller/Manifest
    fun getTpmsData(): Flow<Map<String, TpmsState>> = callbackFlow {
        if (adapter == null || !adapter.isEnabled) {
            Log.e("TpmsDataSource", "Bluetooth not supported or disabled")
            // Emit empty stats or handle error? For now just return.
            trySend(tpmsState.toMap())
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("TpmsDataSource", "BLE Scanner not available")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { scanResult ->
                    val deviceName = scanResult.device.name ?: "Unknown"
//                    Log.d("TpmsDataSource", "Found device: $deviceName (${scanResult.device.address})")

                    if (deviceName.startsWith("TPMS")) {
                        Log.d("TpmsDataSource", "Found TPMS device: $deviceName")
                        
                        // manufacturerSpecificData is a SparseArray. We need to find the right ID.
                        // In Python code: advertisement_data.manufacturer_data[256] -> 256 is 0x0100
                        // Let's iterate or look for 0x0100
                        
                        // SparseArray key is int. 256 = 0x0100.
                        val rawBytes = scanResult.scanRecord?.getManufacturerSpecificData(256)
                        
                        if (rawBytes != null && rawBytes.size >= 16) {
                            try {
                                val (p, t, b, l) = decodeTpms(rawBytes)
                                Log.d("TpmsDataSource", "Parsed $deviceName: $p bar, $t C")
                                
                                val pos = when {
                                    deviceName.contains("TPMS1") -> "FL"
                                    deviceName.contains("TPMS2") -> "FR"
                                    deviceName.contains("TPMS3") -> "RL"
                                    deviceName.contains("TPMS4") -> "RR"
                                    else -> "FL"
                                }

                                val newState = TpmsState(
                                    pressure = ValueWithUnit(p, "bar"),
                                    temp = ValueWithUnit(t, "C"),
                                    batteryLow = b,
                                    leaking = l
                                )
                                
                                synchronized(tpmsState) {
                                    tpmsState[pos] = newState
                                    trySend(tpmsState.toMap())
                                }
                                
                            } catch (e: Exception) {
                                Log.e("TpmsDataSource", "Error parsing TPMS data", e)
                            }
                        } else {
                            Log.w("TpmsDataSource", "TPMS device found but no manufacturer data or size mismatch: ${rawBytes?.size}")
                            // Log all manufacturer data keys to debug
                            val sparseArray = scanResult.scanRecord?.manufacturerSpecificData
                            if (sparseArray != null) {
                                for (i in 0 until sparseArray.size()) {
                                    Log.d("TpmsDataSource", "Manuf ID: ${sparseArray.keyAt(i)}")
                                }
                            }
                        }
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("TpmsDataSource", "onScanFailed Scan failed: $errorCode")
            }
        }
        try {
            Log.d("TpmsDataSource", "Starting BLE scan")
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // ScanFilter can be empty to scan all, but passing null (as we did) is also valid.
            // Let's rely on null for "all", but use settings.
            scanner.startScan(null, settings, callback)
            Log.d("TpmsDataSource", "Scan started successfully")
        } catch (e: Exception) {
            Log.e("TpmsDataSource", "Error starting scan", e)
        }

        awaitClose {
            try {
                Log.d("TpmsDataSource", "Stopping BLE scan")
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun decodeTpms(rawBytes: ByteArray): TpmsDataRaw {
        // Python logic:
        // Pressure is Bytes 6-9 (Little Endian uint32) -> / 100000 -> bar
        // Temp is Bytes 10-13 (Little Endian uint32) -> / 100 -> C
        // Battery is at index 14
        // Leaking is at index 15

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip 6 bytes
        val pressureRaw = buffer.getInt(6)
        val pressureBar = pressureRaw / 100000f

        val tempRaw = buffer.getInt(10)
        val tempC = tempRaw / 100f

        val batteryRaw = rawBytes[14].toInt() and 0xFF
        val batteryLow = batteryRaw < 20

        val leakingRaw = rawBytes[15].toInt()
        val leaking = leakingRaw != 0 // Assuming non-zero is leaking base on python 'leaking = raw_bytes[15]'

        return TpmsDataRaw(pressureBar, tempC, batteryLow, leaking)
    }

    private data class TpmsDataRaw(
        val pressure: Float,
        val temp: Float,
        val batteryLow: Boolean,
        val leaking: Boolean
    )
}
