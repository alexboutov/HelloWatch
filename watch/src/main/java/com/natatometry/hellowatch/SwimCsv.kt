package com.natatometry.hellowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the swim CSV sent to the phone. */
object SwimCsv {
    /**
     * One row per linear-acceleration sample; the row number is the file line number minus 2.
     *
     * t       seconds from session start, from the sensor clock (3 decimals)
     * ax..az  linear acceleration; gx..gz gyroscope; mx..mz magnetic field
     * Prv     projection onto the Azimuth with R from ROTATION_VECTOR, m/s^2
     * Pgm     the same with R from GRAVITY + MAGNETIC_FIELD
     *         (a P is empty while its rotation matrix is not valid)
     * epochMs sample time, see EPOCH_2020_MS
     * dtRrv, dtRgm  ms between the sample and the orientation input used for that P
     * tArr    seconds from session start to the delivery of the sample to the app, from the
     *         processor's clock (4 decimals); compare with t, the sensor's time of the same sample
     * qx..qw  rotation vector as delivered; gvx..gvz gravity, m/s^2
     * st      state of the watch, see WatchStateLog
     * press   pressure, hPa; rax..raz accelerometer (gravity included), m/s^2
     */
    private const val COLUMNS =
        "t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,Prv,epochMs,dtRrv,tArr," +
            "Pgm,dtRgm,qx,qy,qz,qw,gvx,gvy,gvz,st,press,rax,ray,raz"

    /** Method field of line 1: every watch computes P both ways (Prv and Pgm). */
    private const val METHOD_BOTH = "BOTH"

    /**
     * 2020-01-01 00:00:00 UTC in milliseconds since 1970. The epochMs column holds
     * milliseconds since this instant, for the moment the sample was taken.
     */
    const val EPOCH_2020_MS = 1_577_836_800_000L

    /**
     * Start of line 1 of the CSV: "<date>  <HH:mm:ss>,<watchId>,BOTH,<azimuth>,vib=<on|off>".
     * watchId comes from the build variant (watchW / watchH).
     * azimuth is in degrees; the field is empty if no azimuth was received.
     * vib tells whether the vibration feedback was switched on for this session.
     */
    fun sessionHeader(start: Date, azimuthDeg: Double? = null, vibrationOn: Boolean = true): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(start)
        val azimuthStr = azimuthDeg?.toString() ?: ""
        val vibStr = if (vibrationOn) "vib=on" else "vib=off"
        return "$dateStr  $timeStr,${BuildConfig.WATCH_ID},$METHOD_BOTH,$azimuthStr,$vibStr"
    }

    /**
     * Full CSV text: line 1, column header, then one line per row.
     * healthFields (SwimHealth.headerFields) is appended to line 1 when it is not empty.
     */
    fun build(sessionHeader: String, rows: List<String>, healthFields: String = ""): String {
        // Sized once: a 420 s recording is about 8 million characters.
        val size = sessionHeader.length + healthFields.length + COLUMNS.length + 4 + rows.sumOf { it.length + 1 }
        return buildString(size) {
            append(sessionHeader)
            if (healthFields.isNotEmpty()) append(',').append(healthFields)
            appendLine()
            appendLine(COLUMNS)
            for (row in rows) {
                appendLine(row)
            }
        }
    }
}
