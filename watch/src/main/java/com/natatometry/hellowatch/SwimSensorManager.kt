package com.natatometry.hellowatch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.Locale

class SwimSensorManager(
    context: Context,
    private val onSampleRecorded: (String) -> Unit,
    private val onMaxDurationReached: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val latestAcc = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private val latestMag = FloatArray(3)

    private var startTimeMs = 0L
    private var tMaxSec = 3.0
    private var active = false

    fun start(startMs: Long, maxSec: Double) {
        startTimeMs = startMs
        tMaxSec = maxSec
        active = true

        val samplingPeriod = SensorManager.SENSOR_DELAY_GAME
        accSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        gyroSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
        magSensor?.let { sensorManager.registerListener(this, it, samplingPeriod) }
    }

    fun stop() {
        active = false
        sensorManager.unregisterListener(this)
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
                val row = String.format(
                    Locale.US,
                    "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                    elapsedSec,
                    latestAcc[0], latestAcc[1], latestAcc[2],
                    latestGyro[0], latestGyro[1], latestGyro[2],
                    latestMag[0], latestMag[1], latestMag[2]
                )
                onSampleRecorded(row)
            }
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, latestGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, latestMag, 0, 3)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
