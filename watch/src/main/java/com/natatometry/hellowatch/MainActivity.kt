package com.natatometry.hellowatch

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.natatometry.hellowatch.theme.HelloWatchTheme

class MainActivity : ComponentActivity() {
    companion object {
        var isWaterLocked = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (isWaterLocked) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            HelloWatchTheme {
                Scaffold(timeText = { TimeText() }) {
                    WatchSwimRecorderScreen()
                }
            }
        }
    }
}
