package com.natatometry.hellowatch

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transfer test (project doc claude/HelloWatch_transfer_test_plan.md, stage 1).
 * One line per recording saved on the phone, appended to transfer_log.csv in the logs folder:
 *
 * savedAt   phone time when the file was saved, yyyy-MM-dd HH:mm:ss
 * file      name of the saved file
 * watch     watch ID taken from the file name (swim_<watchId>_...)
 * method    DataLayer or Socket
 * bytes     size of the saved file
 * totalSec  from the watch handing the recording over (watch clock) to the file being saved
 *           (phone clock); empty if the watch sent no hand-over time. The two clocks can
 *           differ by about a second.
 * fetchSec  from the phone starting to fetch the recording to the file being saved (phone clock)
 * KBps      bytes / 1024 / totalSec; empty if totalSec is empty or not above zero
 *
 * The name does not start with "swim", so the file list of the app does not show it.
 */
class TransferLog(private val dir: File) {

    companion object {
        const val FILE_NAME = "transfer_log.csv"
        const val METHOD_DATA_LAYER = "DataLayer"
        const val METHOD_SOCKET = "Socket"
        const val HEADER = "savedAt,file,watch,method,bytes,totalSec,fetchSec,KBps"

        /** Watch ID from "swim_<watchId>_<date>_<time>.csv"; "X" if the name has another form. */
        fun watchIdOf(fileName: String): String {
            val parts = fileName.split("_")
            return if (parts.size >= 3 && parts[0] == "swim" && parts[1].isNotEmpty()) parts[1] else "X"
        }

        private fun sec(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

        /** One line of the log, without the line end. handoverMs <= 0 means "not sent by the watch". */
        fun line(
            fileName: String,
            method: String,
            bytes: Long,
            handoverMs: Long,
            fetchStartMs: Long,
            savedMs: Long
        ): String {
            val savedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(savedMs))
            val totalMs = if (handoverMs > 0L) savedMs - handoverMs else null
            val total = totalMs?.let { sec(it) } ?: ""
            val kbps = if (totalMs != null && totalMs > 0L) {
                String.format(Locale.US, "%.1f", bytes / 1024.0 / (totalMs / 1000.0))
            } else {
                ""
            }
            val fetch = sec(savedMs - fetchStartMs)
            return "$savedAt,$fileName,${watchIdOf(fileName)},$method,$bytes,$total,$fetch,$kbps"
        }
    }

    /**
     * Appends one line; writes the column header first if the log does not exist yet.
     * A failure to write the log is swallowed: it must never disturb the saving of a recording.
     */
    @Synchronized
    fun add(fileName: String, method: String, handoverMs: Long, fetchStartMs: Long, savedMs: Long) {
        try {
            val log = File(dir, FILE_NAME)
            val bytes = File(dir, fileName).length()
            if (!log.exists()) log.writeText(HEADER + "\n")
            log.appendText(line(fileName, method, bytes, handoverMs, fetchStartMs, savedMs) + "\n")
        } catch (e: Exception) {
            // the recording is saved; only its log line is missing
        }
    }
}
