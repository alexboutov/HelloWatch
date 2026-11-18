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
        private const val METHOD_GRAVITY_MAG = "GRAVITY_MAG"
    }

    // ---- Tunable parameters ----
    /** Linear-acceleration magnitude threshold (m/s^2) that triggers vibration. */
    @Volatile var VibThrsh: Float = 2.0f

    /** Minimum time (sec) between vibration starts. */
    @Volatile var CoolDown: Double = 1.5

    /** M1: P is computed on the GRAVITY_MAG build (watchW) only. */
    private val useGravityMag = BuildConfig.PROJECTION_METHOD == METHOD_GRAVITY_MAG

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor =
        if (useGravityMag) sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val projector = AzimuthProjector()

    private val latestAcc = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private val latestMag = FloatArray(3)

    private var startTimeMs = 0L
    private var tMaxSec = 3.0
    private var active = false
    private var lastVibMs = Long.MIN_VALUE / 2

    /** azimuthDeg: lane direction for this session; null leaves the P column empty. */
    fun start(startMs: Long, maxSec: Double, azimuthDeg: Double? = null) {
        startTimeMs = startMs
        tMaxSec = maxSec
        active = true
        lastVibMs = Long.MIN_VALUE / 2
        projector.reset(if (useGravityMag) azimuthDeg else null)

        val samplingPeriod = SensorManager.SENSOR_DELAY_GAME
        gyroSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        magSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        if (useGravityMag) {
            if (gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, samplingPeriod)
            } else {
                Log.w(TAG, "TYPE_GRAVITY not available; P stays empty")
            }
        }
        if (linAccSensor != null) {
            sensorManager.registerListener(this, linAccSensor, samplingPeriod)
        } else {
            Log.w(TAG, "TYPE_LINEAR_ACCELERATION not available; no samples will be recorded")
        }
    }

    fun stop() {
        active = false
        sensorManager.unregisterListener(this)
        vibrator?.cancel()
    }

    private fun maybeVibrate(mag: Float) {
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
        val nowEpochMs = System.currentTimeMillis()
        val elapsedSec = (nowEpochMs - startTimeMs) / 1000.0
        if (elapsedSec > tMaxSec) {
            active = false
            onMaxDurationReached()
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                System.arraycopy(event.values, 0, latestAcc, 0, 3)
                val accMag = sqrt(
                    latestAcc[0] * latestAcc[0] + latestAcc[1] * latestAcc[1] + latestAcc[2] * latestAcc[2]
                )
                maybeVibrate(accMag)
                val vibrating = SystemClock.elapsedRealtime() - lastVibMs < VIB_DURATION_MS
                // P (m/s^2, 2 decimals) is empty on any row where R is not valid.
                val p = projector.project(latestAcc[0], latestAcc[1], latestAcc[2])
                val pText = if (p != null) String.format(Locale.US, "%.2f", p) else ""
                val row = String.format(
                    Locale.US,
                    // t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,P,epochMs
                    // epochMs = milliseconds since 2020-01-01 00:00:00 UTC.
                    "%.2f,%s,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d",
                    elapsedSec,
                    if (vibrating) "[v]" else "",
                    accMag,
                    latestAcc[0], latestAcc[1], latestAcc[2],
                    latestGyro[0], latestGyro[1], latestGyro[2],
                    latestMag[0], latestMag[1], latestMag[2],
                    pText,
                    nowEpochMs - SwimCsv.EPOCH_2020_MS
                )
                onSampleRecorded(row)
            }
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, latestGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, latestMag, 0, 3)
                projector.onMagnetic(event.values)
            }
            Sensor.TYPE_GRAVITY -> projector.onGravity(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
