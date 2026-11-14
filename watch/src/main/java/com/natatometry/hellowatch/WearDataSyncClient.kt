package com.natatometry.hellowatch

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class WearDataSyncClient(
    context: Context,
    private val onGoSwim: (Double) -> Unit,
    private val onStopSwim: () -> Unit
) {
    private val dataClient = Wearable.getDataClient(context)

    private val listener = DataClient.OnDataChangedListener { dataEvents ->
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/swim_command"
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val cmd = dataMap.getString("command") ?: ""
                if (cmd == "GO SWIM") {
                    val tMax = dataMap.getDouble("tmax", 3.0)
                    onGoSwim(tMax)
                } else if (cmd == "STOP SWIM") {
                    onStopSwim()
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

    fun sendCsvAsset(
        csvContent: String,
        rowCount: Int,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val asset = Asset.createFromBytes(csvContent.toByteArray(Charsets.UTF_8))
        val putDataMapReq = PutDataMapRequest.create("/swim_csv").apply {
            dataMap.putAsset("csv_file", asset)
            dataMap.putInt("row_count", rowCount)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.message ?: "Unknown error") }
    }
}
