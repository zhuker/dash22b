package com.example.dash22b.obd

import com.example.dash22b.data.DisplayUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast poll (SSM continuous read) against a fake ECU, no hardware.
 * Ported from the Python tests in ~/git/r22b/tests/test_offline.py.
 */
class SsmFastPollTest {

    /**
     * A K-line with an ECU on it. Echoes every request the way the half-duplex cable
     * does, answers it, and in continuous mode keeps producing frames for as long as
     * anyone reads. Each frame carries a fresh counter so tests can tell frames apart.
     */
    private class FakeEcu : SsmLink {
        val buffer = ArrayDeque<Byte>()
        val writes = mutableListOf<ByteArray>()
        var breaks = 0
        var streaming = false
        var numAddresses = 0
        var counter = 0
        var corruptNext = false
        var silent = false            // ECU stops answering (ignition off, cable pulled)
        var maxBytesPerRead = 256     // how much one USB transfer may deliver
        // Off: a read returns what has arrived, and the ECU sends the next frame only
        // once the last is consumed, so reads line up with frames. On: the ECU is ahead
        // and a read fills the whole transfer, splitting frames across reads.
        var readAhead = false
        val tailAfterBreak = 7        // the in-flight frame's tail, still arriving after a break

        override fun write(bytes: ByteArray, timeoutMs: Int) {
            writes.add(bytes)
            bytes.forEach { buffer.addLast(it) }  // echo
            when (bytes[4]) {
                SsmPacket.CMD_READ_ADDRESS -> {
                    numAddresses = (bytes.size - 7) / 3
                    streaming = bytes[5] == SsmPacket.READ_ADDRESS_CONTINUOUS
                    if (!silent) addFrame()
                }
                SsmPacket.CMD_WRITE_ADDRESS -> {
                    streaming = false
                    addPacket(listOf(0x80, 0xF0, 0x10, 0x02, 0xF8, bytes[8].toInt() and 0xFF))
                }
            }
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val want = minOf(buffer.size, maxBytesPerRead)
            if (streaming && !silent && this.buffer.isEmpty()) addFrame()
            while (readAhead && streaming && !silent && this.buffer.size < want) addFrame()
            val n = minOf(want, this.buffer.size)
            repeat(n) { buffer[it] = this.buffer.removeFirst() }
            return n
        }

        override fun setBreak(on: Boolean) {
            if (!on) return
            breaks++
            if (streaming) {
                streaming = false
                buffer.clear()
                repeat(tailAfterBreak) { buffer.addLast(0x55) }
            }
        }

        override fun close() {}

        /** Time passes with nobody reading: the ECU keeps sending regardless. */
        fun elapse(frames: Int) = repeat(frames) { addFrame() }

        private fun addFrame() {
            counter = (counter + 1) and 0xFF
            val values = List(numAddresses) { (counter + it) and 0xFF }
            addPacket(listOf(0x80, 0xF0, 0x10, values.size + 1, 0xE8) + values)
        }

        private fun addPacket(body: List<Int>) {
            var checksum = body.sum() and 0xFF
            if (corruptNext) {
                checksum = (checksum + 1) and 0xFF
                corruptNext = false
            }
            (body + checksum).forEach { buffer.addLast(it.toByte()) }
        }
    }

    private fun param(id: String, address: Int, length: Int = 1) = SsmParameter(
        id = id, name = id, address = address, length = length,
        expression = "x", unit = DisplayUnit.UNKNOWN
    )

    private val gauges = listOf(param("A", 0x000008), param("B", 0x00000E), param("C", 0x00000F))
    private val other = listOf(param("D", 0x000046), param("E", 0x000024))

    private fun values(packet: SsmPacket?): List<Int> {
        assertNotNull("expected a sample", packet)
        assertEquals(SsmPacket.RSP_READ_ADDRESS, packet!!.data[0])
        return packet.data.drop(1).map { it.toInt() and 0xFF }
    }

    @Test
    fun `the fast request sets the continuous padding byte`() {
        val ecu = FakeEcu()
        SsmSerialManager(ecu).readParametersFast(gauges)

        assertEquals(1, ecu.writes.size)
        assertEquals(SsmPacket.CMD_READ_ADDRESS, ecu.writes[0][4])
        assertEquals(SsmPacket.READ_ADDRESS_CONTINUOUS, ecu.writes[0][5])
    }

    @Test
    fun `the slow request keeps the single-read padding byte`() {
        val ecu = FakeEcu()
        SsmSerialManager(ecu).readParameters(gauges)

        assertEquals(SsmPacket.READ_ADDRESS_ONCE, ecu.writes[0][5])
    }

    @Test
    fun `ten fast samples cost exactly one write`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(10) { assertNotNull(ssm.readParametersFast(gauges)) }

        assertEquals(1, ecu.writes.size)
        assertEquals(0, ecu.breaks)
    }

    @Test
    fun `consecutive samples are consecutive frames, so the stream is being consumed`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        val firsts = List(5) { values(ssm.readParametersFast(gauges))[0] }

        assertEquals(listOf(1, 2, 3, 4, 5), firsts)
    }

    @Test
    fun `a multi-byte parameter asks for consecutive addresses and gets one value each`() {
        val ecu = FakeEcu()
        val params = listOf(param("One", 0x000010), param("Four", 0x000020, length = 4))
        val sample = values(SsmSerialManager(ecu).readParametersFast(params))

        val request = ecu.writes[0]
        val addresses = (0 until (request.size - 7) / 3).map { i ->
            ((request[6 + i * 3].toInt() and 0xFF) shl 16) or
                ((request[7 + i * 3].toInt() and 0xFF) shl 8) or
                (request[8 + i * 3].toInt() and 0xFF)
        }
        assertEquals(listOf(0x10, 0x20, 0x21, 0x22, 0x23), addresses)
        assertEquals(5, sample.size)
    }

    @Test
    fun `a read that delivers more than one frame keeps the rest for the next sample`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        // One and a half frames per USB transfer: every other frame starts mid-buffer.
        ecu.readAhead = true
        ecu.maxBytesPerRead = (gauges.size + 6) * 3 / 2

        val firsts = List(8) { values(ssm.readParametersFast(gauges))[0] }

        assertEquals((1..8).toList(), firsts)
        assertEquals("no frame was misaligned", 0, ecu.breaks)
    }

    @Test
    fun `the first sample keeps bytes of the second frame that arrived with the echo`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        // Echo, first frame and the start of the next ones, all in one transfer.
        ecu.readAhead = true
        ssm.readParametersFast(gauges)

        assertEquals(listOf(2, 3, 4), values(ssm.readParametersFast(gauges)))
        assertEquals(0, ecu.breaks)
    }

    @Test
    fun `a reader that falls behind gets the oldest frame, which is why the loop must not sleep`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParametersFast(gauges)
        values(ssm.readParametersFast(gauges))

        ecu.elapse(5)  // a sleep in the poll loop: frames 3-7 arrive unread

        // Frame 3, not the newest (7): every later sample is five frames stale.
        assertEquals(3, values(ssm.readParametersFast(gauges))[0])
    }

    @Test
    fun `a corrupt frame replays the last good sample, breaks the line and re-requests`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParametersFast(gauges)
        val good = ssm.readParametersFast(gauges)

        ecu.corruptNext = true
        val replayed = ssm.readParametersFast(gauges)

        assertArrayEquals(good!!.data, replayed!!.data)
        assertEquals(1, ecu.breaks)

        val fresh = ssm.readParametersFast(gauges)
        assertEquals("re-armed with a new request", 2, ecu.writes.size)
        assertNotEquals(values(good), values(fresh))
    }

    @Test
    fun `a corrupt first frame has nothing to replay and fails`() {
        val ecu = FakeEcu()
        ecu.corruptNext = true

        assertNull(SsmSerialManager(ecu).readParametersFast(gauges))
        assertEquals(1, ecu.breaks)
    }

    @Test
    fun `an ECU that goes silent mid-stream replays once, then fails`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParametersFast(gauges)
        val good = ssm.readParametersFast(gauges)

        ecu.silent = true
        assertArrayEquals(good!!.data, ssm.readParametersFast(gauges)!!.data)
        assertNull(ssm.readParametersFast(gauges))
    }

    @Test
    fun `changing the parameter list breaks the stream and re-requests`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(3) { ssm.readParametersFast(gauges) }

        val sample = values(ssm.readParametersFast(other))

        assertEquals(1, ecu.breaks)
        assertEquals(2, ecu.writes.size)
        assertEquals(other.size, sample.size)
        assertEquals(7 + other.size * 3, ecu.writes[1].size)
    }

    @Test
    fun `a stale frame from another list is not replayed`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(3) { ssm.readParametersFast(gauges) }

        ecu.corruptNext = true
        // The new list's first frame is bad; the last good one belongs to the old list.
        assertNull(ssm.readParametersFast(other))
    }

    @Test
    fun `a slow read breaks a running stream first`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(3) { ssm.readParametersFast(gauges) }

        val slow = ssm.readParameters(other)

        assertEquals(1, ecu.breaks)
        assertEquals(other.size, values(slow).size)
        assertEquals(SsmPacket.READ_ADDRESS_ONCE, ecu.writes.last()[5])
    }

    @Test
    fun `fast poll resumes with a fresh request after a slow read`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParametersFast(gauges)
        ssm.readParameters(other)  // e.g. a DTC chunk

        assertEquals(gauges.size, values(ssm.readParametersFast(gauges)).size)
        assertEquals(SsmPacket.READ_ADDRESS_CONTINUOUS, ecu.writes.last()[5])
        assertEquals(3, ecu.writes.size)
    }

    @Test
    fun `an ECU write breaks a running stream first`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(3) { ssm.readParametersFast(gauges) }

        assertTrue(ssm.writeAddress(0x000060, 0x40))
        assertEquals(1, ecu.breaks)
    }

    @Test
    fun `disconnect breaks the stream`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParametersFast(gauges)

        ssm.disconnect()

        assertEquals(1, ecu.breaks)
        assertTrue(!ssm.isConnected())
    }

    @Test
    fun `stopping with no stream running sends no break`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        ssm.readParameters(gauges)

        ssm.stopStreaming()
        ssm.disconnect()

        assertEquals(0, ecu.breaks)
    }

    @Test
    fun `stats count samples, restarts, replays and failures, and reset when taken`() {
        val ecu = FakeEcu()
        val ssm = SsmSerialManager(ecu)
        repeat(4) { ssm.readParametersFast(gauges) }
        ecu.corruptNext = true
        ssm.readParametersFast(gauges)          // bad frame, replayed
        ecu.corruptNext = true
        ssm.readParametersFast(gauges)          // bad first frame of the new stream: nothing to replay

        assertEquals(
            SsmSerialManager.FastPollStats(
                samples = 4, streamStarts = 2, badFrames = 2, replays = 1, failures = 1
            ),
            ssm.takeFastPollStats()
        )
        assertEquals(SsmSerialManager.FastPollStats(), ssm.takeFastPollStats())
    }

    @Test
    fun `frame validation rejects each kind of damage`() {
        val good = byteArrayOf(0x80.toByte(), 0xF0.toByte(), 0x10, 0x02, 0xE8.toByte(), 0x05, 0x6F)
        assertNull(SsmSerialManager.frameProblem(good, 1))

        fun damaged(index: Int, value: Int) = good.copyOf().also { it[index] = value.toByte() }
        assertNotNull(SsmSerialManager.frameProblem(good, 2))                 // length
        assertNotNull(SsmSerialManager.frameProblem(damaged(0, 0x81), 1))     // header
        assertNotNull(SsmSerialManager.frameProblem(damaged(1, 0x10), 1))     // tester id
        assertNotNull(SsmSerialManager.frameProblem(damaged(2, 0x20), 1))     // module id
        assertNotNull(SsmSerialManager.frameProblem(damaged(4, 0xF8), 1))     // response type
        assertNotNull(SsmSerialManager.frameProblem(damaged(6, 0x70), 1))     // checksum
    }
}
