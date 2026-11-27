package com.natatometry.hellowatch

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches the swim CSVs waiting on the watches and saves them on the phone.
 *
 * Each recording is its own data item on the watch that made it: /swim_csv/<watchId>_<swim start>
 * (older watch builds use the single item /swim_csv). An item stays in the Data Layer until this
 * class has written the file and then deleted the item: the deletion is the phone's confirmation.
 *
 * Nothing depends on being notified at the right moment: while started, every waiting item is
 * looked up again every retryMs, so a file that arrived while the app was closed, or whose
 * transfer failed, is fetched on a later round.
 *
 * Transfer test, stage 1: every saved file gets one line in transfer_log.csv (see TransferLog).
 */
class PhoneCsvReceiver(
    private val dataClient: DataClient,
    private val scope: CoroutineScope,
    private val storage: SwimFileStorage,
    private val onCsvSaved: (fileName: String, rowCount: Int, preview: String) -> Unit,
    private val onError: (prefix: String, message: String?) -> Unit,
    private val retryMs: Long = 30_000L
) {
    companion object {
        const val PATH_CSV = "/swim_csv"
        private const val FETCH_TIMEOUT_S = 300L
        private const val SHORT_TIMEOUT_S = 30L
    }

    /** handoverMs: the watch's clock when it handed the recording to the Data Layer; 0 if not sent. */
    private class Waiting(val uri: Uri, val asset: Asset, val rowCount: Int, val handoverMs: Long)

    private val transferLog = TransferLog(storage.getLogsDirectory())

    private val lock = Any()
    private val inProgress = HashSet<Uri>() // items being fetched or deleted right now
    private val saved = HashSet<Uri>()      // file written, item not yet deleted
    private val reported = HashSet<Uri>()   // a failure of this item has been shown once
    private var retryJob: Job? = null

    /** Starts the look-up rounds. The first round runs at once. */
    fun start() {
        retryJob?.cancel()
        retryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchWaiting()
                delay(retryMs)
            }
        }
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
    }

    /** Call for every changed data item, inside the listener callback. Other paths are ignored. */
    fun onItemChanged(item: DataItem) {
        toWaiting(item)?.let { fetch(it) }
    }

    private fun isCsvPath(path: String?): Boolean =
        path == PATH_CSV || (path != null && path.startsWith("$PATH_CSV/"))

    /** Copies what is needed out of the item; the item itself is only valid inside its buffer. */
    private fun toWaiting(item: DataItem): Waiting? {
        if (!isCsvPath(item.uri.path)) return null
        val dataMap = DataMapItem.fromDataItem(item).dataMap
        val asset = dataMap.getAsset("csv_file") ?: return null
        return Waiting(item.uri, asset, dataMap.getInt("row_count", 0), dataMap.getLong("timestamp", 0L))
    }

    /** One round: every CSV item of every watch that is still in the Data Layer. IO thread. */
    private fun fetchWaiting() {
        val prefix = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_CSV).build()
        val found = ArrayList<Waiting>()
        try {
            val buffer = Tasks.await(
                dataClient.getDataItems(prefix, DataClient.FILTER_PREFIX), SHORT_TIMEOUT_S, TimeUnit.SECONDS
            )
            try {
                for (item in buffer) toWaiting(item)?.let { found.add(it) }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            return // the next round tries again
        }
        found.forEach { fetch(it) }
    }

    private fun fetch(w: Waiting) {
        synchronized(lock) { if (!inProgress.add(w.uri)) return }
        scope.launch(Dispatchers.IO) {
            try {
                if (synchronized(lock) { w.uri !in saved }) {
                    val fetchStartMs = System.currentTimeMillis()
                    val response =
                        Tasks.await(dataClient.getFdForAsset(w.asset), FETCH_TIMEOUT_S, TimeUnit.SECONDS)
                    val csvContent = response.inputStream.bufferedReader().use { it.readText() }
                    val (fileName, previewLines) = storage.saveCsv(csvContent)
                    transferLog.add(
                        fileName, TransferLog.METHOD_DATA_LAYER, w.handoverMs, fetchStartMs, System.currentTimeMillis()
                    )
                    synchronized(lock) { saved.add(w.uri) }
                    withContext(Dispatchers.Main) { onCsvSaved(fileName, w.rowCount, previewLines) }
                }
                // Confirmation: the item leaves the watch only after the file is on the phone.
                Tasks.await(dataClient.deleteDataItems(w.uri), SHORT_TIMEOUT_S, TimeUnit.SECONDS)
                synchronized(lock) { saved.remove(w.uri); reported.remove(w.uri) }
            } catch (e: CancellationException) {
                throw e // the app is closing; the item stays in the Data Layer for the next start
            } catch (e: Exception) {
                // The item is still in the Data Layer; a later round fetches it. Shown once per item.
                val first = synchronized(lock) { w.uri !in saved && reported.add(w.uri) }
                if (first) withContext(Dispatchers.Main) { onError("CSV waiting, will retry", e.message) }
            } finally {
                synchronized(lock) { inProgress.remove(w.uri) }
            }
        }
    }
}
