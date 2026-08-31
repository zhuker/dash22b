package com.example.dash22b.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2FrameTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `ISO9141 request uses the fixed header and a summed checksum`() {
        val frame = Obd2Frame.buildRequest(0x01, 0x01, Obd2Frame.Format.ISO9141)

        // 68 6A F1 01 01, checksum = 0x68+0x6A+0xF1+1+1 = 0x1C5 -> 0xC5
        assertArrayEquals(bytes(0x68, 0x6A, 0xF1, 0x01, 0x01, 0xC5), frame)
    }

    @Test
    fun `KWP2000 request encodes payload length in the format byte`() {
        val frame = Obd2Frame.buildRequest(0x01, 0x01, Obd2Frame.Format.KWP2000)

        // C2 33 F1 01 01, checksum = 0xC2+0x33+0xF1+1+1 = 0x1E8 -> 0xE8
        assertArrayEquals(bytes(0xC2, 0x33, 0xF1, 0x01, 0x01, 0xE8), frame)
    }

    @Test
    fun `checksum is the low byte of the running sum`() {
        assertEquals(0x06, Obd2Frame.checksum(bytes(0x01, 0x02, 0x03)))
        assertEquals(0xFF, Obd2Frame.checksum(bytes(0xFF)))
        assertEquals(0x00, Obd2Frame.checksum(bytes(0x80, 0x80)))
    }

    @Test
    fun `extracts data from a clean response`() {
        // 48 6B 11 41 01 00 07 65 00 <cksum>
        val raw = bytes(0x48, 0x6B, 0x11, 0x41, 0x01, 0x00, 0x07, 0x65, 0x00, 0x2C)

        val data = Obd2Frame.extractData(raw, 0x01, 0x01, 4)

        assertArrayEquals(bytes(0x00, 0x07, 0x65, 0x00), data)
    }

    @Test
    fun `strips our own echo, which the single-wire K-line always returns`() {
        val request = Obd2Frame.buildRequest(0x01, 0x01, Obd2Frame.Format.ISO9141)
        val response = bytes(0x48, 0x6B, 0x11, 0x41, 0x01, 0x00, 0x07, 0x65, 0x00, 0x2C)
        val raw = request + response

        val data = Obd2Frame.extractData(raw, 0x01, 0x01, 4)

        assertArrayEquals(bytes(0x00, 0x07, 0x65, 0x00), data)
    }

    @Test
    fun `survives leading init noise before the response`() {
        val raw = bytes(0x55, 0xEF, 0x85, 0x00, 0x00) +
            bytes(0x48, 0x6B, 0x11, 0x41, 0x01, 0x00, 0x07, 0x65, 0x00, 0x2C)

        assertArrayEquals(bytes(0x00, 0x07, 0x65, 0x00), Obd2Frame.extractData(raw, 0x01, 0x01, 4))
    }

    @Test
    fun `returns null when the response is truncated rather than guessing`() {
        val raw = bytes(0x48, 0x6B, 0x11, 0x41, 0x01, 0x00, 0x07)

        assertNull(Obd2Frame.extractData(raw, 0x01, 0x01, 4))
    }

    @Test
    fun `returns null when no response is present`() {
        assertNull(Obd2Frame.extractData(bytes(0x68, 0x6A, 0xF1, 0x01, 0x01, 0xC5), 0x01, 0x01, 4))
        assertNull(Obd2Frame.extractData(ByteArray(0), 0x01, 0x01, 4))
    }

    @Test
    fun `does not mistake a different PID's response for ours`() {
        // A response to 01 0C (RPM), asked about 01 01.
        val raw = bytes(0x48, 0x6B, 0x11, 0x41, 0x0C, 0x1A, 0xF8, 0x00)

        assertNull(Obd2Frame.extractData(raw, 0x01, 0x01, 4))
    }

    @Test
    fun `recognises a negative response`() {
        assertTrue(Obd2Frame.isNegativeResponse(bytes(0x48, 0x6B, 0x11, 0x7F, 0x01, 0x12), 0x01))
        assertFalse(Obd2Frame.isNegativeResponse(bytes(0x48, 0x6B, 0x11, 0x41, 0x01, 0x00), 0x01))
    }

    @Test
    fun `positive response mode is request plus 0x40`() {
        assertEquals(0x41, Obd2Frame.positiveResponseMode(0x01))
        assertEquals(0x49, Obd2Frame.positiveResponseMode(0x09))
    }
}
