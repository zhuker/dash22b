package com.example.dash22b.data

import com.example.dash22b.BuildConfig
import com.example.dash22b.obd.Obd2SerialManager
import com.example.dash22b.obd.ObdProbe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A capture of raw generic OBD-II responses, written to disk for offline analysis.
 *
 * This exists because the interesting protocol here is undocumented. Mode $06 record
 * layout and value scaling on a pre-CAN Subaru are manufacturer-defined and unpublished,
 * so the only way to learn them is to collect real responses and correlate them against
 * events whose timing is known from other logs. That makes the dump's job **fidelity, not
 * interpretation**: every probe stores the exact bytes sent and heard, and a probe that
 * fails stores why it failed rather than vanishing.
 *
 * Dumps land in the same directory as the monitor CSVs and the debug log, so `share logs`
 * carries them off the head unit with everything else.
 *
 * See docs/mode06_test_results_plan.md for what the captures are for.
 */
object DiagnosticDump {

    /** Prefix for dump files; [LogArchiver] lists these so they can be shared. */
    const val FILE_PREFIX = "obd_dump_"

    @Serializable
    data class Entry(
        val probe: String,
        val mode: Int,
        val pid: Int,
        val label: String,
        val why: String,
        val requestHex: String? = null,
        val responseHex: String? = null,
        val dataHex: String? = null,
        val negativeResponse: Boolean = false,
        val error: String? = null
    )

    @Serializable
    data class Dump(
        val capturedAt: String,
        val appVersion: String,
        val gitBranch: String,
        /** Which init the ECU answered: KWP2000 fast, or ISO 9141-2 slow. */
        val protocol: String,
        val entries: List<Entry>
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Runs [probes] against an already-connected [obd] and returns the capture.
     *
     * The session is assumed open: establishing it means taking the K-line away from SSM,
     * which is the caller's business to sequence.
     */
    fun capture(
        obd: Obd2SerialManager,
        probes: List<ObdProbe> = ObdProbe.defaultDump(),
        now: Long = System.currentTimeMillis()
    ): Dump {
        val entries = probes.map { probe ->
            val result = obd.probe(probe.mode, probe.pid, probe.extra)
            if (result == null) {
                Timber.w("Probe ${probe.id} could not be sent")
                Entry(
                    probe = probe.id,
                    mode = probe.mode,
                    pid = probe.pid,
                    label = probe.label,
                    why = probe.why,
                    error = "request could not be sent"
                )
            } else {
                Entry(
                    probe = probe.id,
                    mode = probe.mode,
                    pid = probe.pid,
                    label = probe.label,
                    why = probe.why,
                    requestHex = result.requestHex,
                    responseHex = result.rawHex,
                    dataHex = result.dataHex,
                    negativeResponse = result.negative
                )
            }
        }

        return Dump(
            capturedAt = timestamp(now),
            appVersion = BuildConfig.VERSION_NAME,
            gitBranch = BuildConfig.GIT_BRANCH,
            protocol = obd.negotiatedFormat()?.name ?: "unknown",
            entries = entries
        )
    }

    /**
     * Writes [dump] into [directory] as `obd_dump_<timestamp>.json` and returns the file.
     *
     * Dumps are never overwritten or rotated: phase 2 of the Mode $06 work is a diff
     * between a capture taken before a monitor ran and one taken after, so an old dump is
     * the more valuable half of the pair.
     */
    fun write(directory: File, dump: Dump, now: Long = System.currentTimeMillis()): File {
        directory.mkdirs()
        val file = File(directory, "$FILE_PREFIX${fileTimestamp(now)}.json")
        file.writeText(json.encodeToString(dump))
        Timber.i("Wrote diagnostic dump: ${file.name} (${dump.entries.size} probes)")
        return file
    }

    /** One-line-per-probe summary for the chat, kept short; the file has the detail. */
    fun summarise(dump: Dump): String = buildString {
        append("OBD dump over ")
        append(dump.protocol)
        append(", ")
        append(dump.entries.size)
        append(" probes:\n")

        dump.entries.forEach { entry ->
            append("\n")
            append(entry.probe)
            append(' ')
            append(entry.label)
            append("\n  ")
            append(
                when {
                    entry.error != null -> "-- ${entry.error}"
                    entry.negativeResponse -> "-- negative response (not supported)"
                    entry.dataHex.isNullOrBlank() -> "-- no data (raw: ${entry.responseHex})"
                    else -> entry.dataHex
                }
            )
        }

        val answered = dump.entries.count { !it.dataHex.isNullOrBlank() }
        append("\n\n")
        append(answered)
        append(" of ")
        append(dump.entries.size)
        append(" probes returned data. Full capture saved — send it with \"share logs\".")
    }

    private fun timestamp(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now))

    private fun fileTimestamp(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
}
