package com.natatometry.hellowatch

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

class WearDataSyncClient(
    context: Context,
    private val onParams: (tMaxSec: Double, azimuthDeg: Double, vibrationOn: Boolean) -> Unit,
    private val onGoSwim: (Double) -> Unit
) {
    companion object {
        private const val PATH_SWIM_PARAMS = "/swim_params"
        private const val KEY_TMAX = "tmax"
        private const val KEY_AZIMUTH = "azimuth"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_TIMESTAMP = "timestamp"
    }

    private val dataClient = Wearable.getDataClient(context)

    private val listener = DataClient.OnDataChangedListener { dataEvents ->
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            when (event.dataItem.uri.path) {
                PATH_SWIM_PARAMS -> {
                    deliverParams(DataMapItem.fromDataItem(event.dataItem).dataMap)
                }
                // Temporary: phone-side GO SWIM, kept until the watch button start is confirmed.
                // STOP SWIM is ignored: nothing stops a recording before Tmax.
                "/swim_command" -> {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val cmd = dataMap.getString("command") ?: ""
                    if (cmd == "GO SWIM") {
                        onGoSwim(dataMap.getDouble("tmax", 3.0))
                    }
                }
            }
        }
    }

    private fun deliverParams(dataMap: DataMap) {
        if (!dataMap.containsKey(KEY_TMAX) || !dataMap.containsKey(KEY_AZIMUTH)) return
        // Params sent by an older phone build have no vibration key: vibration stays on.
        onParams(
            dataMap.getDouble(KEY_TMAX),
            dataMap.getDouble(KEY_AZIMUTH),
            dataMap.getBoolean(KEY_VIBRATION, true)
        )
    }

    fun register() {
        dataClient.addListener(listener)
        fetchStoredParams()
    }

    fun unregister() {
        dataClient.removeListener(listener)
    }

    /**
     * Reads the persistent /swim_params item already in the Data Layer, so the
     * parameters are available even if they were sent before this app started.
     */
    private fun fetchStoredParams() {
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .path(PATH_SWIM_PARAMS)
            .build()
        dataClient.getDataItems(uri).addOnSuccessListener { buffer ->
            try {
                var latest: DataMap? = null
                var latestTs = Long.MIN_VALUE
                for (item in buffer) {
                    if (item.uri.path != PATH_SWIM_PARAMS) continue
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val ts = dataMap.getLong(KEY_TIMESTAMP, 0L)
                    if (latest == null || ts > latestTs) {
                        latest = dataMap
                        latestTs = ts
                    }
                }
                latest?.let { deliverParams(it) }
            } finally {
                buffer.release()
            }
        }
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
