package com.natatometry.hellowatch

import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Projection of a device-frame acceleration onto the Azimuth (swimming-lane direction).
 *
 * Azimuth: degrees, magnetic, clockwise from north.
 * R (device -> world East/North/Up) comes from GRAVITY + MAGNETIC_FIELD.
 *   E = R[0]*ax + R[1]*ay + R[2]*az
 *   N = R[3]*ax + R[4]*ay + R[5]*az
 *   P = E*sin(azimuth) + N*cos(azimuth)
 *
 * project() returns null whenever P cannot be computed (no azimuth, no gravity or
 * magnetic sample yet, or getRotationMatrix failed). It never returns a false 0.
 */
class AzimuthProjector {

    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val rotation = FloatArray(9)

    private var hasGravity = false
    private var hasMagnetic = false
    private var hasAzimuth = false
    private var sinAz = 0f
    private var cosAz = 0f

    /** Call at the start of every session. A null azimuth leaves P empty for the session. */
    fun reset(azimuthDeg: Double?) {
        hasGravity = false
        hasMagnetic = false
        hasAzimuth = azimuthDeg != null
        if (azimuthDeg != null) {
            val rad = Math.toRadians(azimuthDeg)
            sinAz = sin(rad).toFloat()
            cosAz = cos(rad).toFloat()
        }
    }

    /** Latest TYPE_GRAVITY values. */
    fun onGravity(values: FloatArray) {
        System.arraycopy(values, 0, gravity, 0, 3)
        hasGravity = true
    }

    /** Latest TYPE_MAGNETIC_FIELD values. */
    fun onMagnetic(values: FloatArray) {
        System.arraycopy(values, 0, magnetic, 0, 3)
        hasMagnetic = true
    }

    /** P in m/s^2 for the device-frame acceleration (ax, ay, az), or null if R is not valid. */
    fun project(ax: Float, ay: Float, az: Float): Float? {
        if (!hasAzimuth || !hasGravity || !hasMagnetic) return null
        if (!SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) return null

        val east = rotation[0] * ax + rotation[1] * ay + rotation[2] * az
        val north = rotation[3] * ax + rotation[4] * ay + rotation[5] * az
        return east * sinAz + north * cosAz
    }
}
