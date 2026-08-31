package com.example.dash22b.data

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import timber.log.Timber

/**
 * Collects the app's on-device logs so they can be shared off the head unit.
 *
 * Everything the app writes lands in one directory (`getExternalFilesDir(null)`): the
 * monitor CSVs from [MonitorCsvWriter] and the Timber file log from `DashApplication`.
 * This class is the read/zip/delete side of that directory, kept free of Android
 * framework types so it can be exercised against a temp dir in unit tests.
 */
class LogArchiver(private val directory: File) {

    /** One log file on disk, as presented to the user in the Messages tab. */
    data class LogFile(val file: File, val kind: Kind) {
        val name: String get() = file.name
        val bytes: Long get() = file.length()
    }

    enum class Kind { MONITOR_CSV, DEBUG_LOG, OBD_DUMP }

    /**
     * Every log currently on disk, newest first. The active debug log sorts in with the
     * rest -- it is mid-write, but a snapshot of it is what makes a report useful.
     */
    fun list(): List<LogFile> {
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .mapNotNull { file ->
                val kind = when {
                    file.name.endsWith(".csv") && file.name.startsWith(MONITOR_CSV_PREFIX) -> Kind.MONITOR_CSV
                    file.name == ACTIVE_DEBUG_LOG -> Kind.DEBUG_LOG
                    file.name.startsWith(ROTATED_DEBUG_LOG_PREFIX) && file.name.endsWith(".txt") -> Kind.DEBUG_LOG
                    // Diagnostic captures ride out with the logs; they are the whole point
                    // of running the debug command in the first place.
                    (file.name.startsWith(DiagnosticDump.FILE_PREFIX) ||
                        file.name.startsWith(DiagnosticDump.SWEEP_PREFIX)) &&
                        file.name.endsWith(".json") -> Kind.OBD_DUMP
                    else -> null
                }
                kind?.let { LogFile(file, it) }
            }
            .sortedByDescending { it.file.lastModified() }
    }

    /**
     * Zips [logs] into [destination], flat (no directory entries), and returns the zip.
     *
     * A file that disappears or fails to read mid-zip is skipped rather than failing the
     * whole archive -- the debug log is being appended to while this runs, and a rotation
     * can delete a file out from under us.
     */
    fun zipInto(destination: File, logs: List<LogFile> = list()): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            logs.forEach { log ->
                try {
                    zip.putNextEntry(ZipEntry(log.name).apply { time = log.file.lastModified() })
                    log.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } catch (e: Exception) {
                    Timber.w(e, "Skipping ${log.name} while building log archive")
                }
            }
        }
        return destination
    }

    /**
     * Deletes every log and reports how many went away.
     *
     * The active debug log is truncated rather than deleted: `DashApplication`'s
     * FileLoggingTree holds an open append-mode writer on it, and unlinking the file would
     * send the rest of this session's logging to an inode nothing can read. Truncating
     * keeps the same inode, so the next line written starts a fresh file.
     */
    fun deleteAll(): DeleteResult {
        var deleted = 0
        var failed = 0
        var freedBytes = 0L
        list().forEach { log ->
            val size = log.bytes
            val ok = if (log.name == ACTIVE_DEBUG_LOG) truncate(log.file) else log.file.delete()
            if (ok) {
                deleted++
                freedBytes += size
            } else {
                failed++
                Timber.w("Could not delete log ${log.name}")
            }
        }
        return DeleteResult(deleted = deleted, failed = failed, freedBytes = freedBytes)
    }

    data class DeleteResult(val deleted: Int, val failed: Int, val freedBytes: Long)

    private fun truncate(file: File): Boolean = try {
        FileOutputStream(file, false).close()
        true
    } catch (e: Exception) {
        Timber.w(e, "Could not truncate ${file.name}")
        false
    }

    companion object {
        /** Name of the debug log currently being appended to. Also used by DashApplication. */
        const val ACTIVE_DEBUG_LOG = "app_logs.txt"

        /** Prefix of a debug log that startup rotation set aside, e.g. `app_logs_2026-08-29_19-22-01.txt`. */
        const val ROTATED_DEBUG_LOG_PREFIX = "app_logs_"

        /** Prefix [MonitorCsvWriter] gives each recording. */
        const val MONITOR_CSV_PREFIX = "monitor_"

        // SimpleDateFormat is not thread-safe; same ThreadLocal treatment as MonitorCsvWriter.
        private val ARCHIVE_TIMESTAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        }

        /** Filename for a share-sheet archive, e.g. `dash22b-logs-2026-08-29_19-22-01.zip`. */
        fun archiveName(now: Long = System.currentTimeMillis()): String =
            "dash22b-logs-${checkNotNull(ARCHIVE_TIMESTAMP.get()).format(Date(now))}.zip"

        /** Human-readable size for the file list shown in the chat. */
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
