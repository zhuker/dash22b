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
    fun `the dump probes the TIDs the sweep proved live, not the FSM's numbering`() {
        val mode06 = ObdProbe.defaultDump().filter { it.mode == 0x06 }.map { it.pid }

        // Found by sweeping all 256 TIDs on the car: the live tests sit $80 above the
        // FSM's numbers, plus one at $41.
        listOf(0x41, 0x81, 0x83, 0x84, 0x85).forEach {
            assertTrue("TID %02X missing".format(it), mode06.contains(it))
        }
        // The FSM's own TIDs are refused by this ECU; probing them again is wasted time.
        listOf(0x01, 0x03, 0x05, 0x07, 0x0C, 0x0F).forEach {
            assertFalse("TID %02X should no longer be probed".format(it), mode06.contains(it))
        }
    }

    @Test
    fun `the dump re-reads the whole support bitmask chain`() {
        val mode06 = ObdProbe.defaultDump().filter { it.mode == 0x06 }.map { it.pid }

        // Cheap insurance: if the TID map ever changes, the chain shows it.
        listOf(0x00, 0x20, 0x40, 0x60, 0x80).forEach {
            assertTrue("base %02X missing".format(it), mode06.contains(it))
        }
    }

    @Test
    fun `dump summary decodes Mode 06 records rather than printing hex`() {
        val text = DiagnosticDump.summarise(
            dump(
                DiagnosticDump.Entry(
                    probe = "0683", mode = 0x06, pid = 0x83,
                    label = "Mode 06 TID 83", why = "EVAP",
                    responseHex = "...",
                    dataHex = "02 62 E8 6C 08 03 00 00 FF FF"
                )
            )
        )

        assertTrue(text, text.contains("EVAP small leak (0.040 in)"))
        assertTrue(text, text.contains("91.6% of limit"))
        assertTrue(text, text.contains("never run"))
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
    fun `sweep covers every Mode 06 TID and Mode 05`() {
        val sweep = ObdProbe.sweep()

        assertEquals(256, sweep.count { it.mode == 0x06 })
        assertEquals(32, sweep.count { it.mode == 0x05 })
        assertEquals(256 + 32, sweep.size)
    }

    @Test
    fun `sweep summary groups refusals by NRC instead of listing them all`() {
        val entries = (0x01..0x40).map {
            DiagnosticDump.Entry(
                probe = "06%02X".format(it), mode = 0x06, pid = it,
                label = "TID", why = "sweep",
                responseHex = "83 F1 10 7F 06 12 1B",
                negativeResponse = true, nrc = 0x12
            )
        } + DiagnosticDump.Entry(
            probe = "0600", mode = 0x06, pid = 0x00, label = "TID 00", why = "sweep",
            responseHex = "87 F1 10 46 00 FF 00 00 00 01 CE", dataHex = "FF 00 00 00 01"
        )

        val text = DiagnosticDump.summariseSweep(dump(*entries.toTypedArray()))

        assertTrue(text, text.contains("1 answered"))
        assertTrue(text, text.contains("FF 00 00 00 01"))
        assertTrue(text, text.contains("64 refused"))
        assertTrue(text, text.contains("NRC 0x12"))
        // The refusals must not be enumerated one per line.
        assertTrue(text, text.lines().size < 20)
    }

    @Test
    fun `sweep summary reports when nothing answered`() {
        val text = DiagnosticDump.summariseSweep(
            dump(
                DiagnosticDump.Entry(
                    probe = "0601", mode = 0x06, pid = 1, label = "x", why = "y",
                    negativeResponse = true, nrc = 0x12
                )
            )
        )

        assertTrue(text, text.contains("Nothing answered"))
    }

    @Test
    fun `sweeps are archived under their own prefix and still shareable`() {
        val dir = temp.newFolder("logs")
        val file = DiagnosticDump.write(dir, dump(entry("0101")), now = 0L, prefix = DiagnosticDump.SWEEP_PREFIX)

        assertTrue(file.name.startsWith(DiagnosticDump.SWEEP_PREFIX))
        assertEquals(LogArchiver.Kind.OBD_DUMP, LogArchiver(dir).list().single().kind)
    }

    @Test
    fun `summary points at share logs, since the file is the deliverable`() {
        assertTrue(DiagnosticDump.summarise(dump(entry("0101"))).contains("share logs"))
    }
}
