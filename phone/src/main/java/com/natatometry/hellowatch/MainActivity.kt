package com.natatometry.hellowatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.natatometry.hellowatch.ui.theme.HelloWatchTheme

class MainActivity : ComponentActivity() {
    /** Times the app has come to the front; on each one the screen reads the list of files again. */
    private var resumeCount by mutableIntStateOf(0)

    /**
     * Transfer test: the socket receiver needs BLUETOOTH_CONNECT on Android 12 and later. It tries
     * to listen again every 5 s, so nothing has to be done here once the permission is given.
     */
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent {
            HelloWatchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneSwimControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        refreshKey = resumeCount
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
