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
import androidx.compose.runtime.CompositionLocalProvider
import com.example.dash22b.di.LocalDtcRepository
import com.example.dash22b.di.LocalGpsRepository
import com.example.dash22b.di.LocalHistoryStore
import com.example.dash22b.di.LocalParameterRegistry
import com.example.dash22b.di.LocalPresetManager
import com.example.dash22b.di.LocalSsmRepository
import com.example.dash22b.di.LocalTpmsRepository
import com.example.dash22b.service.DashService
import timber.log.Timber
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DashService.ACTION_FORCE_EXIT) {
                Timber.d("Received ACTION_FORCE_EXIT intent")
                finishAndRemoveTask()
                exitProcess(0)
            }
        }
    }
    /**
     * Asks for the runtime permissions, and only logs the answers.
     *
     * Each subsystem decides for itself what it can do without its permission -- TPMS
     * without BLUETOOTH_SCAN, GPS without location -- so a refused dialog must not take the
     * whole dashboard down with it.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (name, granted) ->
            Timber.i("Permission ${name.substringAfterLast('.')}: ${if (granted) "granted" else "refused"}")
        }
        // Deliberately does not start the service. This callback runs before the activity is
        // resumed -- immediately, when every permission is already granted -- and a
        // foreground service started at that moment counts as started from the background.
        // onResume follows either way, and starts it from a state that keeps location access.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter(DashService.ACTION_FORCE_EXIT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
        
        requestPermissionLauncher.launch(requiredPermissions())

        
        enableEdgeToEdge()
        setContent {
            val container = (application as DashApplication).appContainer

            CompositionLocalProvider(
                LocalParameterRegistry provides container.parameterRegistry,
                LocalTpmsRepository provides container.tpmsRepository,
                LocalPresetManager provides container.presetManager,
                LocalSsmRepository provides container.ssmRepository,
                LocalHistoryStore provides container.historyStore,
                LocalGpsRepository provides container.gpsRepository,
                LocalDtcRepository provides container.dtcRepository
            ) {
                Dash22bTheme {
                    // A surface container using the 'background' color from the theme
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        DashboardScreen()
                    }
                }
            }
        }
    }

    /**
     * The service is (re)started from a resumed activity, never from onCreate.
     *
     * Before Android 12 a foreground service started while the app is not yet in the
     * foreground is denied "while in use" access to location, camera and microphone for its
     * whole life -- silently. That is how the BLE scan lost its location permission and got
     * no scan results, and GPS would have gone the same way. onResume is the first point
     * where the app is unambiguously in the foreground.
     */
    override fun onResume() {
        super.onResume()
        // Posted, not called inline: onResume runs before the activity has actually been
        // drawn, and ActivityManager still counts the process as background at that point,
        // which is what stamps the service as ineligible for while-in-use location.
        window.decorView.post { startDashService() }
    }

    private fun startDashService() {
        Timber.i("Starting DashService from a resumed activity")
        val intent = Intent(this, DashService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * The runtime permissions this build actually needs, by API level.
     *
     * ACCESS_FINE_LOCATION has been a runtime permission since API 23 and is needed for BLE
     * scan results as well as GPS. It used to be requested only on API 31+, together with
     * the Bluetooth permissions that really are new there, so on an older device the app
     * never asked for location at all and the TPMS scan silently returned nothing.
     */
    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions += android.Manifest.permission.BLUETOOTH_SCAN
            permissions += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions += android.Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
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
