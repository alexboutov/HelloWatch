package com.natatometry.hellowatch

import java.util.Locale

/**
 * Health check of one recording: is the stream of rows what a 100 Hz recording should be?
 * Fed with every row on the sensor thread; read on the main thread when the session has ended.
 *
 *   rows   rows written to the file
 *   exp    rows expected at the nominal rate: Tmax x 100
 *   half   whole seconds with at least one row but fewer than 75: the half rate
 *   holes  whole seconds with no row at all
 *   jumps  steps of the sensor timestamp larger than 100 ms, or not forward
 *   clk    spread (largest minus smallest) of tArr - t after the first 2 s, in seconds:
 *          how far the sensor's clock and the processor's clock moved against each other
 *
 * Seconds are counted on the processor's clock (tArr). The first second (start-up) and
 * an incomplete last second are left out of half and holes.
 */
class SwimHealth {

    companion object {
        private const val NOMINAL_HZ = 100
        private const val HALF_BELOW = 75
        private const val JUMP_NS = 100_000_000L
        private const val SETTLE_SEC = 2.0
    }

    private var tMaxSec = 0.0
    private var rowsPerSecond = IntArray(0)
    private var fedRows = 0
    private var lastSensorNs = 0L
    private var jumps = 0
    private var hasLag = false
    private var minLagSec = 0.0
    private var maxLagSec = 0.0

    /** Call at the start of every session. */
    @Synchronized
    fun reset(maxSec: Double) {
        tMaxSec = maxSec
        rowsPerSecond = IntArray(wholeSeconds() + 1)
        fedRows = 0
        lastSensorNs = 0L
        jumps = 0
        hasLag = false
    }

    /**
     * Call once per row.
     * sensorNs = sensor timestamp of the sample; sensorSec = the same as session time (column t);
     * arrivalSec = arrival of the sample on the processor's clock, session time (column tArr).
     */
    @Synchronized
    fun onRow(sensorNs: Long, sensorSec: Double, arrivalSec: Double) {
        val second = arrivalSec.toInt()
        if (arrivalSec >= 0.0 && second < rowsPerSecond.size) rowsPerSecond[second]++

        if (fedRows > 0) {
            val stepNs = sensorNs - lastSensorNs
            if (stepNs > JUMP_NS || stepNs <= 0L) jumps++
        }
        lastSensorNs = sensorNs
        fedRows++

        if (arrivalSec >= SETTLE_SEC) {
            val lagSec = arrivalSec - sensorSec
            if (!hasLag || lagSec < minLagSec) minLagSec = lagSec
            if (!hasLag || lagSec > maxLagSec) maxLagSec = lagSec
            hasLag = true
        }
    }

    private fun wholeSeconds(): Int = if (tMaxSec.isFinite() && tMaxSec > 0.0) tMaxSec.toInt() else 0

    private fun expectedRows(): Long = Math.round(tMaxSec * NOMINAL_HZ)

    /** Whole seconds 1 .. (last whole second) whose row count satisfies [test]. */
    private fun countSeconds(test: (Int) -> Boolean): Int {
        var n = 0
        for (second in 1 until wholeSeconds()) if (test(rowsPerSecond[second])) n++
        return n
    }

    private fun halfSeconds(): Int = countSeconds { it in 1 until HALF_BELOW }

    private fun holeSeconds(): Int = countSeconds { it == 0 }

    private fun clkText(): String =
        if (hasLag) String.format(Locale.US, "%.2f", maxLagSec - minLagSec) else ""

    /** Fields for line 1 of the CSV. rowsWritten = number of rows in the file. */
    @Synchronized
    fun headerFields(rowsWritten: Int): String {
        return "rows=$rowsWritten,exp=${expectedRows()},half=${halfSeconds()},holes=${holeSeconds()}," +
            "jumps=$jumps,clk=${clkText()}"
    }

    /** Three short lines for the watch screen. rowsWritten = number of rows in the file. */
    @Synchronized
    fun screenText(rowsWritten: Int): String {
        val expected = expectedRows()
        val percent = if (expected > 0) Math.round(rowsWritten * 100.0 / expected) else 0L
        return "$rowsWritten rows ($percent%)\n" +
            "half ${halfSeconds()}s holes ${holeSeconds()}s\n" +
            "jumps $jumps clk ${if (hasLag) clkText() + "s" else "-"}"
    }
}
