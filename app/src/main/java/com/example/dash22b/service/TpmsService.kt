package com.example.dash22b.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.dash22b.MainActivity
import com.example.dash22b.R
import com.example.dash22b.data.TpmsDataSource
import com.example.dash22b.data.TpmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class TpmsService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var tpmsDataSource: TpmsDataSource

    companion object {
        const val CHANNEL_ID = "TpmsChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()

        tpmsDataSource = TpmsDataSource(this)
        startScanning()
    }

    private fun startScanning() {
        serviceScope.launch {
            tpmsDataSource.getTpmsData()
                .onEach { stateMap ->
                    TpmsRepository.updateState(stateMap)
                    updateNotification(stateMap)
                }
                .collect()
        }
    }

    private fun updateNotification(stateMap: Map<String, com.example.dash22b.data.TpmsState>) {
        val validPressures = stateMap.values
            .map { it.pressure.value }
            .filter { it > 0 } // Filter out uninitialized calls if any

        val contentText = if (validPressures.isNotEmpty()) {
            val min = validPressures.minOrNull() ?: 0f
            val max = validPressures.maxOrNull() ?: 0f
            String.format("Min: %.1f bar, Max: %.1f bar", min, max)
        } else {
            "Scanning for tire pressure..."
        }

        val notification = buildNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundService() {
        val notification = buildNotification("Initializing TPMS Service...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ) 
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TPMS Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Try to use a valid icon, might need fallback
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "TPMS Background Service"
            val descriptionText = "Keeps connection to TPMS sensors"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
