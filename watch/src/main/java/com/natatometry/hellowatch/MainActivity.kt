package com.natatometry.hellowatch

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.natatometry.hellowatch.theme.HelloWatchTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "HelloWatchKeys"

        /** MAP button (upper right) on the WSD-F30. A long press starts the swim. */
        private const val START_KEYCODE = KeyEvent.KEYCODE_STEM_1

        var isWaterLocked = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (isWaterLocked) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // During the swim every key event the app receives is swallowed.
        if (isWaterLocked) {
            return true
        }

        when (event.keyCode) {
            START_KEYCODE -> {
                val longPress = event.action == KeyEvent.ACTION_DOWN &&
                    (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
                if (longPress) {
                    Log.d(TAG, "MAP long press: swim start requested")
                    SwimRecordingService.requestStart(this)
                }
                return true
            }
            // TOOL button (lower right): consumed so it does not leave the app.
            KeyEvent.KEYCODE_STEM_2 -> return true
        }
        return super.dispatchKeyEvent(event)
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
