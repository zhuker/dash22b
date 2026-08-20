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
 * Records converted SSM monitor values as wide CSV files.
 *
 * Each file has a fixed set of columns. When the monitored parameter list or
 * one of its units changes, the current file is closed and a new timestamped
 * file is started. This keeps every file directly readable by pandas while
 * allowing presets to change during a drive.
 */
class MonitorCsvWriter(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private data class Column(val name: String, val unit: DisplayUnit) {
        val heading: String
            get() = if (unit == DisplayUnit.UNKNOWN || unit.displayName().isBlank()) {
                name
            } else {
                "$name [${unit.displayName()}]"
            }
    }

    private val samples = Channel<EngineData>(capacity = 1000)
    private val writerJob: Job

    init {
        directory.mkdirs()
        writerJob = scope.launch {
            writeSamples()
        }
    }

    /** Queues a complete ECU response without blocking the polling loop. */
    fun record(data: EngineData) {
        if (data.values.isEmpty()) return
        if (samples.trySend(data).isFailure) {
            Timber.w("Monitor CSV buffer full; dropping sample at ${data.timestamp}")
        }
    }

    /** Stops accepting samples. Already queued samples are written before exit. */
    fun close() {
        samples.close()
    }

    /** Test/support hook for callers that need to wait until queued data is on disk. */
    suspend fun join() {
        writerJob.join()
    }

    private suspend fun writeSamples() {
        var writer: BufferedWriter? = null
        var columns: List<Column> = emptyList()
        var rowsSinceFlush = 0

        try {
            for (sample in samples) {
                val sampleColumns = sample.values.map { (name, value) -> Column(name, value.unit) }
                if (sampleColumns != columns) {
                    writer?.flush()
                    writer?.close()
                    columns = sampleColumns
                    writer = openFile(sample.timestamp, columns)
                    rowsSinceFlush = 0
                }

                val activeWriter = checkNotNull(writer)
                val values = columns.map { column -> sample.values.getValue(column.name).value }
                activeWriter.append(csvEscape(formatTimestamp(sample.timestamp)))
                values.forEach { value ->
                    activeWriter.append(',')
                    activeWriter.append(value.toString())
                }
                activeWriter.newLine()

                rowsSinceFlush++
                if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                    activeWriter.flush()
                    rowsSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Monitor CSV writer failed")
        } finally {
            try {
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                Timber.e(e, "Failed to close monitor CSV")
            }
        }
    }

    private fun openFile(timestamp: Long, columns: List<Column>): BufferedWriter {
        val baseName = "monitor_${formatFilenameTimestamp(timestamp)}"
        var file = File(directory, "$baseName.csv")
        var suffix = 2
        while (file.exists()) {
            file = File(directory, "${baseName}_$suffix.csv")
            suffix++
        }

        return BufferedWriter(FileWriter(file, false)).also { writer ->
            writer.append("timestamp")
            columns.forEach { column ->
                writer.append(',')
                writer.append(csvEscape(column.heading))
            }
            writer.newLine()
            writer.flush()
            Timber.i("Started monitor CSV: ${file.name}")
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        checkNotNull(ISO_TIMESTAMP.get()).format(Date(timestamp))

    private fun formatFilenameTimestamp(timestamp: Long): String =
        checkNotNull(FILE_TIMESTAMP.get()).format(Date(timestamp))

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    companion object {
        private const val FLUSH_EVERY_ROWS = 20

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
