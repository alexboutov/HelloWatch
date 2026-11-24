package com.natatometry.hellowatch

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The lock of a swim as a window of its own: invisible, as large as the display, on top of
 * every app. It swallows every touch and every key that reaches it (MAP, TOOL), whatever is
 * underneath: HelloWatch's own screen, the watch face or another app. It also holds the
 * display on.
 *
 * It belongs to the recording service, not to a screen, so it stays in place when the watch
 * puts something else in front. Android shows a window of this kind ("application overlay")
 * above all activity windows and below critical system windows. The power button reaches
 * no window.
 *
 * Needs the permission "display over other apps", given once per watch from the laptop:
 *   adb shell appops set com.natatometry.hellowatch SYSTEM_ALERT_WINDOW allow
 * Without it [show] returns false and no window is put up.
 *
 * Main thread only.
 */
class SwimLockWindow(private val context: Context) {

    companion object {
        private const val TAG = "SwimLockWindow"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /** The view that is up, or null. */
    private var view: View? = null

    /** True if this watch lets the app put the window up. */
    val isAllowed: Boolean
        get() = Settings.canDrawOverlays(context)

    /** Puts the window up. Returns true if it is in place afterwards. */
    @Suppress("DEPRECATION") // FLAG_LAYOUT_IN_SCREEN: the window covers the whole display.
    fun show(): Boolean {
        if (view != null) return true
        val manager = windowManager ?: return false
        if (!isAllowed) {
            Log.w(TAG, "No permission to display over other apps: lock window not put up")
            return false
        }
        // FLAG_NOT_FOCUSABLE is left out on purpose: the window takes the key focus, so MAP
        // and TOOL come here. FLAG_ALT_FOCUSABLE_IM: no keyboard stands between the keys and
        // this window.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )
        params.title = "HelloWatch swim lock"
        val newView = SwallowingView(context)
        return try {
            manager.addView(newView, params)
            view = newView
            true
        } catch (e: RuntimeException) {
            // Refused by the system: the swim goes on with the lock of the screen alone.
            Log.e(TAG, "Lock window refused", e)
            false
        }
    }

    /** Takes the window down. Does nothing if it is not up. */
    fun hide() {
        val oldView = view ?: return
        view = null
        try {
            windowManager?.removeView(oldView)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Lock window could not be removed", e)
        }
    }

    /** Draws nothing and lets nothing through. */
    private class SwallowingView(context: Context) : View(context) {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean = true
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
    }
}
