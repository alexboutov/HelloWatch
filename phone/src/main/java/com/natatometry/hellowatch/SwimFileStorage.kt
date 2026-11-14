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

    fun saveCsv(csvContent: String): Pair<String, String> {
        val dir = getLogsDirectory()
        val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outFile = File(dir, "swim_$fileStamp.csv")
        outFile.writeText(csvContent)

        val previewLines = csvContent.lines().take(6).joinToString("\n")
        return Pair(outFile.name, previewLines)
    }
}
