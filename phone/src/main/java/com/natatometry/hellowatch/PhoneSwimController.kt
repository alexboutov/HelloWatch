package com.natatometry.hellowatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State of the phone screen. The fields start from the params last sent, kept in [store]. */
class PhoneSwimController(private val store: PhoneParamsStore) {
    var tMaxInput by mutableStateOf(store.tMaxInput("3"))
    var azimuthInput by mutableStateOf(store.azimuthInput("0"))

    /** Vibration feedback on the watch during a swim; sent with the other params. */
    var vibrationOn by mutableStateOf(store.vibrationOn(true))
    var statusText by mutableStateOf("Ready to record swim")

    /** Names of the files received since the app was opened, newest swim first. */
    var receivedFiles by mutableStateOf(emptyList<String>())
        private set

    /** Keeps the current field values on the phone; called when they are sent to the watches. */
    fun saveParams() {
        store.save(tMaxInput.trim(), azimuthInput.trim(), vibrationOn)
    }

    /** Tmax in seconds; null if the input is not a finite number > 0. */
    fun parseTMax(): Double? {
        return tMaxInput.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Azimuth in degrees (magnetic, clockwise from north); null if not in 0..360. */
    fun parseAzimuth(): Double? {
        return azimuthInput.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= 360.0 }
    }

    private fun vibText(vibrationOn: Boolean) = if (vibrationOn) "on" else "off"

    fun onParamsSending(tMaxVal: Double, azimuthDeg: Double, vibrationOn: Boolean = true) {
        statusText = "Sending params (Tmax=${tMaxVal}s, Azimuth=${azimuthDeg} deg, Vibration ${vibText(vibrationOn)})..."
    }

    fun onParamsSent(tMaxVal: Double, azimuthDeg: Double, vibrationOn: Boolean = true) {
        statusText = "Params sent: Tmax=${tMaxVal}s, Azimuth=${azimuthDeg} deg, Vibration ${vibText(vibrationOn)}"
    }

    // ---- Temporary: phone-side GO SWIM, kept until the watch button start is confirmed ----

    fun onCommandSending(command: String, tMaxVal: Double) {
        statusText = "Sending: $command (Tmax=${tMaxVal}s)..."
    }

    fun onCommandSent(command: String, tMaxVal: Double) {
        statusText = "Watch recording (max ${tMaxVal}s)..."
    }

    /** Date-time part of "swim_<watchId>_<yyyy-MM-dd>_<HH-mm-ss>.csv"; it sorts as text. */
    private fun stampOf(fileName: String): String =
        fileName.removeSuffix(".csv").substringAfter("swim_").substringAfter("_")

    fun onCsvSaved(fileName: String, rowCount: Int) {
        statusText = "Saved $fileName ($rowCount rows)"
        receivedFiles = (receivedFiles + fileName).distinct()
            .sortedWith(compareByDescending<String> { stampOf(it) }.thenBy { it })
    }

    fun onError(prefix: String, message: String?) {
        statusText = "$prefix: ${message ?: "Unknown error"}"
    }
}
