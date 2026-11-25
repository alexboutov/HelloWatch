package com.natatometry.hellowatch

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwimFileStorage(private val context: Context) {

    companion object {
        /** Start of CSV line 1: "<yyyy-MM-dd>  <HH:mm:ss>", the swim's start time on the watch. */
        private val HEADER_TIME = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")
    }

    fun getLogsDirectory(): File {
        val dir = File(context.getExternalFilesDir(null), "swim_logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Line 1 of the CSV: "<date>  <HH:mm:ss>,<watchId>,<method>,<azimuth>,vib=<on|off>". */
    private fun firstLine(csvContent: String): String = csvContent.lineSequence().firstOrNull() ?: ""

    /** Watch ID from line 1; "X" if the line has no usable watch ID (CSV from an older watch build). */
    private fun watchIdOf(csvContent: String): String {
        val id = firstLine(csvContent).split(",").getOrNull(1)?.trim().orEmpty()
        return if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) id else "X"
    }

    /**
     * File-name stamp "yyyy-MM-dd_HH-mm-ss" taken from the swim's start time in line 1.
     * Null if line 1 does not start with a date and time (CSV from an older watch build).
     */
    private fun swimStartStamp(csvContent: String): String? {
        val m = HEADER_TIME.find(firstLine(csvContent)) ?: return null
        val (date, hh, mm, ss) = m.destructured
        return "${date}_$hh-$mm-$ss"
    }

    /**
     * Saves the CSV unchanged as swim_<watchId>_<swim start time>.csv.
     * The time in the name is when the swim started on the watch, not when the phone saved
     * the file. If line 1 carries no start time, the phone's current time is used instead.
     */
    fun saveCsv(csvContent: String): Pair<String, String> {
        val dir = getLogsDirectory()
        val fileStamp = swimStartStamp(csvContent)
            ?: SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outFile = File(dir, "swim_${watchIdOf(csvContent)}_$fileStamp.csv")
        outFile.writeText(csvContent)

        val previewLines = csvContent.lines().take(6).joinToString("\n")
        return Pair(outFile.name, previewLines)
    }

    /** True for the name of a recording in the logs folder: swim*.csv. */
    private fun isSwimCsv(name: String): Boolean = name.startsWith("swim") && name.endsWith(".csv")

    /** Names of the swim*.csv files in the logs folder, in no particular order. */
    fun listCsvNames(): List<String> =
        getLogsDirectory().listFiles()?.filter { it.isFile && isSwimCsv(it.name) }?.map { it.name }
            ?: emptyList()

    /**
     * Deletes the named swim*.csv files from the logs folder. This cannot be undone.
     * Returns the names that could not be deleted. A file that is already gone counts as
     * deleted; a name that is not a swim*.csv file of this folder is left alone and returned.
     */
    fun deleteCsv(names: Collection<String>): List<String> {
        val dir = getLogsDirectory()
        return names.filterNot { name ->
            val file = File(dir, name)
            isSwimCsv(name) && file.parentFile == dir && (!file.exists() || (file.isFile && file.delete()))
        }
    }
}
