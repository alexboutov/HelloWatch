package com.natatometry.hellowatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PhoneSwimController {
    var tMaxInput by mutableStateOf("3.0")
    var azimuthInput by mutableStateOf("")
    var statusText by mutableStateOf("Ready to record swim")
    var csvPreview by mutableStateOf("")

    /** Tmax in seconds; null if the input is not a finite number > 0. */
    fun parseTMax(): Double? {
        return tMaxInput.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Azimuth in degrees (magnetic, clockwise from north); null if not in 0..360. */
    fun parseAzimuth(): Double? {
        return azimuthInput.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= 360.0 }
    }

    fun onParamsSending(tMaxVal: Double, azimuthDeg: Double) {
        statusText = "Sending params (Tmax=${tMaxVal}s, Azimuth=${azimuthDeg} deg)..."
    }

    fun onParamsSent(tMaxVal: Double, azimuthDeg: Double) {
        statusText = "Params sent: Tmax=${tMaxVal}s, Azimuth=${azimuthDeg} deg"
    }

    // ---- Temporary: phone-side GO SWIM / STOP SWIM, kept until the watch button start is confirmed ----

    fun onCommandSending(command: String, tMaxVal: Double) {
        statusText = "Sending: $command (Tmax=${tMaxVal}s)..."
    }

    fun onCommandSent(command: String, tMaxVal: Double) {
        statusText = if (command == "GO SWIM") {
            "Watch recording (max ${tMaxVal}s)..."
        } else {
            "Sent STOP SWIM. Waiting for CSV..."
        }
    }

    fun onCsvSaved(fileName: String, rowCount: Int, previewLines: String) {
        statusText = "Saved $fileName ($rowCount rows)"
        csvPreview = previewLines
    }

    fun onError(prefix: String, message: String?) {
        statusText = "$prefix: ${message ?: "Unknown error"}"
    }
}
