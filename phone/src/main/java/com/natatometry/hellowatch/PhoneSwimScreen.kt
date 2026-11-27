package com.natatometry.hellowatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** [refreshKey]: the list of the files on the phone is read again whenever this value changes. */
@Composable
fun PhoneSwimControlScreen(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val controller = remember(context) { PhoneSwimController(PhoneParamsStore(context)) }
    val storage = remember(context) { SwimFileStorage(context) }
    val fileList = remember(context) { PhoneFileListState() }
    val syncClient = remember(context, scope) {
        PhoneWearSyncClient(
            context = context,
            scope = scope,
            storage = storage,
            onCsvSaved = { fileName, count, _ ->
                controller.onCsvSaved(fileName, count)
                fileList.onSaved(fileName)
            },
            onError = { prefix, msg -> controller.onError(prefix, msg) }
        )
    }

    DisposableEffect(syncClient) {
        syncClient.register()
        onDispose {
            syncClient.unregister()
        }
    }

    fun sendParams() {
        val tMaxVal = controller.parseTMax()
        if (tMaxVal == null) {
            controller.onError("Params Err", "Tmax must be a number > 0")
            return
        }
        val azimuthDeg = controller.parseAzimuth()
        if (azimuthDeg == null) {
            controller.onError("Params Err", "Azimuth must be a number from 0 to 360")
            return
        }
        val vibrationOn = controller.vibrationOn
        controller.saveParams()
        controller.onParamsSending(tMaxVal, azimuthDeg, vibrationOn)
        syncClient.sendSwimParams(
            tMaxVal = tMaxVal,
            azimuthDeg = azimuthDeg,
            vibrationOn = vibrationOn,
            useSocket = controller.useSocket,
            onSuccess = { controller.onParamsSent(tMaxVal, azimuthDeg, vibrationOn) },
            onFailure = { msg -> controller.onError("Params Err", msg) }
        )
    }

    // Temporary: phone-side GO SWIM, kept until the watch button start is confirmed.
    // It first sends the params, then the command, so the watch has both.
    fun triggerCommand(command: String) {
        val tMaxVal = controller.parseTMax()
        if (tMaxVal == null) {
            controller.onError("Command Err", "Tmax must be a number > 0")
            return
        }
        val azimuthDeg = controller.parseAzimuth()
        if (azimuthDeg == null) {
            controller.onError("Command Err", "Azimuth must be a number from 0 to 360")
            return
        }
        controller.saveParams()
        controller.onCommandSending(command, tMaxVal)
        syncClient.sendSwimParams(
            tMaxVal = tMaxVal,
            azimuthDeg = azimuthDeg,
            vibrationOn = controller.vibrationOn,
            useSocket = controller.useSocket,
            onSuccess = {
                syncClient.sendSwimCommand(
                    command = command,
                    tMaxVal = tMaxVal,
                    onSuccess = { controller.onCommandSent(command, tMaxVal) },
                    onFailure = { msg -> controller.onError("Command Err", msg) }
                )
            },
            onFailure = { msg -> controller.onError("Params Err", msg) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HelloWatch Swim Recorder",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Tmax and Azimuth share one row to leave room for the list of files.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.tMaxInput,
                onValueChange = { controller.tMaxInput = it },
                label = { Text("Tmax, s") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = controller.azimuthInput,
                onValueChange = { controller.azimuthInput = it },
                label = { Text("Az, deg") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Vibration feedback on the watch; takes effect when the params are sent.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (controller.vibrationOn) "Vibration: ON" else "Vibration: OFF")
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = controller.vibrationOn,
                onCheckedChange = { controller.vibrationOn = it }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Transfer test: how the watches send the CSV; takes effect when the params are sent.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (controller.useSocket) "Transfer: Socket" else "Transfer: Data Layer")
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = controller.useSocket, onCheckedChange = { controller.useSocket = it })
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { sendParams() }) {
            Text("Send params")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Temporary: phone-side start, removed once the watch button start is confirmed.
        Button(onClick = { triggerCommand("GO SWIM") }) {
            Text("GO SWIM")
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = controller.statusText,
            style = MaterialTheme.typography.bodyLarge
        )

        // All swim*.csv files on the phone, newest swim first: select and delete (PhoneFileList.kt).
        Spacer(modifier = Modifier.height(12.dp))
        PhoneFilesSection(
            fileList = fileList,
            storage = storage,
            refreshKey = refreshKey,
            onStatus = { controller.statusText = it }
        )
    }
}
