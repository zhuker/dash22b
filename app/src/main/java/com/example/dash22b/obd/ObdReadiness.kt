package com.example.dash22b.obd

/**
 * Decoder for generic OBD-II Mode $01 PID $01 — "monitor status since DTCs cleared",
 * the emissions readiness bitfield read by every I/M Readiness screen.
 *
 * This is NOT SSM. The readiness monitors do not exist in the SSM address space this
 * ECU exposes: RomRaider's definition does carry them (switches S84-S91 at 0x22130C) but
 * gates them on ecubyteindex 115, and this ECU's init response is 57 bytes, so the highest
 * capability index it can express is 55. They are filtered out and always will be. The FSM
 * agrees and points at generic OBD-II instead: EN(STi)(diag)-24 lists Mode $01 PID 01 as
 * "Number of emission-related powertrain DTC and malfunction indicator light status and
 * diagnosis support information" -- that last phrase is this bitfield.
 *
 * See questions/readiness-monitors.html in the 22b repo for the full derivation.
 *
 * Response data layout (4 bytes, after the mode/PID echo):
 *
 *   A: bit 7      MIL on/off
 *      bits 0-6   number of stored DTCs
 *   B: bits 0-2   continuous monitors supported (misfire, fuel system, components)
 *      bit 3      0 = spark ignition, 1 = compression ignition
 *      bits 4-6   those same three, INCOMPLETE when set
 *   C: bits 0-7   non-continuous monitors supported
 *   D: bits 0-7   those same monitors, INCOMPLETE when set
 *
 * The polarity is the part worth stating twice: in bytes B and D a SET bit means the
 * monitor has NOT finished. Reading it backwards turns a failing car into a passing one.
 */
object ObdReadiness {

    /** Mode $01, PID $01. */
    const val MODE: Int = 0x01
    const val PID: Int = 0x01

    /** Number of data bytes (A-D) this PID returns. */
    const val DATA_LENGTH: Int = 4

    /**
     * One monitor's state.
     *
     * [NOT_SUPPORTED] and [INCOMPLETE] are very different answers even though both mean
     * "not READY": the first is a monitor this ECU does not implement (nothing to wait
     * for, and no bearing on a Smog Check), the second is one that exists and has not
     * finished running yet.
     */
    enum class Status { READY, INCOMPLETE, NOT_SUPPORTED }

    /**
     * The monitors, in the exact bit order of the PID $01 response so the report reads
     * the way a scan tool prints it.
     *
     * [continuous] monitors live in byte B and run constantly; the rest live in byte C/D
     * and run once per drive cycle when their enabling conditions are met.
     */
    enum class Monitor(
        val label: String,
        val bit: Int,
        val continuous: Boolean
    ) {
        MISFIRE("Misfire", 0, true),
        FUEL_SYSTEM("Fuel System", 1, true),
        COMPONENTS("Comprehensive Component", 2, true),

        CATALYST("Catalyst", 0, false),
        HEATED_CATALYST("Heated Catalyst", 1, false),
        EVAPORATIVE("Evaporative System", 2, false),
        SECONDARY_AIR("Secondary Air System", 3, false),
        AC_REFRIGERANT("A/C system refrigerant", 4, false),
        OXYGEN_SENSOR("Oxygen Sensor", 5, false),
        OXYGEN_SENSOR_HEATER("Oxygen Sensor heater", 6, false),
        EGR("EGR system", 7, false)
    }

    data class Report(
        val milOn: Boolean,
        val dtcCount: Int,
        val compressionIgnition: Boolean,
        val statuses: Map<Monitor, Status>
    ) {
        /** Monitors this ECU implements that have not finished running. */
        val incomplete: List<Monitor>
            get() = Monitor.entries.filter { statuses[it] == Status.INCOMPLETE }

        /** Monitors this ECU does not implement at all. */
        val unsupported: List<Monitor>
            get() = Monitor.entries.filter { statuses[it] == Status.NOT_SUPPORTED }

        /**
         * Whether this would clear the OBD-II portion of a California Smog Check for a
         * model-year 2000-or-newer gasoline vehicle.
         *
         * Per 16 CCR 3340.42.2, such a vehicle fails with "any incomplete monitors,
         * excluding the evaporative system monitor" -- so EVAP is the one monitor allowed
         * to be incomplete. (For 1996-1999 the allowance is any one monitor; this car is
         * titled as a 2001, so the strict rule is the one that applies.) A commanded-on
         * MIL or a stored DTC fails independently of readiness.
         *
         * This is the readiness half only. A Smog Check also compares the ECU's Cal ID and
         * CVN against the expected calibration -- a non-stock tune fails on that criterion
         * no matter how clean this report is.
         */
        val passesCaliforniaReadiness: Boolean
            get() = !milOn &&
                dtcCount == 0 &&
                incomplete.none { it != Monitor.EVAPORATIVE }
    }

    /**
     * Decodes the four data bytes of a PID $01 response.
     *
     * @param data bytes A, B, C, D — the payload after the `41 01` mode/PID echo.
     * @throws IllegalArgumentException if fewer than four bytes were supplied.
     */
    fun decode(data: ByteArray): Report {
        require(data.size >= DATA_LENGTH) {
            "PID $01 needs $DATA_LENGTH data bytes, got ${data.size}"
        }

        val a = data[0].toInt() and 0xFF
        val b = data[1].toInt() and 0xFF
        val c = data[2].toInt() and 0xFF
        val d = data[3].toInt() and 0xFF

        val statuses = Monitor.entries.associateWith { monitor ->
            val supportedByte = if (monitor.continuous) b else c
            // Continuous monitors carry their "incomplete" flags in the high nibble of the
            // same byte; non-continuous ones get a byte of their own.
            val incompleteBit = if (monitor.continuous) monitor.bit + 4 else monitor.bit
            val incompleteByte = if (monitor.continuous) b else d

            when {
                !supportedByte.hasBit(monitor.bit) -> Status.NOT_SUPPORTED
                incompleteByte.hasBit(incompleteBit) -> Status.INCOMPLETE
                else -> Status.READY
            }
        }

        return Report(
            milOn = a.hasBit(7),
            dtcCount = a and 0x7F,
            compressionIgnition = b.hasBit(3),
            statuses = statuses
        )
    }

    private fun Int.hasBit(bit: Int): Boolean = (this shr bit) and 1 == 1
}
