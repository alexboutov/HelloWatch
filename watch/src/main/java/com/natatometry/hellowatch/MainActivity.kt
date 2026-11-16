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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    companion object {
        private const val KEY_TAG = "KeyCheck"
        private const val KEY_LOG_LINES = 5

        var isWaterLocked = false

        // M0 step 1: last key events received by the app, shown on the watch screen.
        private val _keyLog = MutableStateFlow<List<String>>(emptyList())
        val keyLog = _keyLog.asStateFlow()
    }

    private var keyEventCount = 0

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (isWaterLocked) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * M0 step 1: key-code check.
     * Every key event that reaches the app is written to Logcat (tag "KeyCheck")
     * and to the on-screen list. Stem keys are consumed so the app stays in the
     * foreground; all other keys keep their normal behavior.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isStem = when (event.keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3 -> true
            else -> false
        }

        Log.d(KEY_TAG, "consumed=$isStem $event")

        val longPress = (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
        // Auto-repeat events are skipped on screen, except the one that marks a long press.
        if (event.repeatCount == 0 || longPress) {
            keyEventCount++
            val name = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
            val action = when {
                longPress -> "LONG"
                event.action == KeyEvent.ACTION_DOWN -> "DOWN"
                event.action == KeyEvent.ACTION_UP -> "UP"
                else -> "A${event.action}"
            }
            val line = "$keyEventCount: $name ${event.keyCode} $action"
            _keyLog.value = (_keyLog.value + line).takeLast(KEY_LOG_LINES)
        }

        return if (isStem) true else super.dispatchKeyEvent(event)
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
