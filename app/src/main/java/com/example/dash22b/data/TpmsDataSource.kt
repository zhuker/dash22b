package com.example.dash22b.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TpmsUpdate(val position: String, val state: TpmsState)

class TpmsDataSource(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    @SuppressLint("MissingPermission")
    fun getTpmsUpdates(): Flow<TpmsUpdate> = callbackFlow {
        if (adapter == null || !adapter.isEnabled) {
            Log.e("TpmsDataSource", "Bluetooth not supported or disabled")
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

                    if (deviceName.startsWith("TPMS")) {
                        // SparseArray key is int. 256 = 0x0100.
                        val rawBytes = scanResult.scanRecord?.getManufacturerSpecificData(256)
                        
                        if (rawBytes != null && rawBytes.size >= 16) {
                            try {
                                val (p, t, b, l) = decodeTpms(rawBytes)

                                val pos = when {
                                    deviceName.contains("TPMS1") -> "FL"
                                    deviceName.contains("TPMS2") -> "FR"
                                    deviceName.contains("TPMS3") -> "RL"
                                    deviceName.contains("TPMS4") -> "RR"
                                    else -> "FL"
                                }
                                Log.d("TpmsDataSource", "Parsed $deviceName($pos): $p bar, $t C, RSSI: ${scanResult.rssi}")

                                val newState = TpmsState(
                                    pressure = ValueWithUnit(p, "bar"),
                                    temp = ValueWithUnit(t, "C"),
                                    batteryLow = b,
                                    leaking = l,
                                    timestamp = System.currentTimeMillis(),
                                    isStale = false,
                                    rssi = scanResult.rssi
                                )
                                
                                trySend(TpmsUpdate(pos, newState))
                                
                            } catch (e: Exception) {
                                // Ignore parsing errors
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
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                .build()
            
            // Filter for Manufacturer ID 0x0100 (256)
            // Passing empty data array matches any data content for this ID
            val filter = android.bluetooth.le.ScanFilter.Builder()
                .setManufacturerData(256, byteArrayOf())
                .build()
                
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            Log.e("TpmsDataSource", "Error starting scan", e)
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun decodeTpms(rawBytes: ByteArray): TpmsDataRaw {
        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip 6 bytes
        val pressureRaw = buffer.getInt(6)
        val pressureBar = pressureRaw / 100000f

        val tempRaw = buffer.getInt(10)
        val tempC = tempRaw / 100f

        val batteryRaw = rawBytes[14].toInt() and 0xFF
        val batteryLow = batteryRaw < 20

        val leakingRaw = rawBytes[15].toInt()
        val leaking = leakingRaw != 0

        return TpmsDataRaw(pressureBar, tempC, batteryLow, leaking)
    }

    private data class TpmsDataRaw(
        val pressure: Float,
        val temp: Float,
        val batteryLow: Boolean,
        val leaking: Boolean
    )
}
