package com.example.dash22b.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Android-side glue for getting [LogArchiver] output off the head unit.
 *
 * Kept separate from LogArchiver so the zip/delete logic stays unit-testable without a
 * device: everything here needs a real Context, a FileProvider, or the share sheet.
 */
object LogSharing {

    /** Staging dir for archives handed to other apps; matches `res/xml/file_paths.xml`. */
    private const val SHARE_DIR = "shared-logs"

    /**
     * The directory the app writes logs into. Mirrors what `DashService` hands
     * [MonitorCsvWriter] and what `DashApplication` uses for the Timber file log, so all
     * three agree on where logs live even on a device with no external storage.
     */
    fun archiver(context: Context): LogArchiver =
        LogArchiver(context.getExternalFilesDir(null) ?: context.filesDir)

    /**
     * Zips [logs] into the share staging dir and returns the archive.
     *
     * Previous archives are removed first -- they are copies of data that still exists in
     * the log dir, so keeping them around only doubles the space the app uses.
     */
    suspend fun buildArchive(context: Context, logs: List<LogArchiver.LogFile>): File =
        withContext(Dispatchers.IO) {
            val shareDir = File(context.cacheDir, SHARE_DIR)
            shareDir.mkdirs()
            shareDir.listFiles()?.forEach { stale ->
                if (!stale.delete()) Timber.w("Could not remove stale archive ${stale.name}")
            }
            archiver(context).zipInto(File(shareDir, LogArchiver.archiveName()), logs)
        }

    /**
     * Opens the system share sheet for [archive] (Drive, mail, Files, ...).
     *
     * The chooser is started from a non-Activity context in some call paths, so
     * NEW_TASK is set; the read grant rides along on the intent and is scoped to the
     * one URI the receiving app is handed.
     */
    fun share(context: Context, archive: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archive
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, archive.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share dash22b logs").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
