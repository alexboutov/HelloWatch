package com.natatometry.hellowatch

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope

class PhoneWearSyncClient(
    context: Context,
    scope: CoroutineScope,
    storage: SwimFileStorage,
    onCsvSaved: (fileName: String, rowCount: Int, preview: String) -> Unit,
    onError: (prefix: String, message: String?) -> Unit
) {
    companion object {
        /** Persistent swim parameters, synced to every connected watch. */
        const val PATH_SWIM_PARAMS = "/swim_params"
        const val KEY_TMAX = "tmax"
        const val KEY_AZIMUTH = "azimuth"
        const val KEY_VIBRATION = "vibration"
        const val KEY_TIMESTAMP = "timestamp"
    }

    private val dataClient = Wearable.getDataClient(context)

    /** Saves the CSVs waiting on the watches and confirms each one by deleting its data item. */
    private val csvReceiver = PhoneCsvReceiver(dataClient, scope, storage, onCsvSaved, onError)

    private val listener = DataClient.OnDataChangedListener { dataEvents ->
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) csvReceiver.onItemChanged(event.dataItem)
        }
    }

    fun register() {
        dataClient.addListener(listener)
        csvReceiver.start()
    }

    fun unregister() {
        dataClient.removeListener(listener)
        csvReceiver.stop()
    }

    /**
     * Stores the swim parameters as a persistent DataItem. The item stays in the
     * Data Layer and is delivered to each watch, including one that connects later.
     * The swim itself is started on the watch.
     */
    fun sendSwimParams(
        tMaxVal: Double,
        azimuthDeg: Double,
        vibrationOn: Boolean = true,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        val putDataMapReq = PutDataMapRequest.create(PATH_SWIM_PARAMS).apply {
            dataMap.putDouble(KEY_TMAX, tMaxVal)
            dataMap.putDouble(KEY_AZIMUTH, azimuthDeg)
            dataMap.putBoolean(KEY_VIBRATION, vibrationOn)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.message) }
    }

    /**
     * Temporary: phone-side GO SWIM / STOP SWIM command, unchanged from the previous
     * version. Kept until the watch button start is confirmed, then removed.
     */
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
