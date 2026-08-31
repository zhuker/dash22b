package com.example.dash22b.data

import com.example.dash22b.obd.ObdReadiness

/**
 * Turns an [ObdReadiness.Report] into the chat message the Messages tab shows.
 *
 * Split out of DashService so the wording -- particularly the pass/fail verdict, which is
 * the line a person will act on -- can be unit tested without an Android runtime.
 */
object ReadinessMessage {

    /**
     * Renders a readiness report the way a scan tool prints it — monitors in PID $01 bit
     * order, with the California verdict spelled out, since passing the biennial smog is
     * the reason to look at this screen at all.
     */
    fun format(report: ObdReadiness.Report): String = buildString {
        append("MIL: ")
        append(if (report.milOn) "ON" else "Off")
        append("\nStored DTCs: ")
        append(report.dtcCount)
        append('\n')

        ObdReadiness.Monitor.entries.forEach { monitor ->
            append('\n')
            append(monitor.label)
            append(": ")
            append(
                when (report.statuses[monitor]) {
                    ObdReadiness.Status.READY -> "READY"
                    ObdReadiness.Status.INCOMPLETE -> "NOT READY"
                    // The ECU does not implement this monitor -- nothing to wait for, and
                    // it has no bearing on a Smog Check.
                    else -> "NA"
                }
            )
        }

        append("\n\n")
        if (report.passesCaliforniaReadiness) {
            append("Clears the CA OBD-II readiness check")
            val unsupported = report.unsupported.size
            if (unsupported > 0) {
                append(" ($unsupported monitor${if (unsupported == 1) "" else "s"} not supported by this ECU)")
            }
            append(".")
            if (report.incomplete.isNotEmpty()) {
                // Only reachable via the EVAP carve-out.
                append(" EVAP is incomplete but California excuses that one.")
            }
        } else if (report.milOn || report.dtcCount > 0) {
            append("Would FAIL smog: a lit MIL or a stored code fails regardless of monitors.")
        } else {
            val blocking = report.incomplete.filter { it != ObdReadiness.Monitor.EVAPORATIVE }
            append("Would FAIL smog: ")
            append(blocking.joinToString(", ") { it.label })
            append(if (blocking.size == 1) " is not complete." else " are not complete.")
            append(" Drive it and re-check.")
        }
        append("\n\nNote: this is the readiness half only. Smog also checks Cal ID/CVN, which a non-stock tune fails.")
    }
}
