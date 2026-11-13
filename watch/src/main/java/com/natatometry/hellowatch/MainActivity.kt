package com.natatometry.hellowatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.natatometry.hellowatch.theme.HelloWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HelloWatchTheme {
                Scaffold(timeText = { TimeText() }) {
                    WatchHomeScreen()
                }
            }
        }
    }
}

@Composable
fun WatchHomeScreen() {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Ready to send data") }
    var pingCount by remember { mutableStateOf(0) }

    // Listen for incoming messages from the Phone
    DisposableEffect(context) {
        val messageClient = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == "/ping_from_phone") {
                val payload = String(event.data, Charsets.UTF_8)
                statusText = "Got: $payload"
            }
        }
        messageClient.addListener(listener)
        onDispose {
            messageClient.removeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HelloPhone on Watch",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = {
            pingCount++
            val message = "Watch Sensor #$pingCount"
            statusText = "Sent #$pingCount"
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    statusText = "No phone paired"
                } else {
                    for (node in nodes) {
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, "/ping_from_watch", message.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }) {
            Text(text = "Ping")
        }
    }
}
