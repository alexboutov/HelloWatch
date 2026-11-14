package com.natatometry.hellowatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.natatometry.hellowatch.ui.theme.HelloWatchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HelloWatchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneSwimControlScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PhoneSwimControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tMaxInput by remember { mutableStateOf("3.0") }
    var statusText by remember { mutableStateOf("Ready to record swim") }
    var csvPreview by remember { mutableStateOf("") }

    fun sendSwimCommand(command: String) {
        val tMaxVal = tMaxInput.toDoubleOrNull() ?: 3.0
        statusText = "Sending: $command (Tmax=${tMaxVal}s)..."
        val putDataMapReq = PutDataMapRequest.create("/swim_command").apply {
            dataMap.putString("command", command)
            dataMap.putDouble("tmax", tMaxVal)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener {
                statusText = if (command == "GO SWIM") {
                    "Watch recording (max ${tMaxVal}s)..."
                } else {
                    "Sent STOP SWIM. Waiting for CSV..."
                }
            }
            .addOnFailureListener { e ->
                statusText = "Command Err: ${e.message}"
            }
    }

    // Listen for incoming CSV Asset from the Watch
    DisposableEffect(context) {
        val dataClient = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { dataEvents ->
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == "/swim_csv"
                ) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val asset = dataMap.getAsset("csv_file")
                    val rowCount = dataMap.getInt("row_count", 0)

                    if (asset != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val fdResponse = Tasks.await(dataClient.getFdForAsset(asset))
                                val csvContent = fdResponse.inputStream.bufferedReader().use { it.readText() }

                                // Save CSV file to phone storage
                                val dir = File(context.getExternalFilesDir(null), "swim_logs")
                                if (!dir.exists()) dir.mkdirs()
                                val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                                val outFile = File(dir, "swim_$fileStamp.csv")
                                outFile.writeText(csvContent)

                                val previewLines = csvContent.lines().take(6).joinToString("\n")
                                withContext(Dispatchers.Main) {
                                    statusText = "Saved ${outFile.name} ($rowCount rows)"
                                    csvPreview = previewLines
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusText = "CSV Read Err: ${e.message}"
                                }
                            }
                        }
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
            value = tMaxInput,
            onValueChange = { tMaxInput = it },
            label = { Text("Tmax (seconds)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = { sendSwimCommand("GO SWIM") }) {
                Text("GO SWIM")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { sendSwimCommand("STOP SWIM") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("STOP SWIM")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge
        )

        if (csvPreview.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Preview (first 6 lines):\n$csvPreview",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
