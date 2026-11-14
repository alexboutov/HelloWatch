package com.natatometry.hellowatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwimSessionController {
    var statusText by mutableStateOf("Idle: Waiting for GO SWIM")
    var isRecording by mutableStateOf(false)
    var startTimeMs by mutableLongStateOf(0L)
    var tMaxSec by mutableDoubleStateOf(3.0)
    var sampleCount by mutableIntStateOf(0)

    private val csvRows = mutableListOf<String>()
    private var sessionHeader = ""

    fun startSession(maxDurationSec: Double) {
        tMaxSec = maxDurationSec
        csvRows.clear()
        sampleCount = 0

        val now = Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val timeStr = SimpleDateFormat("HH-mm", Locale.US).format(now)
        sessionHeader = "$dateStr  $timeStr"

        startTimeMs = System.currentTimeMillis()
        isRecording = true
        MainActivity.isWaterLocked = true
        statusText = "RECORDING (max ${tMaxSec}s)"
    }

    fun addRow(row: String) {
        if (!isRecording) return
        csvRows.add(row)
        sampleCount = csvRows.size
    }

    fun stopAndBuildCsv(): Pair<Int, String>? {
        if (!isRecording) return null
        isRecording = false
        MainActivity.isWaterLocked = false

        val totalSamples = csvRows.size
        statusText = "Sending $totalSamples rows..."

        val fullCsv = buildString {
            appendLine(sessionHeader)
            appendLine("Timestamp,ax,ay,az,gx,gy,gz,mx,my,mz")
            for (row in csvRows) {
                appendLine(row)
            }
        }
        return Pair(totalSamples, fullCsv)
    }
}
