package com.example.dash22b.obd

/**
 * One generic OBD-II request to issue during a diagnostic dump, and why.
 *
 * A probe is deliberately dumb: it names a service and a parameter and nothing else. No
 * probe carries a parser, because the point of a dump is to capture bytes whose layout is
 * not yet known — the moment a probe starts interpreting, a wrong assumption about the
 * layout silently discards the evidence needed to correct it.
 */
data class ObdProbe(
    val mode: Int,
    val pid: Int,
    val label: String,
    val why: String
) {
    val id: String get() = "%02X%02X".format(mode, pid)

    companion object {
        /**
         * The Mode $06 test IDs this ECU is documented to implement, from the FSM's
         * General Scan Tool section (EN(STi)(diag)-25).
         *
         * These are listed explicitly rather than discovered through `06 00` because the
         * supported-TID bitmask at TID $00 is a CAN-era convention (OBDMID $00) that a
         * pre-CAN ECU need not implement. Probing $00 and treating a negative response as
         * "no Mode $06 support" would be the wrong conclusion from the wrong question, so
         * $00 is probed as a bonus while the real enumeration comes from the FSM.
         */
        val FSM_MODE06_TIDS: List<Pair<Int, String>> = listOf(
            0x01 to "Catalyst system efficiency below threshold",
            0x03 to "EVAP leak tests (CID 01 large / 02 small / 03 very small)",
            0x05 to "O2 sensor circuit slow response (B1S1)",
            0x06 to "O2 sensor circuit (B1S2)",
            0x07 to "O2 sensor circuit slow response (B1S2)",
            0x0C to "Coolant thermostat below regulating temperature",
            0x0F to "Drain valve range / performance"
        )

        /**
         * The full dump, in the order it is issued.
         *
         * Ordered cheapest-and-most-diagnostic first: if Mode $01 PID $00 comes back empty
         * the session itself is wrong and every later result is noise, which is worth
         * knowing at the top of the file rather than inferred from fifteen null rows.
         */
        fun defaultDump(): List<ObdProbe> = buildList {
            add(
                ObdProbe(
                    0x01, 0x00, "Mode 01 supported PIDs 01-20",
                    "Proves the generic OBD-II session works at all. Everything below is " +
                        "uninterpretable if this is empty."
                )
            )
            add(
                ObdProbe(
                    0x01, 0x01, "Mode 01 PID 01 readiness (raw)",
                    "The bytes behind the readiness report. Captured raw so the decoder can " +
                        "be checked against the wire rather than trusted."
                )
            )

            // Mode $06 -- the reason this dump exists.
            add(
                ObdProbe(
                    0x06, 0x00, "Mode 06 supported TIDs (bonus)",
                    "CAN-era convention; may not exist here. A negative response is a normal " +
                        "outcome, not evidence Mode 06 is unsupported."
                )
            )
            FSM_MODE06_TIDS.forEach { (tid, desc) ->
                add(ObdProbe(0x06, tid, "Mode 06 TID %02X".format(tid), desc))
            }

            // Mode $09 -- vehicle info. Cal ID and CVN are what a CA Smog Check compares
            // against the expected calibration, so reading them here lets the car report
            // its own tune status before a custom map is ever flashed.
            add(ObdProbe(0x09, 0x00, "Mode 09 supported PIDs", "Which vehicle-info items exist."))
            add(ObdProbe(0x09, 0x02, "Mode 09 VIN", "The ECU's own VIN, which on a swap is the donor's."))
            add(ObdProbe(0x09, 0x04, "Mode 09 Cal ID", "Calibration ID -- the BAR Cal ID/CVN check reads this."))
            add(ObdProbe(0x09, 0x06, "Mode 09 CVN", "Calibration verification number -- changes when the ROM changes."))
        }
    }
}
