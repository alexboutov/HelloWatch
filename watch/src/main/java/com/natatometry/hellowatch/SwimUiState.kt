package com.natatometry.hellowatch

/** What the watch screen shows. Published by SwimRecordingService.uiState. */
data class SwimUiState(
    val statusText: String = "Idle: hold MAP to start",
    val paramsText: String = "Params: none (send from phone)",
    val isRecording: Boolean = false,
    val sampleCount: Int = 0,
    val endsAtElapsedMs: Long = 0L // SystemClock.elapsedRealtime() when the swim reaches Tmax (countdown)
)
