package com.natatometry.hellowatch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.Locale
import kotlin.math.sqrt

class SwimSensorManager(
    context: Context,
    private val onSampleRecorded: (String) -> Unit,
    private val onMaxDurationReached: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SwimSensorManager"
        private const val VIB_DURATION_MS = 500L
    }

    // ---- Tunable parameters ----
    /** Linear-acceleration magnitude threshold (m/s^2) that triggers vibration. */
    @Volatile var VibThrsh: Float = 2.0f

    /** Minimum time (sec) between vibration starts. */
    @Volatile var CoolDown: Double = 1.5

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val latestAcc = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private val latestMag = FloatArray(3)

    private var startTimeMs = 0L
    private var tMaxSec = 3.0
    private var active = false
    private var lastVibMs = Long.MIN_VALUE / 2

    fun start(startMs: Long, maxSec: Double) {
        startTimeMs = startMs
        tMaxSec = maxSec
        active = true
        lastVibMs = Long.MIN_VALUE / 2

        val samplingPeriod = SensorManager.SENSOR_DELAY_GAME
        accSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        gyroSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        magSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        if (linAccSensor != null) {
            sensorManager.registerListener(this, linAccSensor, samplingPeriod)
        } else {
            Log.w(TAG, "TYPE_LINEAR_ACCELERATION not available; vibration trigger disabled")
        }
    }

    fun stop() {
        active = false
        sensorManager.unregisterListener(this)
        vibrator?.cancel()
    }

    private fun maybeVibrate(linAcc: FloatArray) {
        val mag = sqrt(
            linAcc[0] * linAcc[0] + linAcc[1] * linAcc[1] + linAcc[2] * linAcc[2]
        )
        if (mag <= VibThrsh) return

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastVibMs < (CoolDown * 1000.0).toLong()) return
        lastVibMs = nowMs

        vibrator?.vibrate(
            VibrationEffect.createOneShot(VIB_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        if (elapsedSec > tMaxSec) {
            active = false
            onMaxDurationReached()
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, latestAcc, 0, 3)
                val vibrating = SystemClock.elapsedRealtime() - lastVibMs < VIB_DURATION_MS
                val row = String.format(
                    Locale.US,
                    "%.2f,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                    elapsedSec,
                    if (vibrating) "[v]" else "",
                    latestAcc[0], latestAcc[1], latestAcc[2],
                    latestGyro[0], latestGyro[1], latestGyro[2],
                    latestMag[0], latestMag[1], latestMag[2]
                )
                onSampleRecorded(row)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> maybeVibrate(event.values)
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, latestGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, latestMag, 0, 3)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
