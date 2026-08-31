package com.example.dash22b.obd

/**
 * Decoder for this ECU's Mode $06 on-board test results.
 *
 * The layout here was **measured, not looked up**. Pre-CAN Mode $06 TID assignments and
 * value scaling are manufacturer-defined and Subaru does not publish them, so a sweep of
 * all 256 TIDs on 2026-08-30 established both the TID map and the record shape.
 *
 * What the sweep found:
 *
 *  - The supported-TID response is a constant `FF` followed by a **4-byte bitmask**, and
 *    the chain runs $00 -> $20 -> $40 -> $60 -> $80. Every TID the mask advertises answers
 *    and every one it does not refuses, which is what makes the reading trustworthy.
 *  - Live TIDs are **$41, $81, $83, $84, $85** -- not the $01/$03/$05 the FSM tabulates.
 *    They sit at the FSM's numbering plus $80 (and one at $40), so FSM TID $03, the EVAP
 *    leak family, is TID **$83** here. Its three CIDs line up with the FSM's three EVAP
 *    rows: large, small and very small leak.
 *  - Each record is five bytes: CID, then a 16-bit test value, then a 16-bit limit.
 *    Records arrive one per KWP frame, several frames to a response.
 *
 * What is still unknown, and deliberately not guessed at: the **scaling** of value and
 * limit (raw counts, no published conversion), and whether the limit is a maximum or a
 * minimum. Both are recoverable by correlating a dump against a monitor run of known
 * outcome -- see docs/mode06_test_results_plan.md phase 2 -- and until then [Record]
 * exposes the raw numbers and their ratio rather than inventing units.
 */
object Mode06 {

    /** Bytes per test record: CID + value(2) + limit(2). */
    const val RECORD_LENGTH = 5

    /**
     * A value of 0 against a limit of 0xFFFF: the ECU has **no stored result** for this
     * test. Distinct from a test that ran and scored zero, which would carry a real limit.
     */
    const val NO_RESULT_LIMIT = 0xFFFF

    data class Record(
        val tid: Int,
        val cid: Int,
        val value: Int,
        val limit: Int
    ) {
        /**
         * True when this test has never produced a result.
         *
         * The 0 / 0xFFFF pair is the conventional "never ran" encoding, and on this car it
         * is what TID $83 CID $03 -- the 0.020 inch EVAP leak test -- reports while its two
         * siblings carry real numbers.
         */
        val noResult: Boolean get() = limit == NO_RESULT_LIMIT && value == 0

        /**
         * How close the measurement sits to its limit, or null when there is no result.
         *
         * Deliberately a ratio: it is meaningful without knowing the scaling, which is the
         * one thing a raw pre-CAN Mode $06 value cannot give you. "0.92 of the limit"
         * answers how much margin the car has; converting to kPa would need a conversion
         * nobody has published.
         */
        val ratio: Double? get() = if (noResult || limit == 0) null else value.toDouble() / limit

        val label: String get() = labelFor(tid, cid)
    }

    /**
     * TID/CID names, mapped from the FSM's table (EN(STi)(diag)-25) through the +$80 offset
     * the sweep revealed.
     *
     * Marked as correlation, not documentation: the FSM never names TID $83. The mapping
     * rests on the offset being consistent and on TID $83 carrying exactly three CIDs where
     * the FSM's EVAP row lists exactly three leak sizes. Strong, but not a Subaru citation.
     */
    private val LABELS: Map<Pair<Int, Int>, String> = mapOf(
        (0x81 to 0x01) to "Catalyst efficiency",
        (0x83 to 0x01) to "EVAP large leak",
        (0x83 to 0x02) to "EVAP small leak (0.040 in)",
        (0x83 to 0x03) to "EVAP very small leak (0.020 in)",
        (0x85 to 0x01) to "O2 sensor slow response (B1S1)",
        (0x85 to 0x02) to "O2 sensor circuit (B1S1)"
    )

    fun labelFor(tid: Int, cid: Int): String =
        LABELS[tid to cid] ?: "TID %02X CID %02X".format(tid, cid)

    /**
     * Reads the test records for [tid] out of a raw K-line capture.
     *
     * **Takes the raw response, not a flat payload.** The ECU sends one record per KWP
     * message, so the bytes between records are a checksum and the next message's header.
     * Chunking a flat payload by five instead produces records that decode without error
     * and are entirely fictional — the first is right and everything after it is
     * misaligned garbage, which is worse than a parse failure because nothing looks wrong.
     *
     * A message carrying fewer than [RECORD_LENGTH] bytes is skipped rather than padded.
     */
    fun decodeRecords(raw: ByteArray, tid: Int): List<Record> =
        Obd2Frame.responseFrames(raw, 0x06, tid)
            .filter { it.size >= RECORD_LENGTH }
            .map { rec ->
                fun b(o: Int) = rec[o].toInt() and 0xFF
                Record(
                    tid = tid,
                    cid = b(0),
                    value = (b(1) shl 8) or b(2),
                    limit = (b(3) shl 8) or b(4)
                )
            }

    /**
     * The supported-TID bitmask from a `06 <base>` response payload.
     *
     * Payload is a constant prefix byte then four mask bytes, MSB first, where the bit for
     * `base + 1` is the high bit of the first mask byte. The last bit of the fourth byte
     * marks that the next range exists, exactly like Mode $01's PID $20 chain.
     */
    fun decodeSupportedTids(base: Int, payload: ByteArray): List<Int> {
        if (payload.size < 5) return emptyList()
        val mask = payload.copyOfRange(1, 5)
        return buildList {
            mask.forEachIndexed { byteIndex, byte ->
                for (bit in 7 downTo 0) {
                    if ((byte.toInt() shr bit) and 1 == 1) {
                        add(base + 1 + byteIndex * 8 + (7 - bit))
                    }
                }
            }
        }
    }

    /** Renders records for the chat: label, value against limit, and margin. */
    fun format(records: List<Record>): String = buildString {
        records.forEach { r ->
            append("\n")
            append(r.label)
            append("\n  ")
            if (r.noResult) {
                append("never run — no stored result")
            } else {
                append("value ")
                append(r.value)
                append(" / limit ")
                append(r.limit)
                r.ratio?.let { append(" (%.1f%% of limit)".format(it * 100)) }
            }
        }
    }
}
