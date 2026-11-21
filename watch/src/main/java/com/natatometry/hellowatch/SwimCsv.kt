package com.natatometry.hellowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the swim CSV sent to the phone. */
object SwimCsv {
    /**
     * t       seconds from session start, from the sensor clock (3 decimals)
     * P       projection onto the Azimuth, m/s^2; empty while the rotation matrix is not valid
     * epochMs sample time, see EPOCH_2020_MS
     * dtR     ms between the sample and the orientation input used for P; empty when P is empty
     * tArr    seconds from session start to the delivery of the sample to the app, from the
     *         processor's clock (4 decimals); compare with t, the sensor's time of the same sample
     */
    private const val COLUMNS = "t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,P,epochMs,dtR,tArr"

    /**
     * 2020-01-01 00:00:00 UTC in milliseconds since 1970. The epochMs column holds
     * milliseconds since this instant, for the moment the sample was taken.
     */
    const val EPOCH_2020_MS = 1_577_836_800_000L

    /**
     * Line 1 of the CSV: "<date>  <HH:mm:ss>,<watchId>,<method>,<azimuth>,vib=<on|off>".
     * watchId and method come from the build variant (watchW / watchH).
     * azimuth is in degrees; the field is empty if no azimuth was received.
     * vib tells whether the vibration feedback was switched on for this session.
     */
    fun sessionHeader(start: Date, azimuthDeg: Double? = null, vibrationOn: Boolean = true): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(start)
        val azimuthStr = azimuthDeg?.toString() ?: ""
        val vibStr = if (vibrationOn) "vib=on" else "vib=off"
        return "$dateStr  $timeStr,${BuildConfig.WATCH_ID},${BuildConfig.PROJECTION_METHOD},$azimuthStr,$vibStr"
    }

    /** Full CSV text: session header, column header, then one line per row. */
    fun build(sessionHeader: String, rows: List<String>): String {
        return buildString {
            appendLine(sessionHeader)
            appendLine(COLUMNS)
            for (row in rows) {
                appendLine(row)
            }
        }
    }
}
