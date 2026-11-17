package com.natatometry.hellowatch

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwimFileStorage(private val context: Context) {

    fun getLogsDirectory(): File {
        val dir = File(context.getExternalFilesDir(null), "swim_logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Watch ID from line 1 of the CSV: "<date>  <HH-mm>,<watchId>,<method>,<azimuth>".
     * Returns "X" if the line has no usable watch ID (CSV from an older watch build).
     */
    private fun watchIdOf(csvContent: String): String {
        val firstLine = csvContent.lineSequence().firstOrNull() ?: return "X"
        val id = firstLine.split(",").getOrNull(1)?.trim().orEmpty()
        return if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) id else "X"
    }

    /** Saves the CSV unchanged as swim_<watchId>_<yyyy-MM-dd_HH-mm-ss>.csv. */
    fun saveCsv(csvContent: String): Pair<String, String> {
        val dir = getLogsDirectory()
        val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outFile = File(dir, "swim_${watchIdOf(csvContent)}_$fileStamp.csv")
        outFile.writeText(csvContent)

        val previewLines = csvContent.lines().take(6).joinToString("\n")
        return Pair(outFile.name, previewLines)
    }
}
