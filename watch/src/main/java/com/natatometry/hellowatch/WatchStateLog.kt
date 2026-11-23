package com.natatometry.hellowatch

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display

/**
 * The watch's own state as a 5-character code, written into every CSV row (column st),
 * so that each sensor mode (row rate, timestamps) can be tied to a state of the watch.
 *
 *   character 1: display state as Android reports it:
 *                1 off, 2 on, 3 doze (ambient), 4 doze-suspend, 6 on-suspend, 0 unknown
 *   character 2: 'i' if the watch is interactive, else '-'
 *   character 3: 'r' if this app's screen is in front (activity resumed), else '-'
 *   character 4: 'd' if the device is in idle (Doze) mode, else '-'
 *   character 5: 's' if power save is on, else '-'
 * Example: "2ir--" awake with the app in front; "3----" ambient.
 *
 * The code is rebuilt at every change of the display and once a second.
 * [start] and [stop] may be called from any thread; [code] may be read from any thread.
 */
class WatchStateLog(context: Context) {

    companion object {
        private const val REFRESH_MS = 1000L
        private const val UNKNOWN = "0----"
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Touched on the main thread only. */
    private var listening = false

    /** The state code as of the latest refresh. */
    @Volatile var code: String = UNKNOWN
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    /** Call at the start of every session. The code is valid as soon as this returns. */
    fun start() {
        refresh()
        mainHandler.post {
            if (!listening) {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
                listening = true
            }
            mainHandler.removeCallbacks(tick)
            mainHandler.postDelayed(tick, REFRESH_MS)
        }
    }

    /** Call at the end of every session. */
    fun stop() {
        mainHandler.post {
            mainHandler.removeCallbacks(tick)
            if (listening) {
                displayManager?.unregisterDisplayListener(displayListener)
                listening = false
            }
        }
    }

    private fun refresh() {
        val state = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: 0
        val text = StringBuilder(5)
        text.append(if (state in 0..9) '0' + state else '0')
        text.append(if (powerManager?.isInteractive == true) 'i' else '-')
        text.append(if (MainActivity.isInFront) 'r' else '-')
        text.append(if (powerManager?.isDeviceIdleMode == true) 'd' else '-')
        text.append(if (powerManager?.isPowerSaveMode == true) 's' else '-')
        code = text.toString()
    }
}
