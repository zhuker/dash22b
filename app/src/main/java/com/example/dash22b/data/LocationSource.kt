package com.example.dash22b.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Platform GNSS fixes as a flow, plus the latest fix for callers that only want "now".
 *
 * Uses `android.location.LocationManager` rather than the fused provider: there is no Play
 * Services dependency in this app, and a head unit may have no Google services at all.
 *
 * The update rate is not ours to choose. `minTime` throttles delivery, it never makes the
 * receiver go faster -- the rate is fixed by the GNSS chipset and its HAL, and is 1 Hz on
 * most hardware. So this asks for 0 and takes whatever arrives; anything else could only
 * discard fixes. [logRateOnce] reports the interval actually observed, so the real rate is
 * a measured fact rather than an assumption.
 */
class LocationSource(private val context: Context) {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private val latestFix = AtomicReference<GpsFix?>(null)

    /** Satellites used in the last fix, from the GNSS status callback. -1 until known. */
    private val satellitesUsed = AtomicInteger(-1)

    /** The most recent fix, or null if there has never been one this session. */
    val latest: GpsFix? get() = latestFix.get()

    /**
     * The most recent fix if it is recent enough to describe where the car is now, else null.
     * See [GpsFix.isFresh] for why a stale fix is dropped rather than held.
     */
    fun freshFix(maxAgeMillis: Long = GpsFix.MAX_AGE_MS): GpsFix? =
        latestFix.get()?.takeIf { it.isFresh(SystemClock.elapsedRealtimeNanos(), maxAgeMillis) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every fix the receiver delivers, until the collector stops.
     *
     * Emits nothing and completes immediately when location permission has not been granted
     * or the device has no location manager, so callers need no permission check of their own.
     */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<GpsFix> = callbackFlow {
        val manager = locationManager
        if (manager == null) {
            Timber.w("No LocationManager on this device; GPS logging disabled")
            close()
            return@callbackFlow
        }
        if (!hasPermission()) {
            Timber.w("Location permission not granted; GPS logging disabled")
            close()
            return@callbackFlow
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // Not fatal: location can be switched on mid-drive, and the listener stays
            // registered across that.
            Timber.w("GPS provider is disabled; no fixes until location is turned on")
        }

        var previousElapsedNanos = 0L
        var rateLogged = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = location.toGpsFix(satellitesUsed.get())
                latestFix.set(fix)
                if (!rateLogged) {
                    rateLogged = logRateOnce(previousElapsedNanos, fix.elapsedRealtimeNanos)
                }
                previousElapsedNanos = fix.elapsedRealtimeNanos
                trySend(fix)
            }

            // Implemented rather than inherited: LocationListener only gained default
            // methods in API 30, and this app runs back to 24, where a missing override is
            // an AbstractMethodError at the first callback.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) {
                Timber.i("Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Timber.w("Location provider disabled: $provider; fixes stop until it returns")
            }
        }

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                satellitesUsed.set(used)
            }
        }

        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper()
            )
            manager.registerGnssStatusCallback(statusCallback, null)
            Timber.i("GPS updates requested at the receiver's own rate")
        } catch (e: SecurityException) {
            // Permission can be revoked between the check above and this call.
            Timber.e(e, "Location permission refused at registration; GPS logging disabled")
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                manager.removeUpdates(listener)
                manager.unregisterGnssStatusCallback(statusCallback)
                Timber.i("GPS updates released")
            } catch (e: Exception) {
                Timber.e(e, "Failed to release GPS updates")
            }
        }
    }

    /**
     * Logs the interval between the first two fixes, once per session, and returns whether
     * it did. One measurement is enough to tell 1 Hz hardware from 5 or 10 Hz.
     */
    private fun logRateOnce(previousElapsedNanos: Long, elapsedNanos: Long): Boolean {
        if (previousElapsedNanos <= 0L) return false
        val intervalMs = (elapsedNanos - previousElapsedNanos) / 1_000_000L
        if (intervalMs <= 0L) return false
        Timber.i("GPS fix interval: ${intervalMs}ms (about %.1f Hz)", 1000.0 / intervalMs)
        return true
    }

    companion object {
        /**
         * Throttle only. Zero means "deliver every fix"; it cannot speed the receiver up.
         */
        private const val MIN_INTERVAL_MS = 0L
        private const val MIN_DISTANCE_M = 0f
    }
}

private const val MPS_TO_KMH = 3.6f

/**
 * Flattens a platform [Location] into the plain [GpsFix] the writers and tests use.
 *
 * Optional fields are null when the receiver did not report them, never zero: a bearing of
 * 0.0 is due north, and a speed of 0.0 is standing still. Both are real readings that must
 * not be confused with a missing one.
 */
internal fun Location.toGpsFix(satellitesUsed: Int): GpsFix = GpsFix(
    latitude = latitude,
    longitude = longitude,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    utcMillis = time,
    recordedAtMillis = System.currentTimeMillis(),
    // Doppler-derived, not differentiated position, so it is low-noise even at 1 Hz.
    speedKmh = if (hasSpeed()) speed * MPS_TO_KMH else null,
    altitudeMeters = if (hasAltitude()) altitude else null,
    bearingDegrees = if (hasBearing()) bearing else null,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    satellitesUsed = satellitesUsed.takeIf { it >= 0 }
)
