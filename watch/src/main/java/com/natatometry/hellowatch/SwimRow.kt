package com.natatometry.hellowatch

import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The latest value of every sensor, and the CSV row built from them for one
 * TYPE_LINEAR_ACCELERATION sample. Used on the sensor thread only.
 *
 * Columns, in the order of SwimCsv.COLUMNS:
 *   t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,Prv,epochMs,dtRrv,tArr,
 *   Pgm,dtRgm,qx,qy,qz,qw,gvx,gvy,gvz,st,press,rax,ray,raz
 * The first 16 are formatted exactly as before M4. The fields of a sensor that has not
 * delivered a sample yet in this session are empty (gx..gz and mx..mz read 0.00, as before).
 */
class SwimRow {

    private val gyro = FloatArray(3)
    private val mag = FloatArray(3)
    private val gravity = FloatArray(3)
    private val rotVec = FloatArray(4) // qx, qy, qz, qw
    private val rawAcc = FloatArray(3)
    private var pressure = 0f

    private var hasGravity = false
    private var hasRotVec = false
    private var hasPressure = false
    private var hasRawAcc = false

    // Sensor timestamps (ns) of the latest orientation inputs; 0 = none yet this session.
    private var gravityNs = 0L
    private var magNs = 0L
    private var rotVecNs = 0L

    /** Call at the start of every session. */
    fun reset() {
        gyro.fill(0f)
        mag.fill(0f)
        hasGravity = false
        hasRotVec = false
        hasPressure = false
        hasRawAcc = false
        gravityNs = 0L
        magNs = 0L
        rotVecNs = 0L
    }

    fun onGyro(values: FloatArray) {
        System.arraycopy(values, 0, gyro, 0, 3)
    }

    fun onMagnetic(values: FloatArray, sensorNs: Long) {
        System.arraycopy(values, 0, mag, 0, 3)
        magNs = sensorNs
    }

    fun onGravity(values: FloatArray, sensorNs: Long) {
        System.arraycopy(values, 0, gravity, 0, 3)
        gravityNs = sensorNs
        hasGravity = true
    }

    /** The sensor reports 3, 4 or 5 values; qw is computed when only three are given. */
    fun onRotationVector(values: FloatArray, sensorNs: Long) {
        if (values.size < 3) return
        System.arraycopy(values, 0, rotVec, 0, 3)
        rotVec[3] = if (values.size >= 4) {
            values[3]
        } else {
            val rest = 1f - values[0] * values[0] - values[1] * values[1] - values[2] * values[2]
            if (rest > 0f) sqrt(rest) else 0f
        }
        rotVecNs = sensorNs
        hasRotVec = true
    }

    fun onPressure(hPa: Float) {
        pressure = hPa
        hasPressure = true
    }

    fun onRawAcceleration(values: FloatArray) {
        System.arraycopy(values, 0, rawAcc, 0, 3)
        hasRawAcc = true
    }

    /**
     * One CSV row for a linear-acceleration sample.
     * sampleNs = sensor timestamp of the sample; elapsedSec and epochMs are that time converted.
     * pRv / pGm = P by ROTATION_VECTOR / by GRAVITY+MAG; null leaves that P and its dtR empty.
     * dtR (ms) = sample time minus the time of the orientation input used for that P
     * (for GRAVITY+MAG: the older of the gravity and magnetic samples).
     */
    fun format(
        elapsedSec: Double, vibrating: Boolean, accMag: Float, ax: Float, ay: Float, az: Float,
        pRv: Float?, pGm: Float?, epochMs: Long, sampleNs: Long, arrivalSec: Double, state: String
    ): String {
        val pRvText = if (pRv != null) String.format(Locale.US, "%.2f", pRv) else ""
        val dtRvText =
            if (pRv != null) String.format(Locale.US, "%.1f", (sampleNs - rotVecNs) / 1_000_000.0) else ""
        val head = String.format(
            Locale.US,
            "%.3f,%s,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%s,%.4f",
            elapsedSec,
            if (vibrating) "[v]" else "",
            accMag,
            ax, ay, az,
            gyro[0], gyro[1], gyro[2],
            mag[0], mag[1], mag[2],
            pRvText,
            epochMs,
            dtRvText,
            arrivalSec
        )

        // The 14 columns added in M4 are written without String.format: at 100 rows a second
        // on the watch its cost counts.
        val row = StringBuilder(head.length + 110).append(head).append(',')
        if (pGm != null) row.fixed(pGm.toDouble(), 2)
        row.append(',')
        if (pGm != null) row.fixed((sampleNs - min(gravityNs, magNs)) / 1_000_000.0, 1)
        row.append(',')
        row.triple(hasRotVec, rotVec, 5).append(',')
        if (hasRotVec) row.fixed(rotVec[3].toDouble(), 5)
        row.append(',')
        row.triple(hasGravity, gravity, 3).append(',')
        row.append(state).append(',')
        if (hasPressure) row.fixed(pressure.toDouble(), 2)
        row.append(',')
        row.triple(hasRawAcc, rawAcc, 2)
        return row.toString()
    }

    /** Three comma-separated values, or the two commas alone when there is no value yet. */
    private fun StringBuilder.triple(present: Boolean, v: FloatArray, decimals: Int): StringBuilder {
        if (present) fixed(v[0].toDouble(), decimals)
        append(',')
        if (present) fixed(v[1].toDouble(), decimals)
        append(',')
        if (present) fixed(v[2].toDouble(), decimals)
        return this
    }

    /** Appends value rounded to the given number of decimals, e.g. -0.12345 or 1013.25. */
    private fun StringBuilder.fixed(value: Double, decimals: Int): StringBuilder {
        if (value.isNaN() || value.isInfinite()) return append(value)
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        // Rounded half away from zero, like "%.2f"; a value that rounds to zero has no sign.
        val magnitude = Math.round((if (value < 0) -value else value) * scale)
        if (value < 0 && magnitude != 0L) append('-')
        append(magnitude / scale)
        if (decimals > 0) {
            append('.')
            val fraction = (magnitude % scale).toString()
            for (i in fraction.length until decimals) append('0')
            append(fraction)
        }
        return this
    }
}
