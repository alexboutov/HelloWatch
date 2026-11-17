package com.natatometry.hellowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the swim CSV sent to the phone. */
object SwimCsv {
    private const val COLUMNS = "t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz"

    /** Line 1 of the CSV: "<date>  <HH-mm>" of the swim start. */
    fun sessionHeader(start: Date): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)
        val timeStr = SimpleDateFormat("HH-mm", Locale.US).format(start)
        return "$dateStr  $timeStr"
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
