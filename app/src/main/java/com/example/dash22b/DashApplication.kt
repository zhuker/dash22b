package com.example.dash22b

import android.app.Application
import android.util.Log
import com.example.dash22b.di.AppContainer
import com.example.dash22b.obd.SsmSerialManager
import com.example.dash22b.obd.SsmEcuInit
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize dependency container
        appContainer = AppContainer(this)

        // Rotate logs before initializing logging
        rotateLogs()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // Always plant FileLoggingTree (or you can condition it on DEBUG/RELEASE)
        Timber.plant(FileLoggingTree())
        
        // Attempt SSM ECU init on startup
        tryEcuInit()
    }
    
    private fun tryEcuInit() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            Timber.i("Attempting SSM ECU init via USB serial...")
            val ssm = SsmSerialManager(this@DashApplication)

            if (ssm.connect()) {
                val response = ssm.sendInit(target = 1) // ECU
                if (response != null) {
                    Timber.i("ECU responded! ROM ID: ${SsmEcuInit(response).getRomId()}")

                    // TODO: Initialize ParameterRegistry from XML with ECU capability filtering
                    // When serial cable is connected and ECU responds, we should:
                    // 1. Create SsmEcuInit from response bytes (response already has this data)
                    // 2. Load logger_METRIC_EN_v370.xml from assets
                    // 3. Parse with ParameterRegistry.fromXml(xmlStream, ecuInit)
                    // 4. Replace appContainer.parameterRegistry with the XML-based one
                    //
                    // Example:
                    // val ecuInit = SsmEcuInit(response.toBytes())
                    // val xmlStream = assets.open("logger_METRIC_EN_v370.xml")
                    // val registry = ParameterRegistry.fromXml(xmlStream, ecuInit)
                    // appContainer.updateParameterRegistry(registry)
                } else {
                    Timber.w("No valid response from ECU")
                }
                ssm.disconnect()
            } else {
                Timber.w("Could not connect to USB serial device (not plugged in or no permission)")
            }
        }
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
