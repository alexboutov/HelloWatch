package com.natatometry.hellowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the swim CSV sent to the phone. */
object SwimCsv {
    private const val COLUMNS = "t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz,P,epochMs"

    /**
     * 2020-01-01 00:00:00 UTC in milliseconds since 1970. The epochMs column holds
     * milliseconds since this instant: epochMs = System.currentTimeMillis() - EPOCH_2020_MS.
     */
    const val EPOCH_2020_MS = 1_577_836_800_000L

    /**
     * Line 1 of the CSV: "<date>  <HH:mm:ss>,<watchId>,<method>,<azimuth>".
     * watchId and method come from the build variant (watchW / watchH).
     * azimuth is in degrees; the field is empty if no azimuth was received.
     */
    fun sessionHeader(start: Date, azimuthDeg: Double? = null): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(start)
        val azimuthStr = azimuthDeg?.toString() ?: ""
        return "$dateStr  $timeStr,${BuildConfig.WATCH_ID},${BuildConfig.PROJECTION_METHOD},$azimuthStr"
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
