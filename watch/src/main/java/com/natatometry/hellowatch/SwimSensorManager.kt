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
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Registers the swim sensors and builds one CSV row per TYPE_LINEAR_ACCELERATION sample.
 *
 * Events are delivered on a dedicated thread, not the main thread, with batching off.
 * Row times (t, epochMs) come from SensorEvent.timestamp via SensorClock.
 * tArr is the time the same event reached this class, on the processor's clock.
 * Both callbacks are invoked ON THE SENSOR THREAD; the caller must be thread-safe.
 */
class SwimSensorManager(
    context: Context,
    private val onSampleRecorded: (String) -> Unit,
    private val onMaxDurationReached: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SwimSensorManager"
        private const val METHOD_GRAVITY_MAG = "GRAVITY_MAG"
        private const val METHOD_ROTATION_VECTOR = "ROTATION_VECTOR"
    }

    /** Source of the rotation matrix for P, fixed per build: watchW = GRAVITY_MAG, watchH = ROTATION_VECTOR. */
    private val useGravityMag = BuildConfig.PROJECTION_METHOD == METHOD_GRAVITY_MAG
    private val useRotationVector = BuildConfig.PROJECTION_METHOD == METHOD_ROTATION_VECTOR

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor =
        if (useGravityMag) sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null
    private val rotVecSensor =
        if (useRotationVector) sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) else null

    /** Vibration rule; its tunables are vibration.VibThrsh and vibration.CoolDown. */
    val vibration = SwimVibration(context)

    private val projector = AzimuthProjector(useRotationVector)
    private val clock = SensorClock()
    private var sensorThread: HandlerThread? = null

    private val latestGyro = FloatArray(3)
    private val latestMag = FloatArray(3)

    // Sensor timestamps (ns) of the latest orientation inputs; 0 = none yet this session.
    private var lastGravityNs = 0L
    private var lastMagNs = 0L
    private var lastRotVecNs = 0L

    private var tMaxSec = 3.0
    @Volatile private var active = false

    /** azimuthDeg: lane direction for this session; null leaves the P column empty. */
    fun start(maxSec: Double, azimuthDeg: Double? = null) {
        tMaxSec = maxSec
        lastGravityNs = 0L
        lastMagNs = 0L
        lastRotVecNs = 0L
        vibration.reset()
        projector.reset(if (useGravityMag || useRotationVector) azimuthDeg else null)

        val thread = HandlerThread("swim_sensors", Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        sensorThread = thread
        val handler = Handler(thread.looper)

        clock.start()
        active = true

        // GRAVITY_MAG build: the two inputs of R are asked for their fastest rate, so that
        // the orientation used for P is as fresh as these sensors allow (dtR column).
        val fast = SensorManager.SENSOR_DELAY_FASTEST
        val game = SensorManager.SENSOR_DELAY_GAME
        register(gyroSensor, handler, "TYPE_GYROSCOPE")
        register(magSensor, handler, "TYPE_MAGNETIC_FIELD", if (useGravityMag) fast else game)
        if (useGravityMag) register(gravitySensor, handler, "TYPE_GRAVITY (P stays empty)", fast)
        if (useRotationVector) register(rotVecSensor, handler, "TYPE_ROTATION_VECTOR (P stays empty)")
        register(linAccSensor, handler, "TYPE_LINEAR_ACCELERATION (no samples will be recorded)")
    }

    /** No batching (max report latency 0), delivery on the sensor thread. Default rate: SENSOR_DELAY_GAME. */
    private fun register(
        sensor: Sensor?, handler: Handler, nameIfMissing: String,
        rate: Int = SensorManager.SENSOR_DELAY_GAME
    ) {
        if (sensor == null) {
            Log.w(TAG, "Not available: $nameIfMissing")
            return
        }
        Log.i(TAG, "${sensor.name}: rate=$rate, minDelay=${sensor.minDelay} us")
        sensorManager.registerListener(this, sensor, rate, 0, handler)
    }

    /** May be called from any thread, including the sensor thread. */
    fun stop() {
        active = false
        sensorManager.unregisterListener(this)
        vibration.cancel()
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Read first: the moment this event reached the app (arrival clock, column tArr).
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        if (!active) return
        val ts = event.timestamp
        clock.observe(ts)
        val elapsedSec = clock.elapsedSec(ts)
        if (elapsedSec > tMaxSec) {
            active = false
            onMaxDurationReached()
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION ->
                if (elapsedSec >= 0.0) recordSample(event.values, ts, elapsedSec, arrivalNs)
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, latestGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, latestMag, 0, 3)
                projector.onMagnetic(event.values)
                lastMagNs = ts
            }
            Sensor.TYPE_GRAVITY -> {
                projector.onGravity(event.values)
                lastGravityNs = ts
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                projector.onRotationVector(event.values)
                lastRotVecNs = ts
            }
        }
    }

    /**
     * One CSV row: t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,P,epochMs,dtR,tArr
     * ts = sensor timestamp of the sample (ns); arrivalNs = elapsedRealtimeNanos at its delivery.
     */
    private fun recordSample(acc: FloatArray, ts: Long, elapsedSec: Double, arrivalNs: Long) {
        val ax = acc[0]
        val ay = acc[1]
        val az = acc[2]
        val accMag = sqrt(ax * ax + ay * ay + az * az)
        val vibrating = vibration.onSample(accMag, ts)

        // P (m/s^2) is empty on any row where R is not valid.
        // dtR (ms) = sample time minus the time of the orientation input used for R
        // (for GRAVITY_MAG: the older of the gravity and magnetic samples). Empty when P is empty.
        val p = projector.project(ax, ay, az)
        val pText = if (p != null) String.format(Locale.US, "%.2f", p) else ""
        val orientationNs = if (useRotationVector) lastRotVecNs else min(lastGravityNs, lastMagNs)
        val dtRText =
            if (p != null) String.format(Locale.US, "%.1f", (ts - orientationNs) / 1_000_000.0) else ""

        val row = String.format(
            Locale.US,
            "%.3f,%s,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%s,%.4f",
            elapsedSec,
            if (vibrating) "[v]" else "",
            accMag,
            ax, ay, az,
            latestGyro[0], latestGyro[1], latestGyro[2],
            latestMag[0], latestMag[1], latestMag[2],
            pText,
            clock.epochMs2020(ts),
            dtRText,
            clock.arrivalSec(arrivalNs)
        )
        onSampleRecorded(row)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
