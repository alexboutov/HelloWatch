package com.natatometry.hellowatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SwimUiState(
    val statusText: String = "Idle: Waiting for GO SWIM",
    val isRecording: Boolean = false,
    val sampleCount: Int = 0
)

class SwimRecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "swim_recording_channel"
        private const val NOTIFICATION_ID = 1001

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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null

    private lateinit var sensorManager: SwimSensorManager
    private lateinit var syncClient: WearDataSyncClient

    private val csvRows = mutableListOf<String>()
    private var sessionHeader = ""
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HelloWatch Swim Sensor")
                .setContentText("Listening for GO SWIM...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        )

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
            onGoSwim = { tMax -> startSession(tMax) },
            onStopSwim = { finishSession() }
        )
        syncClient.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startSession(maxDurationSec: Double) {
        if (isRecording) return
        csvRows.clear()

        val now = Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val timeStr = SimpleDateFormat("HH-mm", Locale.US).format(now)
        sessionHeader = "$dateStr  $timeStr"

        val startMs = System.currentTimeMillis()
        isRecording = true
        MainActivity.isWaterLocked = true
        _uiState.value = SwimUiState(
            statusText = "RECORDING (max ${maxDurationSec}s)",
            isRecording = true,
            sampleCount = 0
        )

        sensorManager.start(startMs, maxDurationSec)

        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay((maxDurationSec * 1000).toLong())
            if (isRecording) {
                finishSession()
            }
        }
    }

    private fun finishSession() {
        if (!isRecording) return
        isRecording = false
        MainActivity.isWaterLocked = false
        timeoutJob?.cancel()
        sensorManager.stop()

        val totalSamples = csvRows.size
        _uiState.value = SwimUiState(
            statusText = "Sending $totalSamples rows...",
            isRecording = false,
            sampleCount = totalSamples
        )

        val fullCsv = buildString {
            appendLine(sessionHeader)
            appendLine("t,VIB,|A|,ax,ay,az,gx,gy,gz,mx,my,mz")
            for (row in csvRows) {
                appendLine(row)
            }
        }

        syncClient.sendCsvAsset(
            csvContent = fullCsv,
            rowCount = totalSamples,
            onSuccess = {
                _uiState.value = _uiState.value.copy(statusText = "Sent $totalSamples rows! (Unlocked)")
            },
            onFailure = { err ->
                _uiState.value = _uiState.value.copy(statusText = "Send Err: $err")
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Swim Sensor Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        sensorManager.stop()
        syncClient.unregister()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
