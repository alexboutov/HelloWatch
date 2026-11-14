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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun WatchSwimRecorderScreen() {
    val context = LocalContext.current
    val state by SwimRecordingService.uiState.collectAsState()

    LaunchedEffect(Unit) {
        SwimRecordingService.startService(context)
    }

    BackHandler(enabled = state.isRecording) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (state.isRecording) Color(0xFF1A0808) else Color.Black)
            .pointerInput(state.isRecording) {
                if (state.isRecording) {
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
                    text = if (state.isRecording) "WATER LOCKED" else "Swim Sensor",
                    color = if (state.isRecording) Color(0xFFFF6B6B) else Color.White,
                    style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = if (state.isRecording) "Samples: ${state.sampleCount} (Touch Disabled)" else "Screen Unlocked",
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )
        }
    }
}
