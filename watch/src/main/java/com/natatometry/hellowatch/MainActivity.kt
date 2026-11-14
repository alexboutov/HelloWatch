package com.natatometry.hellowatch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.natatometry.hellowatch.theme.HelloWatchTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            HelloWatchTheme {
                Scaffold(timeText = { TimeText() }) {
                    WatchSwimRecorderScreen()
                }
            }
        }
    }
}

@Composable
fun WatchSwimRecorderScreen() {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Idle: Waiting for GO SWIM") }
    var isRecording by remember { mutableStateOf(false) }
    var startTimeMs by remember { mutableLongStateOf(0L) }
    var tMaxSec by remember { mutableStateOf(3.0) }
    var sampleCount by remember { mutableStateOf(0) }

    // Holds recorded CSV rows in memory during the swim window
    val csvRows = remember { mutableListOf<String>() }
    var sessionHeader by remember { mutableStateOf("") }

    // Latest sensor readings (updated asynchronously by SensorManager)
    val latestAcc = remember { FloatArray(3) }
    val latestGyro = remember { FloatArray(3) }
    val latestMag = remember { FloatArray(3) }

    fun finishAndSendCsv() {
        if (!isRecording) return
        isRecording = false
        val totalSamples = csvRows.size
        statusText = "Sending $totalSamples rows..."

        val fullCsv = buildString {
            appendLine(sessionHeader)
            appendLine("Timestamp,ax,ay,az,gx,gy,gz,mx,my,mz")
            for (row in csvRows) {
                appendLine(row)
            }
        }

        val asset = Asset.createFromBytes(fullCsv.toByteArray(Charsets.UTF_8))
        val putDataMapReq = PutDataMapRequest.create("/swim_csv").apply {
            dataMap.putAsset("csv_file", asset)
            dataMap.putInt("row_count", totalSamples)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener {
                statusText = "Sent $totalSamples rows!"
            }
            .addOnFailureListener { e ->
                statusText = "Send Err: ${e.message}"
            }
    }

    // Register 9-axis hardware sensors while isRecording == true
    DisposableEffect(isRecording) {
        if (!isRecording) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isRecording) return
                val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
                if (elapsedSec > tMaxSec) {
                    finishAndSendCsv()
                    return
                }

                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, latestAcc, 0, 3)
                        // Record a unified 10-column row on each accelerometer tick (~50 Hz)
                        val row = String.format(
                            Locale.US,
                            "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                            elapsedSec,
                            latestAcc[0], latestAcc[1], latestAcc[2],
                            latestGyro[0], latestGyro[1], latestGyro[2],
                            latestMag[0], latestMag[1], latestMag[2]
                        )
                        csvRows.add(row)
                        sampleCount = csvRows.size
                    }
                    Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, latestGyro, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, latestMag, 0, 3)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val samplingPeriod = SensorManager.SENSOR_DELAY_GAME // ~50 Hz (0.02s)
        accSensor?.let { sensorManager.registerListener(sensorListener, it, samplingPeriod) }
        gyroSensor?.let { sensorManager.registerListener(sensorListener, it, samplingPeriod) }
        magSensor?.let { sensorManager.registerListener(sensorListener, it, samplingPeriod) }

        onDispose {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    // Hard timeout watchdog for Tmax seconds
    LaunchedEffect(isRecording, startTimeMs, tMaxSec) {
        if (isRecording) {
            delay((tMaxSec * 1000).toLong())
            if (isRecording) {
                finishAndSendCsv()
            }
        }
    }

    // Listen for "GO SWIM" and "STOP SWIM" commands from Phone
    DisposableEffect(context) {
        val dataClient = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { dataEvents ->
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == "/swim_command"
                ) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val cmd = dataMap.getString("command") ?: ""
                    if (cmd == "GO SWIM") {
                        tMaxSec = dataMap.getDouble("tmax", 3.0)
                        csvRows.clear()
                        sampleCount = 0
                        val now = Date()
                        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                        val timeStr = SimpleDateFormat("HH-mm", Locale.US).format(now)
                        sessionHeader = "$dateStr  $timeStr"
                        startTimeMs = System.currentTimeMillis()
                        isRecording = true
                        statusText = "RECORDING (max ${tMaxSec}s)"
                    } else if (cmd == "STOP SWIM") {
                        finishAndSendCsv()
                    }
                }
            }
        }
        dataClient.addListener(listener)
        onDispose {
            dataClient.removeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Swim Sensor",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
        }

        Text(
            text = if (isRecording) "Samples: $sampleCount" else "Ready",
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center
        )
    }
}
