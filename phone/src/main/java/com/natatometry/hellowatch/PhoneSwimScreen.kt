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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun PhoneSwimControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val controller = remember { PhoneSwimController() }
    val storage = remember(context) { SwimFileStorage(context) }
    val syncClient = remember(context, scope) {
        PhoneWearSyncClient(
            context = context,
            scope = scope,
            storage = storage,
            onCsvSaved = { fileName, count, preview -> controller.onCsvSaved(fileName, count, preview) },
            onError = { prefix, msg -> controller.onError(prefix, msg) }
        )
    }

    DisposableEffect(syncClient) {
        syncClient.register()
        onDispose {
            syncClient.unregister()
        }
    }

    fun triggerCommand(command: String) {
        val tMaxVal = controller.parseTMax()
        controller.onCommandSending(command, tMaxVal)
        syncClient.sendSwimCommand(
            command = command,
            tMaxVal = tMaxVal,
            onSuccess = { controller.onCommandSent(command, tMaxVal) },
            onFailure = { msg -> controller.onError("Command Err", msg) }
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

        OutlinedTextField(
            value = controller.tMaxInput,
            onValueChange = { controller.tMaxInput = it },
            label = { Text("Tmax (seconds)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = { triggerCommand("GO SWIM") }) {
                Text("GO SWIM")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { triggerCommand("STOP SWIM") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("STOP SWIM")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = controller.statusText,
            style = MaterialTheme.typography.bodyLarge
        )

        if (controller.csvPreview.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Preview (first 6 lines):\n${controller.csvPreview}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
