package com.example.dash22b

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.dash22b.ui.theme.Dash22bTheme
import com.example.dash22b.ui.DashboardScreen

import androidx.activity.enableEdgeToEdge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import timber.log.Timber
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.example.dash22b.service.TpmsService.ACTION_FORCE_EXIT) {
                Timber.d("Received ACTION_FORCE_EXIT intent")
                finishAndRemoveTask()
                exitProcess(0)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter(com.example.dash22b.service.TpmsService.ACTION_FORCE_EXIT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val requestPermissionLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val allGranted = permissions.entries.all { it.value }
                if (allGranted) {
                    val intent = android.content.Intent(this, com.example.dash22b.service.TpmsService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
            
            val permissionsToRequest = mutableListOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
             // For older Android versions, just start it
             val intent = android.content.Intent(this, com.example.dash22b.service.TpmsService::class.java)
             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                 startForegroundService(intent)
             } else {
                 startService(intent)
             }
        }
        
        enableEdgeToEdge()
        setContent {
            Dash22bTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(exitReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Dash22bTheme {
        Greeting("Android")
    }
}
