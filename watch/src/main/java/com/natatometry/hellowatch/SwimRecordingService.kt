package com.natatometry.hellowatch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

data class SwimUiState(
    val statusText: String = "Idle: hold MAP to start",
    val paramsText: String = "Params: none (send from phone)",
    val isRecording: Boolean = false,
    val sampleCount: Int = 0
)

class SwimRecordingService : Service() {

    companion object {
        private const val ACTION_START_SWIM = "com.natatometry.hellowatch.action.START_SWIM"

        /** Extra time the wake lock may be held after Tmax, for sending the CSV. */
        private const val WAKE_LOCK_MARGIN_MS = 30_000L

        private val _uiState = MutableStateFlow(SwimUiState())
        val uiState = _uiState.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, SwimRecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Called by the activity on a long press of the start button. */
        fun requestStart(context: Context) {
            val intent = Intent(context, SwimRecordingService::class.java)
                .setAction(ACTION_START_SWIM)
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null

    private lateinit var sensorManager: SwimSensorManager
    private lateinit var syncClient: WearDataSyncClient
    private lateinit var wakeLock: SwimWakeLock

    private val csvRows = mutableListOf<String>()
    private var sessionHeader = ""
    private var isRecording = false

    // Latest swim parameters received from the phone (/swim_params).
    private var paramTmaxSec: Double? = null
    private var paramAzimuthDeg: Double? = null

    override fun onCreate() {
        super.onCreate()
        SwimNotification.createChannel(this)
        startForeground(SwimNotification.NOTIFICATION_ID, SwimNotification.build(this))

        wakeLock = SwimWakeLock(this)

        sensorManager = SwimSensorManager(
            context = this,
            onSampleRecorded = { row ->
                if (isRecording) {
                    csvRows.add(row)
                    _uiState.value = _uiState.value.copy(sampleCount = csvRows.size)
                }
            },
            onMaxDurationReached = { finishSession() }
        )

        syncClient = WearDataSyncClient(
            context = this,
            onParams = { tMaxSec, azimuthDeg -> storeParams(tMaxSec, azimuthDeg) },
            // Temporary: phone-side GO SWIM, kept until the watch button start is confirmed.
            onGoSwim = { tMax -> startSession(tMax) }
        )
        syncClient.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SWIM) {
            startFromButton()
        }
        return START_STICKY
    }

    private fun storeParams(tMaxSec: Double, azimuthDeg: Double) {
        paramTmaxSec = tMaxSec
        paramAzimuthDeg = azimuthDeg
        // A running session keeps the Tmax it started with.
        _uiState.value = _uiState.value.copy(
            paramsText = "Tmax=${formatSeconds(tMaxSec)}s"
        )
    }

    private fun startFromButton() {
        if (isRecording) return
        val tMaxSec = paramTmaxSec
        if (tMaxSec == null || tMaxSec <= 0.0) {
            _uiState.value = _uiState.value.copy(statusText = "No params: send from phone")
            return
        }
        startSession(tMaxSec)
    }

    private fun startSession(maxDurationSec: Double) {
        if (isRecording) return
        csvRows.clear()

        val durationMs = (maxDurationSec * 1000).toLong()
        wakeLock.acquire(durationMs + WAKE_LOCK_MARGIN_MS)

        // The azimuth stored at the start is the one this session is recorded with.
        val azimuthDeg = paramAzimuthDeg
        sessionHeader = SwimCsv.sessionHeader(Date(), azimuthDeg)

        val startMs = System.currentTimeMillis()
        isRecording = true
        MainActivity.isWaterLocked = true
        _uiState.value = _uiState.value.copy(
            statusText = "RECORDING (max ${formatSeconds(maxDurationSec)}s)",
            isRecording = true,
            sampleCount = 0
        )

        sensorManager.start(startMs, maxDurationSec, azimuthDeg)

        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(durationMs)
            if (isRecording) {
                finishSession()
            }
        }
    }

    /** Reached only at Tmax (timeout job or sensor callback). Nothing else ends a session. */
    private fun finishSession() {
        if (!isRecording) return
        isRecording = false
        MainActivity.isWaterLocked = false
        timeoutJob?.cancel()
        sensorManager.stop()

        val totalSamples = csvRows.size
        _uiState.value = _uiState.value.copy(
            statusText = "Sending $totalSamples rows...",
            isRecording = false,
            sampleCount = totalSamples
        )

        syncClient.sendCsvAsset(
            csvContent = SwimCsv.build(sessionHeader, csvRows),
            rowCount = totalSamples,
            onSuccess = {
                _uiState.value = _uiState.value.copy(statusText = "Sent $totalSamples rows! (Unlocked)")
                releaseWakeLock()
            },
            onFailure = { err ->
                _uiState.value = _uiState.value.copy(statusText = "Send Err: $err")
                releaseWakeLock()
            }
        )
    }

    private fun releaseWakeLock() {
        // A new session may already have started and re-acquired the lock.
        if (!isRecording) wakeLock.release()
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        sensorManager.stop()
        syncClient.unregister()
        serviceScope.cancel()
        wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
