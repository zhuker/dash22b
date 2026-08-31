package com.example.dash22b.data

import com.example.dash22b.obd.ObdProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticDumpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(
        probe: String,
        data: String? = "00 07 65 00",
        negative: Boolean = false,
        error: String? = null
    ) = DiagnosticDump.Entry(
        probe = probe,
        mode = 0x01,
        pid = 0x01,
        label = "probe $probe",
        why = "because",
        requestHex = "68 6A F1 01 01 C5",
        responseHex = "48 6B 11 41 01 $data",
        dataHex = data,
        negativeResponse = negative,
        error = error
    )

    private fun dump(vararg entries: DiagnosticDump.Entry) = DiagnosticDump.Dump(
        capturedAt = "2026-08-30T12:00:00Z",
        appVersion = "0.4.0-test",
        gitBranch = "main",
        protocol = "KWP2000",
        entries = entries.toList()
    )

    @Test
    fun `the probe list covers the FSM's documented Mode 06 TIDs`() {
        val probes = ObdProbe.defaultDump()
        val mode06 = probes.filter { it.mode == 0x06 }.map { it.pid }

        ObdProbe.FSM_MODE06_TIDS.map { it.first }.forEach {
            assertTrue("TID %02X missing".format(it), mode06.contains(it))
        }
        // Plus the speculative support bitmask.
        assertTrue(mode06.contains(0x00))
    }

    @Test
    fun `the dump asks for Cal ID and CVN, which the smog check compares`() {
        val mode09 = ObdProbe.defaultDump().filter { it.mode == 0x09 }.map { it.pid }

        assertTrue(mode09.contains(0x04)) // Cal ID
        assertTrue(mode09.contains(0x06)) // CVN
    }

    @Test
    fun `session sanity check is probed first, so a dead session is obvious at the top`() {
        val first = ObdProbe.defaultDump().first()

        assertEquals(0x01, first.mode)
        assertEquals(0x00, first.pid)
    }

    @Test
    fun `every probe carries a reason, so a future reader knows why it was asked`() {
        ObdProbe.defaultDump().forEach {
            assertTrue(it.label, it.why.isNotBlank())
            assertTrue(it.label, it.label.isNotBlank())
        }
    }

    @Test
    fun `writes a timestamped json file that round-trips the raw hex`() {
        val dir = temp.newFolder("logs")
        val file = DiagnosticDump.write(dir, dump(entry("0101")), now = 0L)

        assertTrue(file.name.startsWith(DiagnosticDump.FILE_PREFIX))
        assertTrue(file.name.endsWith(".json"))

        val text = file.readText()
        assertTrue(text, text.contains("\"dataHex\""))
        assertTrue(text, text.contains("00 07 65 00"))
        assertTrue(text, text.contains("\"protocol\": \"KWP2000\""))
        // The request is kept too: reproducing a capture needs to know what was asked.
        assertTrue(text, text.contains("68 6A F1 01 01 C5"))
    }

    @Test
    fun `dumps never collide, so an earlier capture survives for diffing`() {
        val dir = temp.newFolder("logs")
        val first = DiagnosticDump.write(dir, dump(entry("0101")), now = 0L)
        val second = DiagnosticDump.write(dir, dump(entry("0101")), now = 60_000L)

        assertFalse(first.name == second.name)
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun `LogArchiver lists dumps so share logs carries them off the car`() {
        val dir = temp.newFolder("logs")
        DiagnosticDump.write(dir, dump(entry("0101")), now = 0L)

        val logs = LogArchiver(dir).list()

        assertEquals(1, logs.size)
        assertEquals(LogArchiver.Kind.OBD_DUMP, logs.first().kind)
    }

    @Test
    fun `summary distinguishes data, negative responses and send failures`() {
        val text = DiagnosticDump.summarise(
            dump(
                entry("0101"),
                entry("0600", data = null, negative = true),
                entry("0603", data = null, error = "request could not be sent")
            )
        )

        assertTrue(text, text.contains("00 07 65 00"))
        assertTrue(text, text.contains("negative response"))
        assertTrue(text, text.contains("request could not be sent"))
        assertTrue(text, text.contains("1 of 3 probes returned data"))
        assertTrue(text, text.contains("KWP2000"))
    }

    @Test
    fun `summary points at share logs, since the file is the deliverable`() {
        assertTrue(DiagnosticDump.summarise(dump(entry("0101"))).contains("share logs"))
    }
}
