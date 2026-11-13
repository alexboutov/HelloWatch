package com.natatometry.hellowatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.natatometry.hellowatch.ui.theme.HelloWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HelloWatchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneHomeScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PhoneHomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Waiting for Watch...") }
    var pingCount by remember { mutableStateOf(0) }

    // Listen for incoming Bluetooth messages from the Watch
    DisposableEffect(context) {
        val messageClient = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == "/ping_from_watch") {
                val payload = String(event.data, Charsets.UTF_8)
                statusText = "Received: $payload"
            }
        }
        messageClient.addListener(listener)
        onDispose {
            messageClient.removeListener(listener)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HelloWatch on Phone",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            pingCount++
            val message = "Phone Ping #$pingCount"
            statusText = "Sending: $message"
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    statusText = "No paired watch connected"
                } else {
                    for (node in nodes) {
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, "/ping_from_phone", message.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }) {
            Text(text = "Ping Watch")
        }
    }
}
