package com.natatometry.hellowatch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Registers the swim sensors and builds one CSV row per TYPE_LINEAR_ACCELERATION sample.
 *
 * The same on every watch (since M4): every sensor is asked for 100 Hz, and P is computed
 * both ways for every row, from ROTATION_VECTOR (Prv) and from GRAVITY + MAGNETIC_FIELD (Pgm).
 * Events are delivered on a dedicated thread, not the main thread, with batching off.
 * Row times (t, epochMs) come from SensorEvent.timestamp via SensorClock.
 * tArr is the time the same event reached this class, on the processor's clock.
 * The session ends on the processor's clock only, never on the sensor's timestamps.
 * Both callbacks are invoked ON THE SENSOR THREAD; the caller must be thread-safe.
 */
class SwimSensorManager(
    context: Context,
    private val onSampleRecorded: (String) -> Unit,
    private val onMaxDurationReached: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SwimSensorManager"

        /**
         * Sampling period wanted from every sensor: 10,000 us = 100 Hz. A sensor that cannot go
         * that fast is asked for its own shortest period instead (the magnetometer: 20,000 us):
         * asked for less than it can deliver, it stamps its readings wrongly.
         */
        private const val SAMPLING_PERIOD_US = 10_000
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotVecSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rawAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Vibration rule; its tunables are vibration.VibThrsh and vibration.CoolDown. */
    val vibration = SwimVibration(context)

    /** Health check of the session; read it after the session has ended. */
    val health = SwimHealth()

    private val rvProjector = AzimuthProjector(useRotationVector = true)
    private val gmProjector = AzimuthProjector(useRotationVector = false)
    private val clock = SensorClock()
    private val stateLog = WatchStateLog(context)
    private val row = SwimRow()
    private var sensorThread: HandlerThread? = null

    private var tMaxSec = 3.0
    @Volatile private var active = false

    /** azimuthDeg: lane direction for this session; null leaves both P columns empty. */
    fun start(maxSec: Double, azimuthDeg: Double? = null) {
        tMaxSec = maxSec
        vibration.reset()
        rvProjector.reset(azimuthDeg)
        gmProjector.reset(azimuthDeg)
        row.reset()
        health.reset(maxSec)
        stateLog.start()

        val thread = HandlerThread("swim_sensors", Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        sensorThread = thread
        val handler = Handler(thread.looper)

        clock.start()
        active = true

        register(gyroSensor, handler, "TYPE_GYROSCOPE")
        register(magSensor, handler, "TYPE_MAGNETIC_FIELD (Pgm stays empty)")
        register(gravitySensor, handler, "TYPE_GRAVITY (Pgm stays empty)")
        register(rotVecSensor, handler, "TYPE_ROTATION_VECTOR (Prv stays empty)")
        register(pressureSensor, handler, "TYPE_PRESSURE (press stays empty)")
        register(rawAccSensor, handler, "TYPE_ACCELEROMETER (rax, ray, raz stay empty)")
        register(linAccSensor, handler, "TYPE_LINEAR_ACCELERATION (no samples will be recorded)")
    }

    /** 100 Hz (or the sensor's own fastest rate) asked for explicitly, no batching, delivery on the sensor thread. */
    private fun register(sensor: Sensor?, handler: Handler, nameIfMissing: String) {
        if (sensor == null) {
            Log.w(TAG, "Not available: $nameIfMissing")
            return
        }
        val periodUs = maxOf(SAMPLING_PERIOD_US, sensor.minDelay)
        val accepted = sensorManager.registerListener(this, sensor, periodUs, 0, handler)
        Log.i(TAG, "${sensor.name}: asked $periodUs us, minDelay=${sensor.minDelay} us, accepted=$accepted")
    }

    /** May be called from any thread, including the sensor thread. */
    fun stop() {
        active = false
        sensorManager.unregisterListener(this)
        vibration.cancel()
        stateLog.stop()
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Read first: the moment this event reached the app (arrival clock, column tArr).
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        if (!active) return
        val arrivalSec = clock.arrivalSec(arrivalNs)
        if (arrivalSec > tMaxSec) {
            active = false
            onMaxDurationReached()
            return
        }

        val ts = event.timestamp
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> recordSample(values, ts, arrivalSec)
            Sensor.TYPE_GYROSCOPE -> row.onGyro(values)
            Sensor.TYPE_MAGNETIC_FIELD -> {
                row.onMagnetic(values, ts)
                gmProjector.onMagnetic(values)
            }
            Sensor.TYPE_GRAVITY -> {
                row.onGravity(values, ts)
                gmProjector.onGravity(values)
            }
            // A reading of all zeros is not a rotation (seen once, as the first reading of a session).
            Sensor.TYPE_ROTATION_VECTOR -> if (values.any { it != 0f }) {
                row.onRotationVector(values, ts)
                rvProjector.onRotationVector(values)
            }
            Sensor.TYPE_PRESSURE -> row.onPressure(values[0])
            Sensor.TYPE_ACCELEROMETER -> row.onRawAcceleration(values)
        }
    }

    /**
     * One CSV row (see SwimRow) for a linear-acceleration sample. Every delivered sample is written.
     * ts = sensor timestamp of the sample (ns); arrivalSec = its delivery, session time on the processor's clock.
     */
    private fun recordSample(acc: FloatArray, ts: Long, arrivalSec: Double) {
        // The sensor clock of a session is checked on the stream that gives the rows their time.
        clock.observe(ts)
        val elapsedSec = clock.elapsedSec(ts)
        val ax = acc[0]
        val ay = acc[1]
        val az = acc[2]
        val accMag = sqrt(ax * ax + ay * ay + az * az)
        val vibrating = vibration.onSample(accMag, ts)
        health.onRow(ts, elapsedSec, arrivalSec)

        val text = row.format(
            elapsedSec, vibrating, accMag, ax, ay, az,
            rvProjector.project(ax, ay, az),
            gmProjector.project(ax, ay, az),
            clock.epochMs2020(ts), ts, arrivalSec, stateLog.code
        )
        onSampleRecorded(text)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
