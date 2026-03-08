package com.example.dash22b.obd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages USB serial connection for SSM communication with the ECU.
 * Based on PiMonitor Python PMConnection implementation.
 */
class SsmSerialManager(private val context: Context) {
    
    companion object {
        const val TAG = "SsmSerialManager"
        private const val BAUD_RATE = 4800
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        private const val READ_TIMEOUT_MS = 2000
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERMISSION = "com.example.dash22b.USB_PERMISSION"
    }
    
    private var port: UsbSerialPort? = null
    
    /**
     * Attempts to find and connect to a USB serial device.
     * @return true if connection successful
     */
    fun connect(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            Timber.tag(TAG).w("No USB serial devices found")
            return false
        }
        
        val driver = availableDrivers[0]
        val device = driver.device
        Timber.tag(TAG).i("Found USB device: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")
        
        // Check if we have permission
        if (!usbManager.hasPermission(device)) {
            Timber.tag(TAG).i("Requesting USB permission...")
            requestPermission(usbManager, device)
            return false // Permission request is async, will need to retry
        }
        
        return openDevice(usbManager, driver)
    }
    
    private fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)  // Make intent explicit for Android 14+
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else 
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Timber.tag(TAG).i("USB permission ${if (granted) "granted" else "denied"}")
                    context.unregisterReceiver(this)
                    
                    if (granted) {
                        // Try connecting again
                        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                        if (drivers.isNotEmpty()) {
                            if (openDevice(usbManager, drivers[0])) {
                                // Send init now that we're connected
                                val response = sendInit(1)
                                if (response != null) {
                                    Timber.tag(TAG).i("ECU responded! ROM ID: ${SsmEcuInit(response).getRomId()}")
                                }
                                disconnect()
                            }
                        }
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        usbManager.requestPermission(device, permissionIntent)
    }
    
    private fun openDevice(usbManager: UsbManager, driver: UsbSerialDriver): Boolean {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Timber.tag(TAG).e("Failed to open USB device connection")
            return false
        }
        
        port = driver.ports[0]
        try {
            port?.open(connection)
            port?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            Timber.tag(TAG).i("Connected to USB serial at $BAUD_RATE baud")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open USB serial port")
            port = null
            return false
        }
    }
    
    /**
     * Sends an ECU init packet and waits for response.
     * @return Response packet, or null if failed
     */
    fun sendInit(target: Int = 1): SsmPacket? {
        val port = this.port ?: run {
            Timber.tag(TAG).e("Not connected")
            return null
        }
        
        val initPacket = SsmPacket.createInitPacket(target)
        Timber.tag(TAG).i("Sending init packet: ${initPacket.toHexString()}")
        
        try {
            // Send packet
            val txBytes = initPacket.toBytes()

            try {
                port.write(txBytes, WRITE_TIMEOUT_MS)
            } catch (e: java.io.IOException) {
                // Write failed at offset 0 usually means cable disconnected
                Timber.tag(TAG).w("Write failed: ${e.message}")
                handleWriteError()
                return null
            }

            // Small delay after write (like Python version)
            Thread.sleep(50)
            
            // Read response
            val rxBuffer = ByteArray(256)
            val responseData = mutableListOf<Byte>()
            
            // Read header (3 bytes)
            var bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
            if (bytesRead < 3) {
                Timber.tag(TAG).w("No response or incomplete header (got $bytesRead bytes)")
                return null
            }
            
            // Skip our own echo (the cable echoes back what we send)
            var offset = 0
            while (offset < bytesRead) {
                // Look for response header (destination and source are swapped)
                if (rxBuffer[offset] == SsmPacket.HEADER && 
                    offset + 3 < bytesRead &&
                    (rxBuffer[offset + 1].toInt() and 0xFF) == SsmPacket.SOURCE_DIAG) {
                    // This is the response (destination is now 0xF0, our address)
                    break
                }
                offset++
            }
            
            if (offset >= bytesRead) {
                Timber.tag(TAG).w("Could not find response in received data")
                logHex("Received", rxBuffer.copyOf(bytesRead))
                return null
            }
            
            // Copy from response start
            for (i in offset until bytesRead) {
                responseData.add(rxBuffer[i])
            }
            
            // If we got the header, read rest of packet
            if (responseData.size >= 4) {
                val dataLen = responseData[3].toInt() and 0xFF
                val expectedTotal = 5 + dataLen
                
                // Read more if needed
                while (responseData.size < expectedTotal) {
                    bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
                    if (bytesRead <= 0) break
                    for (i in 0 until bytesRead) {
                        responseData.add(rxBuffer[i])
                    }
                }
            }
            
            val responseBytes = responseData.toByteArray()
            logHex("Response", responseBytes)
            
            val response = SsmPacket.fromBytes(responseBytes)
            if (response != null) {
                val romId = SsmEcuInit(response).getRomId()
                Timber.tag(TAG).i("ECU init successful! ROM ID: $romId")
            } else {
                Timber.tag(TAG).w("Failed to parse response packet")
            }
            
            return response
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during init")
            return null
        }
    }
    
    /**
     * Disconnects from the USB serial device.
     */
    fun disconnect() {
        try {
            port?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error closing port")
        }
        port = null
        Timber.tag(TAG).i("Disconnected")
    }
    
    /**
     * Checks if currently connected.
     */
    fun isConnected(): Boolean = port != null

    /**
     * Handles write errors by disconnecting, which will trigger reconnection logic.
     */
    private fun handleWriteError() {
        Timber.tag(TAG).w("Write error detected, disconnecting to trigger reconnect")
        disconnect()
    }

    /**
     * Reads multiple parameters from the ECU in a single batched request.
     * More efficient than individual reads.
     *
     * @param parameters List of SSM parameters to read
     * @return Response packet containing parameter values, or null if failed
     */
    fun readParameters(parameters: List<SsmParameter>): SsmPacket? {
        val port = this.port ?: run {
            Timber.tag(TAG).e("Not connected")
            return null
        }

        // Calculate expected sizes upfront
        val totalBytesToRead = parameters.sumOf { it.length }
        val requestSize = 6 + (totalBytesToRead * 3) + 1  // header(5) + cmd(1) + pad(1) + addresses(3 each) + checksum(1)
        val expectedResponseSize = 6 + totalBytesToRead    // header(5) + rspCmd(1) + values(N) + checksum(1)
        val expectedTotalRead = requestSize + expectedResponseSize
        Timber.tag(TAG).d("Expecting to read: request echo=$requestSize + response=$expectedResponseSize = $expectedTotalRead bytes")

        // Build read request packet
        // For multi-byte parameters, request each byte separately
        val data = mutableListOf<Byte>()
        data.add(SsmPacket.CMD_READ_ADDRESS)  // Read command (0xA8)
        data.add(0x00.toByte())                // Padding (single read mode)

        // Add addresses for each byte to read
        parameters.forEach { param ->
            repeat(param.length) { i ->
                val addr = param.address + i
                data.add(((addr shr 16) and 0xFF).toByte())  // High byte
                data.add(((addr shr 8) and 0xFF).toByte())   // Mid byte
                data.add((addr and 0xFF).toByte())            // Low byte
            }
        }

        val requestPacket = SsmPacket(
            destination = SsmPacket.DESTINATION_ECU,
            source = SsmPacket.SOURCE_DIAG,
            data = data.toByteArray()
        )

        try {
            // Send packet
            val txBytes = requestPacket.toBytes()
            val requestStart = System.nanoTime()
            Timber.tag(TAG).d("Sending read request: ${requestPacket.toHexString()}")

            try {
                port.write(txBytes, WRITE_TIMEOUT_MS)
            } catch (e: java.io.IOException) {
                // Write failed at offset 0 usually means cable disconnected
                Timber.tag(TAG).w("Write failed: ${e.message}")
                handleWriteError()
                return null
            }

            // Small delay after write
            Thread.sleep(50)

            // Read exactly expectedTotalRead bytes (echo + response)
            val allBytes = ByteArray(expectedTotalRead)
            val rxBuffer = ByteArray(256)
            var totalBytesRead = 0

            while (totalBytesRead < expectedTotalRead) {
                val bytesRead = port.read(rxBuffer, READ_TIMEOUT_MS)
                if (bytesRead <= 0) {
                    Timber.tag(TAG).w("Read timeout: got $totalBytesRead of $expectedTotalRead bytes")
                    break
                }
                val bytesToCopy = minOf(bytesRead, expectedTotalRead - totalBytesRead)
                System.arraycopy(rxBuffer, 0, allBytes, totalBytesRead, bytesToCopy)
                totalBytesRead += bytesToCopy
            }

            if (totalBytesRead < expectedTotalRead) {
                Timber.tag(TAG).w("Incomplete read: got $totalBytesRead of $expectedTotalRead bytes")
                logHex("Partial data", allBytes.copyOf(totalBytesRead))
                return null
            }

            val requestDuration = System.nanoTime() - requestStart
            val requestDurationMsec = requestDuration / 1_000_000L

            logHex("Received in $requestDurationMsec msec", allBytes)

            // Response starts right after the echo
            val responseBytes = allBytes.copyOfRange(requestSize, expectedTotalRead)
            val response = SsmPacket.fromBytes(responseBytes)

            if (response == null) {
                Timber.tag(TAG).w("Failed to parse response packet")
                return null
            }

            // Verify it's a read response
            if (response.data.isNotEmpty() && response.data[0] != SsmPacket.RSP_READ_ADDRESS) {
                Timber.tag(TAG).w("Unexpected response type: ${String.format("%02X", response.data[0])}")
                return null
            }

            return response

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error reading parameters $e")
            return null
        }
    }

    private fun logHex(label: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { String.format("%02X", it) }
        Timber.tag(TAG).d("$label (${bytes.size} bytes): $hex")
    }
}
