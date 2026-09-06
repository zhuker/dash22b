package com.example.dash22b.data

import android.content.Context

/** [OdometerStore] backed by SharedPreferences, alongside the presets. */
class PrefsOdometerStore(context: Context) : OdometerStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stored as a Double bit pattern: SharedPreferences has no putDouble, and a Float
    // odometer would quantise to about 8 m once past 100,000 km.
    override fun readMeters(): Double =
        Double.fromBits(prefs.getLong(KEY_ODOMETER_METERS, 0.0.toRawBits()))

    override fun writeMeters(meters: Double) {
        prefs.edit().putLong(KEY_ODOMETER_METERS, meters.toRawBits()).apply()
    }

    companion object {
        private const val PREFS_NAME = "dashboard_odometer"
        private const val KEY_ODOMETER_METERS = "odometer_meters"
    }
}
