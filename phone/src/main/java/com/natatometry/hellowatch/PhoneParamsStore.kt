package com.natatometry.hellowatch

import android.content.Context

/**
 * Keeps the last sent swim params on the phone, so the fields show them again
 * when the app is opened. Phone only: nothing here is sent to the watches.
 */
class PhoneParamsStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "swim_params"
        private const val KEY_TMAX = "tmax_input"
        private const val KEY_AZIMUTH = "azimuth_input"
        private const val KEY_VIBRATION = "vibration_on"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stored Tmax field text; [default] if nothing was stored yet. */
    fun tMaxInput(default: String): String = prefs.getString(KEY_TMAX, default) ?: default

    /** Stored Azimuth field text; [default] if nothing was stored yet. */
    fun azimuthInput(default: String): String = prefs.getString(KEY_AZIMUTH, default) ?: default

    /** Stored Vibration switch; [default] if nothing was stored yet. */
    fun vibrationOn(default: Boolean): Boolean = prefs.getBoolean(KEY_VIBRATION, default)

    /** Stores the three values as they were sent. */
    fun save(tMaxInput: String, azimuthInput: String, vibrationOn: Boolean) {
        prefs.edit()
            .putString(KEY_TMAX, tMaxInput)
            .putString(KEY_AZIMUTH, azimuthInput)
            .putBoolean(KEY_VIBRATION, vibrationOn)
            .apply()
    }
}
