package com.natatometry.hellowatch

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneWearSyncClient(
    context: Context,
    private val scope: CoroutineScope,
    private val storage: SwimFileStorage,
    private val onCsvSaved: (fileName: String, rowCount: Int, preview: String) -> Unit,
    private val onError: (prefix: String, message: String?) -> Unit
) {
    private val dataClient = Wearable.getDataClient(context)

    private val listener = DataClient.OnDataChangedListener { dataEvents ->
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/swim_csv"
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val asset = dataMap.getAsset("csv_file")
                val rowCount = dataMap.getInt("row_count", 0)

                if (asset != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val fdResponse = Tasks.await(dataClient.getFdForAsset(asset))
                            val csvContent = fdResponse.inputStream.bufferedReader().use { it.readText() }
                            val (fileName, previewLines) = storage.saveCsv(csvContent)

                            withContext(Dispatchers.Main) {
                                onCsvSaved(fileName, rowCount, previewLines)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                onError("CSV Read Err", e.message)
                            }
                        }
                    }
                }
            }
        }
    }

    fun register() {
        dataClient.addListener(listener)
    }

    fun unregister() {
        dataClient.removeListener(listener)
    }

    fun sendSwimCommand(
        command: String,
        tMaxVal: Double,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        val putDataMapReq = PutDataMapRequest.create("/swim_command").apply {
            dataMap.putString("command", command)
            dataMap.putDouble("tmax", tMaxVal)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.message) }
    }
}
