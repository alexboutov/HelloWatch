package com.natatometry.hellowatch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

        /** The on-screen sample counter is refreshed once per this many rows (about 5 times a second). */
        private const val UI_COUNT_EVERY_ROWS = 20

        private val _uiState = MutableStateFlow(SwimUiState())
        val uiState = _uiState.asStateFlow()

        fun startService(context: Context) {
            context.startForegroundService(Intent(context, SwimRecordingService::class.java))
        }

        /** Called by the activity on a long press of the start button. */
        fun requestStart(context: Context) {
            context.startService(Intent(context, SwimRecordingService::class.java).setAction(ACTION_START_SWIM))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null

    private lateinit var sensorManager: SwimSensorManager
    private lateinit var syncClient: WearDataSyncClient
    private lateinit var wakeLock: SwimWakeLock

    /** Written on the sensor thread, read on the main thread: always used under synchronized(csvRows). */
    private val csvRows = mutableListOf<String>()
    private var sessionHeader = ""
    @Volatile private var isRecording = false

    // Latest swim parameters received from the phone (/swim_params).
    private var paramTmaxSec: Double? = null
    private var paramAzimuthDeg: Double? = null
    private var paramVibrationOn = true

    override fun onCreate() {
        super.onCreate()
        SwimNotification.createChannel(this)
        startForeground(SwimNotification.NOTIFICATION_ID, SwimNotification.build(this))

        wakeLock = SwimWakeLock(this)

        // Both callbacks arrive on the sensor thread.
        sensorManager = SwimSensorManager(
            context = this,
            onSampleRecorded = { row -> addRow(row) },
            onMaxDurationReached = { serviceScope.launch { finishSession() } }
        )

        syncClient = WearDataSyncClient(
            context = this,
            onParams = { tMaxSec, azimuthDeg, vibOn -> storeParams(tMaxSec, azimuthDeg, vibOn) },
            // Temporary: phone-side GO SWIM, kept until the watch button start is confirmed.
            onGoSwim = { tMax -> startSession(tMax) }
        )
        syncClient.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SWIM) startFromButton()
        return START_STICKY
    }

    /** Sensor thread. */
    private fun addRow(row: String) {
        if (!isRecording) return
        val count = synchronized(csvRows) {
            csvRows.add(row)
            csvRows.size
        }
        if (count % UI_COUNT_EVERY_ROWS == 0) {
            _uiState.update { it.copy(sampleCount = count) }
        }
    }

    private fun storeParams(tMaxSec: Double, azimuthDeg: Double, vibrationOn: Boolean) {
        paramTmaxSec = tMaxSec
        paramAzimuthDeg = azimuthDeg
        paramVibrationOn = vibrationOn
        // A running session keeps the Tmax and the vibration setting it started with.
        val vibText = if (vibrationOn) "" else ", vib off"
        _uiState.update { it.copy(paramsText = "Tmax=${formatSeconds(tMaxSec)}s$vibText") }
    }

    private fun startFromButton() {
        if (isRecording) return
        val tMaxSec = paramTmaxSec
        if (tMaxSec == null || tMaxSec <= 0.0) {
            _uiState.update { it.copy(statusText = "No params: send from phone") }
            return
        }
        startSession(tMaxSec)
    }

    private fun startSession(maxDurationSec: Double) {
        if (isRecording) return
        synchronized(csvRows) { csvRows.clear() }

        val durationMs = (maxDurationSec * 1000).toLong()
        wakeLock.acquire(durationMs + WAKE_LOCK_MARGIN_MS)

        // The azimuth and vibration setting stored at the start are the ones this session is recorded with.
        val azimuthDeg = paramAzimuthDeg
        sensorManager.vibration.enabled = paramVibrationOn
        sessionHeader = SwimCsv.sessionHeader(Date(), azimuthDeg, paramVibrationOn)

        isRecording = true
        MainActivity.isWaterLocked = true
        val status = "RECORDING (max ${formatSeconds(maxDurationSec)}s)"
        _uiState.update { it.copy(statusText = status, isRecording = true, sampleCount = 0) }

        sensorManager.start(maxDurationSec, azimuthDeg)

        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(durationMs)
            if (isRecording) finishSession()
        }
    }

    /** Main thread. Reached only at Tmax (timeout job or sensor clock). Nothing else ends a session. */
    private fun finishSession() {
        if (!isRecording) return
        isRecording = false
        MainActivity.isWaterLocked = false
        timeoutJob?.cancel()
        sensorManager.stop()

        val rows = synchronized(csvRows) { csvRows.toList() }
        val totalSamples = rows.size
        _uiState.update {
            it.copy(statusText = "Sending $totalSamples rows...", isRecording = false, sampleCount = totalSamples)
        }

        syncClient.sendCsvAsset(
            csvContent = SwimCsv.build(sessionHeader, rows),
            rowCount = totalSamples,
            onSuccess = {
                _uiState.update { it.copy(statusText = "Sent $totalSamples rows! (Unlocked)") }
                releaseWakeLock()
            },
            onFailure = { err ->
                _uiState.update { it.copy(statusText = "Send Err: $err") }
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
