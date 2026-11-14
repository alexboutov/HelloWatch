package com.natatometry.hellowatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PhoneSwimController {
    var tMaxInput by mutableStateOf("3.0")
    var statusText by mutableStateOf("Ready to record swim")
    var csvPreview by mutableStateOf("")

    fun parseTMax(): Double {
        return tMaxInput.toDoubleOrNull() ?: 3.0
    }

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
