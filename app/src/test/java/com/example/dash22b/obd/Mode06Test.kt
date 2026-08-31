package com.example.dash22b.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode06Test {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---- supported-TID bitmask, against the car's actual responses (sweep 2026-08-30) ----

    @Test
    fun `TID 80 mask matches the four TIDs that answered`() {
        // 06 80 -> FF B8 00 00 00
        val tids = Mode06.decodeSupportedTids(0x80, bytes(0xFF, 0xB8, 0x00, 0x00, 0x00))

        assertEquals(listOf(0x81, 0x83, 0x84, 0x85), tids)
    }

    @Test
    fun `TID 40 mask advertises 41 and the continuation to 60`() {
        // 06 40 -> FF 80 00 00 01
        val tids = Mode06.decodeSupportedTids(0x40, bytes(0xFF, 0x80, 0x00, 0x00, 0x01))

        assertEquals(listOf(0x41, 0x60), tids)
    }

    @Test
    fun `an empty range still chains to the next one`() {
        // 06 00 -> FF 00 00 00 01: nothing in 01-1F, continue at 20.
        assertEquals(listOf(0x20), Mode06.decodeSupportedTids(0x00, bytes(0xFF, 0, 0, 0, 0x01)))
    }

    @Test
    fun `a short payload yields no TIDs rather than throwing`() {
        assertTrue(Mode06.decodeSupportedTids(0x00, bytes(0xFF, 0x00)).isEmpty())
    }

    // ---- records ----

    @Test
    fun `decodes the car's EVAP records, including the test that never ran`() {
        // TID 83, from the sweep: three CIDs = the FSM's three EVAP leak sizes.
        val payload = bytes(
            0x01, 0x6A, 0x06, 0x6A, 0x7F,
            0x02, 0x62, 0xE8, 0x6C, 0x08,
            0x03, 0x00, 0x00, 0xFF, 0xFF
        )

        val records = Mode06.decodeRecords(0x83, payload)

        assertEquals(3, records.size)

        assertEquals(0x01, records[0].cid)
        assertEquals(27142, records[0].value)
        assertEquals(27263, records[0].limit)
        assertFalse(records[0].noResult)

        assertEquals(25320, records[1].value)
        assertEquals(27656, records[1].limit)
        assertEquals(0.916, records[1].ratio!!, 0.001)

        // The 0.020 inch test: no stored result. This is the finding the sweep was for.
        assertTrue(records[2].noResult)
        assertNull(records[2].ratio)
    }

    @Test
    fun `a real zero is not mistaken for a missing result`() {
        // Value 0 against a real limit is a test that ran and scored zero.
        val record = Mode06.decodeRecords(0x83, bytes(0x02, 0x00, 0x00, 0x6C, 0x08)).single()

        assertFalse(record.noResult)
        assertEquals(0.0, record.ratio!!, 0.0)
    }

    @Test
    fun `a limit of zero yields no ratio instead of dividing by zero`() {
        assertNull(Mode06.decodeRecords(0x81, bytes(0x01, 0x00, 0x05, 0x00, 0x00)).single().ratio)
    }

    @Test
    fun `a trailing partial record is dropped, not padded`() {
        val payload = bytes(0x01, 0x00, 0x0A, 0x00, 0x0A, 0x02, 0x00)

        val records = Mode06.decodeRecords(0x85, payload)

        assertEquals(1, records.size)
        assertEquals(0x01, records.single().cid)
    }

    @Test
    fun `labels come from the FSM mapping, with a fallback for unknown pairs`() {
        assertEquals("EVAP very small leak (0.020 in)", Mode06.labelFor(0x83, 0x03))
        assertEquals("Catalyst efficiency", Mode06.labelFor(0x81, 0x01))
        assertEquals("TID 41 CID 81", Mode06.labelFor(0x41, 0x81))
    }

    @Test
    fun `formatting calls out the never-run test in words`() {
        val text = Mode06.format(
            Mode06.decodeRecords(
                0x83,
                bytes(0x02, 0x62, 0xE8, 0x6C, 0x08, 0x03, 0x00, 0x00, 0xFF, 0xFF)
            )
        )

        assertTrue(text, text.contains("EVAP small leak (0.040 in)"))
        assertTrue(text, text.contains("91.6% of limit"))
        assertTrue(text, text.contains("EVAP very small leak (0.020 in)"))
        assertTrue(text, text.contains("never run"))
    }
}
