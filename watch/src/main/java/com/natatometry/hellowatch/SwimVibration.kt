package com.natatometry.hellowatch

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibration feedback during a swim: the watch vibrates for 0.5 s when the linear-acceleration
 * magnitude exceeds VibThrsh, with at least CoolDown seconds between vibration starts.
 * All times are sample times on the sensor clock (SensorEvent.timestamp, nanoseconds).
 */
class SwimVibration(context: Context) {

    companion object {
        private const val VIB_DURATION_MS = 500L
        private const val VIB_DURATION_NS = VIB_DURATION_MS * 1_000_000L
    }

    // ---- Tunable parameters ----
    /** Linear-acceleration magnitude threshold (m/s^2) that triggers vibration. */
    @Volatile var VibThrsh: Float = 2.0f

    /** Minimum time (sec) between vibration starts. */
    @Volatile var CoolDown: Double = 1.5

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var hasVibrated = false
    private var lastVibNs = 0L

    /** Call at the start of every session. */
    fun reset() {
        hasVibrated = false
        lastVibNs = 0L
    }

    /**
     * Call once per linear-acceleration sample. Starts a vibration if the rule is met.
     * Returns true if this sample lies in a vibration window (500 ms from the triggering
     * sample, which is itself included): the row gets the "[v]" mark.
     */
    fun onSample(accMag: Float, sampleNs: Long): Boolean {
        val coolDownNs = (CoolDown * 1_000_000_000.0).toLong()
        if (accMag > VibThrsh && (!hasVibrated || sampleNs - lastVibNs >= coolDownNs)) {
            hasVibrated = true
            lastVibNs = sampleNs
            vibrator?.vibrate(
                VibrationEffect.createOneShot(VIB_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
        return hasVibrated && sampleNs - lastVibNs in 0 until VIB_DURATION_NS
    }

    /** Stops a running vibration. */
    fun cancel() {
        vibrator?.cancel()
    }
}
