package com.example.dash22b.obd

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * The serial operations the SSM conversation needs, and nothing else.
 *
 * Exists so [SsmSerialManager]'s protocol logic -- above all the fast-poll state
 * machine -- can run in unit tests against a fake ECU instead of a USB cable.
 * Semantics follow [UsbSerialPort]: [read] returns however many bytes one transfer
 * produced (possibly more than one frame), 0 on timeout, and a timeout of 0 blocks
 * forever.
 */
interface SsmLink {
    fun write(bytes: ByteArray, timeoutMs: Int)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun setBreak(on: Boolean)
    fun close()
}

/** The real link: an opened usb-serial-for-android port. */
class UsbSsmLink(private val port: UsbSerialPort) : SsmLink {
    override fun write(bytes: ByteArray, timeoutMs: Int) = port.write(bytes, timeoutMs)
    override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)
    // Only drivers that implement it can stop a fast-poll stream; FTDI (the car's
    // FT232R cable) does, CommonUsbSerialPort throws UnsupportedOperationException.
    override fun setBreak(on: Boolean) = port.setBreak(on)
    override fun close() = port.close()
}
