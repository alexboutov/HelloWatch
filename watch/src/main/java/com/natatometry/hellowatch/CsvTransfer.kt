package com.natatometry.hellowatch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Transfer test, stage 3: hands a finished recording to the phone by one of the two methods.
 *
 * Data Layer (useSocket = false): exactly as before, through WearDataSyncClient.sendCsvAsset.
 * The phone confirms by deleting the recording's data item once it has saved the file.
 *
 * Socket (useSocket = true): the recording is first written to the watch's own storage, as
 * pending/<watchId>_<handoverMs>.csv, and is deleted only when the phone has confirmed it.
 * It is sent on a worker thread, under a wake lock of its own. A failed attempt is repeated
 * every RETRY_MS, up to MAX_ATTEMPTS; after that the file stays on the watch and goes out
 * after the next recording that gets through, or when the app is started again.
 * Nothing is sent while a recording is in progress. There is no automatic switch to the
 * Data Layer: in the test each method has to show its own result.
 *
 * [isRecording] tells whether a recording is in progress right now.
 */
class CsvTransfer(
    context: Context,
    private val syncClient: WearDataSyncClient,
    private val isRecording: () -> Boolean
) {
    companion object {
        private const val PENDING_DIR = "pending"
        private const val RETRY_MS = 15_000L
        private const val MAX_ATTEMPTS = 12
        private const val WAKE_LOCK_MS = 20 * 60_000L
    }

    private val pendingDir = File(context.filesDir, PENDING_DIR)
    private val sender = CsvSocketSender()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelloWatch:CsvTransfer")
            .apply { setReferenceCounted(false) }

    /** Data Layer recordings waiting for the phone's confirmation, by item path. Main thread only. */
    private val awaitingPhone = HashMap<String, () -> Unit>()

    init {
        syncClient.onCsvDeleted = { path -> awaitingPhone.remove(path)?.invoke() }
    }

    /**
     * Sends one recording. [onStatus] gets each new text for the watch screen, on the main thread:
     * Data Layer: "Sent, waiting for phone" when stored, then "Copied to phone" when the phone confirms;
     * Socket: "Copied to phone" when the phone has answered OK; either: "Send Err: <reason>".
     * [summary] is added to each text on the lines below it.
     */
    fun send(csvContent: String, rowCount: Int, useSocket: Boolean, summary: String, onStatus: (String) -> Unit) {
        val onSuccess = { onStatus("Copied to phone\n$summary") }
        val onFailure = { err: String -> onStatus("Send Err: $err") }
        if (!useSocket) {
            val path = syncClient.csvItemPath(csvContent)
            awaitingPhone[path] = onSuccess
            syncClient.sendCsvAsset(csvContent, rowCount, onSuccess = {
                // Skipped if the phone has confirmed already.
                if (path in awaitingPhone) onStatus("Sent, waiting for phone\n$summary")
            }, onFailure = { err ->
                awaitingPhone.remove(path)
                onFailure(err)
            })
            return
        }
        val handoverMs = System.currentTimeMillis()
        worker.execute {
            underWakeLock {
                val error = try {
                    pendingDir.mkdirs()
                    val file = File(pendingDir, "${BuildConfig.WATCH_ID}_$handoverMs.csv")
                    file.writeBytes(csvContent.toByteArray(Charsets.UTF_8))
                    sendWithRetries(file)
                } catch (e: Exception) {
                    "file not written: ${e.message}"
                }
                mainHandler.post { if (error == null) onSuccess() else onFailure("socket: $error") }
                if (error == null) sendOlderFiles()
            }
        }
    }

    /** Sends the recordings left over from earlier failures. Call when the app starts. */
    fun resendPending() {
        worker.execute { underWakeLock { sendOlderFiles() } }
    }

    fun close() {
        worker.shutdownNow()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun underWakeLock(block: () -> Unit) {
        wakeLock.acquire(WAKE_LOCK_MS)
        try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** Null when the phone has confirmed the file (the file is then deleted), else the last error. */
    private fun sendWithRetries(file: File): String? {
        var error: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                try {
                    Thread.sleep(RETRY_MS)
                } catch (e: InterruptedException) {
                    return "stopped"
                }
            }
            if (isRecording()) return "kept on the watch (a recording is in progress)"
            error = sender.send(file, handoverOf(file))
            if (error == null) {
                file.delete()
                return null
            }
        }
        return "$error; kept on the watch"
    }

    /** One attempt per file, oldest first; stops at the first failure. */
    private fun sendOlderFiles() {
        val files = pendingDir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.sortedBy { it.name }
            ?: return
        for (file in files) {
            if (isRecording()) return
            if (sender.send(file, handoverOf(file)) != null) return
            file.delete()
        }
    }

    /** Hand-over time kept in the file name; the file's own time if the name has another form. */
    private fun handoverOf(file: File): Long =
        file.name.removeSuffix(".csv").substringAfterLast('_').toLongOrNull() ?: file.lastModified()
}
