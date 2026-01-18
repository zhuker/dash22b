package com.example.dash22b

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Rotate logs before initializing logging
        rotateLogs()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // Always plant FileLoggingTree (or you can condition it on DEBUG/RELEASE)
        Timber.plant(FileLoggingTree())
    }

    private fun rotateLogs() {
        val logFile = File(getExternalFilesDir(null), "app_logs.txt")
        Log.d("DashApplication", "log file $logFile")
        if (logFile.exists()) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(logFile.lastModified()))
            val backupFile = File(getExternalFilesDir(null), "app_logs_$timestamp.txt")
            if (logFile.renameTo(backupFile)) {
                Log.i("DashApplication", "Log file rotated: ${backupFile.name}")
            } else {
                Log.e("DashApplication", "Failed to rotate log file")
            }
        }
    }

    private inner class FileLoggingTree : Timber.Tree() {
        
        private val logChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 1000)
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())
        
        init {
            scope.launch {
                val logFile = File(getExternalFilesDir(null), "app_logs.txt")
                var writer: FileWriter? = null
                try {
                    writer = FileWriter(logFile, true)
                    for (msg in logChannel) {
                        writer.append(msg)
                        writer.flush() // Robustness: Ensure it's on disk immediately
                    }
                } catch (e: Exception) {
                    Log.e("FileLoggingTree", "Error in log writer loop", e)
                } finally {
                    try {
                        writer?.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val priorityStr = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> "?"
            }
            
            val logMessage = StringBuilder()
                .append("$timestamp $priorityStr/$tag: $message\n")
            
            if (t != null) {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                logMessage.append(sw.toString())
            }

            // Non-blocking send (drops logs if buffer full to avoid crashing/stalling app)
            logChannel.trySend(logMessage.toString())
        }
        
        fun close() {
            logChannel.close()
            // Scope cancellation is handled by process death usually, 
            // but strict cleanup could be added if this Tree had a lifecycle.
        }
    }
}
