package com.example.dash22b.data

import com.example.dash22b.BuildConfig
import com.example.dash22b.obd.Mode06
import com.example.dash22b.obd.Mode09
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

    /** Prefix for sweep captures, which are large and separate from a normal dump. */
    const val SWEEP_PREFIX = "obd_sweep_"

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
        /** KWP2000 negative response code, when the ECU refused. */
        val nrc: Int? = null,
        val error: String? = null
    ) {
        /** The payload bytes as captured. */
        fun dataBytes(): ByteArray = hexToBytes(dataHex)

        /** The whole exchange as captured, echo included — what multi-message decoding needs. */
        fun rawBytes(): ByteArray = hexToBytes(responseHex)

        private fun hexToBytes(hex: String?): ByteArray {
            val parts = hex?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: return ByteArray(0)
            return try {
                ByteArray(parts.size) { parts[it].toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                ByteArray(0)
            }
        }
    }

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

    /** `06 00`, `06 20`, ... return a support bitmask, not test records. */
    private val SUPPORTED_TID_BASES = setOf(0x00, 0x20, 0x40, 0x60, 0x80)

    /** VIN / Cal ID / CVN rendered as text, since that is the form worth reading. */
    private fun describeVehicleInfo(entry: Entry): String {
        val raw = entry.rawBytes()
        return when (entry.pid) {
            Mode09.PID_VIN -> Mode09.text(raw, Mode09.PID_VIN)
            Mode09.PID_CAL_ID -> Mode09.calibrationIds(raw).joinToString(", ")
            Mode09.PID_CVN -> Mode09.calibrationVerificationNumbers(raw).joinToString(", ")
            else -> entry.dataHex ?: ""
        }.ifBlank { entry.dataHex ?: "" }
    }

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
                    negativeResponse = result.negative,
                    nrc = result.nrc
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
    fun write(
        directory: File,
        dump: Dump,
        now: Long = System.currentTimeMillis(),
        prefix: String = FILE_PREFIX
    ): File {
        directory.mkdirs()
        val file = File(directory, "$prefix${fileTimestamp(now)}.json")
        file.writeText(json.encodeToString(dump))
        Timber.i("Wrote diagnostic dump: ${file.name} (${dump.entries.size} probes)")
        return file
    }

    /**
     * Summary for a sweep: the few probes that answered, then the refusals grouped by
     * negative response code.
     *
     * Listing 288 refusals individually would bury the finding. Grouping by NRC keeps the
     * one thing that distinguishes outcomes — a TID answering with a different code than
     * its neighbours is a lead, and would otherwise be lost in the scroll.
     */
    fun summariseSweep(dump: Dump): String = buildString {
        val answered = dump.entries.filter { !it.dataHex.isNullOrBlank() }
        val refused = dump.entries.filter { it.negativeResponse }
        val silent = dump.entries.filter {
            it.dataHex.isNullOrBlank() && !it.negativeResponse && it.error == null
        }

        append("Swept ")
        append(dump.entries.size)
        append(" probes over ")
        append(dump.protocol)
        append(".\n\n")

        if (answered.isEmpty()) {
            append("Nothing answered.")
        } else {
            append(answered.size)
            append(" answered:")
            answered.forEach {
                append("\n  ")
                append(it.probe)
                append("  ")
                append(it.dataHex)
            }
        }

        if (refused.isNotEmpty()) {
            append("\n\n")
            append(refused.size)
            append(" refused, by code:")
            refused.groupBy { it.nrc }.toSortedMap(compareBy { it ?: -1 }).forEach { (nrc, list) ->
                append("\n  NRC ")
                append(nrc?.let { "0x%02X".format(it) } ?: "?")
                append(": ")
                append(list.size)
                append(" (")
                append(list.take(4).joinToString(", ") { it.probe })
                if (list.size > 4) append(", ...")
                append(")")
            }
        }
        if (silent.isNotEmpty()) {
            append("\n\n")
            append(silent.size)
            append(" got no response at all.")
        }
        append("\n\nFull capture saved — send it with \"share logs\".")
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
            when {
                entry.error != null -> append("\n  -- ${entry.error}")
                entry.negativeResponse -> append("\n  -- negative response (not supported)")
                entry.dataHex.isNullOrBlank() -> append("\n  -- no data (raw: ${entry.responseHex})")
                // Mode $06 test records are decodable now that the sweep established the
                // layout, and the decoded form is the whole point -- "never run" and
                // "91.6% of limit" are the answers; the hex is in the file.
                // Mode $06 records and Mode $09 strings both span several KWP messages,
                // so they are decoded from the raw capture rather than the flat payload —
                // see Obd2Frame.frames.
                entry.mode == 0x06 && entry.pid !in SUPPORTED_TID_BASES ->
                    append(Mode06.format(Mode06.decodeRecords(entry.rawBytes(), entry.pid)))
                entry.mode == 0x09 && entry.pid != 0x00 ->
                    append("\n  ${describeVehicleInfo(entry)}")
                else -> append("\n  ${entry.dataHex}")
            }
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
