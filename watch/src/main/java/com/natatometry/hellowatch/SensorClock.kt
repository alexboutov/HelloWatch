package com.natatometry.hellowatch

import android.os.SystemClock
import android.util.Log

/**
 * Converts SensorEvent.timestamp (nanoseconds on the sensor clock) into session time
 * and epochMs, so that row times are sample times and not delivery times.
 *
 * start() reads the wall clock and the elapsed-realtime clock together, once per session.
 * Sensor timestamps are expected to be on the elapsed-realtime clock. The first event is
 * checked: if its timestamp is not near "now" on that clock, the sensor uses another base,
 * and the session is anchored on that first event instead (delivery delay of that one
 * event then becomes a constant offset for the session).
 */
class SensorClock {

    companion object {
        private const val TAG = "SensorClock"
        /** A fresh event may be at most this much older than "now" on the expected clock. */
        private const val MAX_LAG_NS = 2_000_000_000L
        /** ... or this much newer (clock read jitter). */
        private const val MAX_LEAD_NS = 50_000_000L
    }

    private var wallStartMs = 0L   // System.currentTimeMillis() at session start
    private var realStartNs = 0L   // SystemClock.elapsedRealtimeNanos() at session start
    private var sensorStartNs = 0L // sensor-clock value at session start
    private var checked = false

    /** True if the sensor clock was not the elapsed-realtime clock and the fallback is in use. */
    var usesFallback = false
        private set

    /** Call at the start of every session. */
    fun start() {
        wallStartMs = System.currentTimeMillis()
        realStartNs = SystemClock.elapsedRealtimeNanos()
        sensorStartNs = realStartNs
        checked = false
        usesFallback = false
    }

    /** Call with every event's timestamp before the conversions below. Only the first call does work. */
    fun observe(eventTimestampNs: Long) {
        if (checked) return
        checked = true
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val lagNs = nowNs - eventTimestampNs
        if (lagNs > MAX_LAG_NS || lagNs < -MAX_LEAD_NS) {
            sensorStartNs = eventTimestampNs - (nowNs - realStartNs)
            usesFallback = true
            Log.w(TAG, "Sensor clock is not elapsedRealtime (lag ${lagNs / 1_000_000} ms); anchored on first event")
        }
    }

    /** Seconds from session start to the sample. */
    fun elapsedSec(eventTimestampNs: Long): Double {
        return (eventTimestampNs - sensorStartNs) / 1_000_000_000.0
    }

    /** Sample time in milliseconds since 2020-01-01 00:00:00 UTC. */
    fun epochMs2020(eventTimestampNs: Long): Long {
        return wallStartMs + (eventTimestampNs - sensorStartNs) / 1_000_000L - SwimCsv.EPOCH_2020_MS
    }

    /** "Now" expressed on the sensor clock, for comparing with event timestamps. */
    fun nowSensorNs(): Long {
        return sensorStartNs + (SystemClock.elapsedRealtimeNanos() - realStartNs)
    }
}
