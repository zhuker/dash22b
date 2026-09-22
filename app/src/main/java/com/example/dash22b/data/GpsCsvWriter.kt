package com.example.dash22b.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Records GNSS fixes as their own CSV files, one row per delivered fix.
 *
 * Kept separate from [MonitorCsvWriter] rather than bolted onto it, for three reasons:
 *
 *  - The monitor CSV only has rows while the ECU is answering. GPS must keep recording with
 *    no cable attached, which is most of what a track is for.
 *  - Fixes arrive at whatever rate the receiver produces (1 Hz on most hardware). Folding
 *    them into a ~10 Hz monitor row would repeat each fix ten times, or invent values
 *    between them.
 *  - Position needs [Double]. The monitor CSV carries `Float` parameter values.
 *
 * One row per fix also makes a later GPX export a straight read of this file.
 *
 * The file is never rotated on a schedule: a new one is started per writer instance, and
 * reopened if the file goes away underneath us, which is what "clear logs" does mid-drive.
 */
class GpsCsvWriter(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val fixes = Channel<GpsFix>(capacity = 256)
    private val writerJob: Job

    init {
        directory.mkdirs()
        writerJob = scope.launch {
            writeFixes()
        }
    }

    /** Queues a fix without blocking the location callback. */
    fun record(fix: GpsFix) {
        if (fixes.trySend(fix).isFailure) {
            Timber.w("GPS CSV buffer full; dropping fix at ${fix.utcMillis}")
        }
    }

    /** Stops accepting fixes. Already queued fixes are written before exit. */
    fun close() {
        fixes.close()
    }

    /** Test/support hook for callers that need to wait until queued data is on disk. */
    suspend fun join() {
        writerJob.join()
    }

    private suspend fun writeFixes() {
        var writer: BufferedWriter? = null
        var file: File? = null
        var rowsSinceFlush = 0

        try {
            for (fix in fixes) {
                if (writer == null || file?.exists() == false) {
                    writer?.flush()
                    writer?.close()
                    file = nextFile(fix.recordedAtMillis)
                    writer = openFile(file)
                    rowsSinceFlush = 0
                }

                val activeWriter = checkNotNull(writer)
                activeWriter.append(row(fix))
                activeWriter.newLine()

                rowsSinceFlush++
                if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                    activeWriter.flush()
                    rowsSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "GPS CSV writer failed")
        } finally {
            try {
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                Timber.e(e, "Failed to close GPS CSV")
            }
        }
    }

    private fun row(fix: GpsFix): String = buildString {
        append(formatTimestamp(fix.recordedAtMillis))
        append(',')
        append(formatTimestamp(fix.utcMillis))
        append(',')
        append(fix.elapsedRealtimeNanos)
        append(',')
        append(degrees(fix.latitude))
        append(',')
        append(degrees(fix.longitude))
        append(',')
        append(decimal(fix.speedKmh?.toDouble()))
        append(',')
        append(decimal(fix.altitudeMeters))
        append(',')
        append(decimal(fix.bearingDegrees?.toDouble()))
        append(',')
        append(decimal(fix.accuracyMeters?.toDouble()))
        append(',')
        append(fix.satellitesUsed?.toString() ?: "")
    }

    private fun nextFile(timestamp: Long): File {
        val baseName = "$FILE_PREFIX${formatFilenameTimestamp(timestamp)}"
        var file = File(directory, "$baseName.csv")
        var suffix = 2
        while (file.exists()) {
            file = File(directory, "${baseName}_$suffix.csv")
            suffix++
        }
        return file
    }

    private fun openFile(file: File): BufferedWriter {
        directory.mkdirs()
        return BufferedWriter(FileWriter(file, false)).also { writer ->
            writer.append(HEADER)
            writer.newLine()
            writer.flush()
            Timber.i("Started GPS CSV: ${file.name}")
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        checkNotNull(ISO_TIMESTAMP.get()).format(Date(timestamp))

    private fun formatFilenameTimestamp(timestamp: Long): String =
        checkNotNull(FILE_TIMESTAMP.get()).format(Date(timestamp))

    companion object {
        private val FILE_PREFIX = LogArchiver.GPS_CSV_PREFIX

        /**
         * `timestamp` is the device clock at delivery, the same basis as the monitor CSV's
         * timestamp column, so the two files join directly. `gps_utc` is the receiver's own
         * clock, which is right even when the head unit's is not.
         */
        const val HEADER =
            "timestamp,gps_utc,elapsed_realtime_ns,latitude,longitude," +
                "speed_kmh,altitude_m,bearing_deg,accuracy_m,satellites"

        private const val FLUSH_EVERY_ROWS = 10

        /** Seven decimals is about 1 cm, finer than any receiver, and never in exponent form. */
        private fun degrees(value: Double): String = String.format(Locale.US, "%.7f", value)

        private fun decimal(value: Double?): String =
            if (value == null) "" else String.format(Locale.US, "%.2f", value)

        private val ISO_TIMESTAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val FILE_TIMESTAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
        }
    }
}
