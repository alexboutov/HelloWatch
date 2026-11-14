package com.natatometry.hellowatch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

@Composable
fun WatchSwimRecorderScreen() {
    val context = LocalContext.current
    val controller = remember { SwimSessionController() }

    lateinit var syncClient: WearDataSyncClient

    fun finishSession() {
        val result = controller.stopAndBuildCsv() ?: return
        val (count, csv) = result
        syncClient.sendCsvAsset(
            csvContent = csv,
            rowCount = count,
            onSuccess = { controller.statusText = "Sent $count rows! (Unlocked)" },
            onFailure = { err -> controller.statusText = "Send Err: $err" }
        )
    }

    syncClient = remember(context) {
        WearDataSyncClient(
            context = context,
            onGoSwim = { tMax -> controller.startSession(tMax) },
            onStopSwim = { finishSession() }
        )
    }

    val sensorManager = remember(context) {
        SwimSensorManager(
            context = context,
            onSampleRecorded = { row -> controller.addRow(row) },
            onMaxDurationReached = { finishSession() }
        )
    }

    BackHandler(enabled = controller.isRecording) {}

    DisposableEffect(controller.isRecording) {
        if (controller.isRecording) {
            sensorManager.start(controller.startTimeMs, controller.tMaxSec)
        }
        onDispose {
            sensorManager.stop()
        }
    }

    LaunchedEffect(controller.isRecording, controller.startTimeMs, controller.tMaxSec) {
        if (controller.isRecording) {
            delay((controller.tMaxSec * 1000).toLong())
            if (controller.isRecording) {
                finishSession()
            }
        }
    }

    DisposableEffect(syncClient) {
        syncClient.register()
        onDispose {
            syncClient.unregister()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (controller.isRecording) Color(0xFF1A0808) else Color.Black)
            .pointerInput(controller.isRecording) {
                if (controller.isRecording) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (controller.isRecording) "WATER LOCKED" else "Swim Sensor",
                    color = if (controller.isRecording) Color(0xFFFF6B6B) else Color.White,
                    style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = controller.statusText,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = if (controller.isRecording) "Samples: ${controller.sampleCount} (Touch Disabled)" else "Screen Unlocked",
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )
        }
    }
}
