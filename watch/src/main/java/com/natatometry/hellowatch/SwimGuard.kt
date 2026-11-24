package com.natatometry.hellowatch

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.Locale

/**
 * Keeps a swim uninterrupted from its start to Tmax, whatever the watch does:
 *   - the lock window (SwimLockWindow) is up for the whole swim;
 *   - if the watch leaves the awake state (display not on, or not interactive), it is woken
 *     at once;
 *   - if HelloWatch's screen is not in front, it is brought back;
 *   - every time the watch is found "away" (not awake, or the screen not in front) is counted
 *     and timed, for line 1 of the CSV and for the watch screen.
 * The state is checked every CHECK_MS and at every change of the display, so a time away is
 * measured in steps of CHECK_MS.
 *
 * It belongs to the recording service. Main thread only.
 */
class SwimGuard(private val context: Context) {

    companion object {
        private const val TAG = "SwimGuard"
        private const val CHECK_MS = 250L
        /** The watch is not woken twice within this time; a wake-up without effect is repeated after it. */
        private const val WAKE_UP_AGAIN_MS = 500L
        /** A return to the front that had no effect is asked again after this long. */
        private const val RETURN_AGAIN_MS = 1000L
        /** The wake-up holds the display on this long, or until HelloWatch is in front again. */
        private const val WAKE_HOLD_MS = 10_000L
        /** The guard ends itself this long after Tmax if the service has not ended it. */
        private const val END_MARGIN_MS = 2000L
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private val lockWindow = SwimLockWindow(context)

    @Suppress("DEPRECATION") // On Android 9 this is how a service turns the display on.
    private val wakeUpLock: PowerManager.WakeLock? = powerManager
        ?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HelloWatch:SwimGuardWakeUp"
        )
        ?.apply { setReferenceCounted(false) }

    private var active = false
    private var lockWasFull = false
    private var awayCount = 0
    private var awayTotalMs = 0L
    private var awayMaxMs = 0L
    /** SystemClock.elapsedRealtime() at which the watch was found away; -1 while it is not away. */
    private var awaySinceMs = -1L
    private var lastWakeUpMs = -WAKE_UP_AGAIN_MS
    private var lastReturnMs = -RETURN_AGAIN_MS

    /** True if this watch allows the lock window (permission "display over other apps"). */
    val canLockFully: Boolean
        get() = lockWindow.isAllowed

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) check()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            check()
            if (active) handler.postDelayed(this, CHECK_MS)
        }
    }

    private val ownEnd = Runnable {
        if (active) {
            Log.w(TAG, "Not ended by the service: ending $END_MARGIN_MS ms after Tmax")
            stop()
        }
    }

    /** Start of a swim that lasts [durationMs]. */
    fun start(durationMs: Long) {
        if (active) stop()
        active = true
        awayCount = 0
        awayTotalMs = 0L
        awayMaxMs = 0L
        awaySinceMs = -1L
        lastWakeUpMs = -WAKE_UP_AGAIN_MS
        lastReturnMs = -RETURN_AGAIN_MS
        MainActivity.isWaterLocked = true
        lockWasFull = lockWindow.show()
        displayManager?.registerDisplayListener(displayListener, handler)
        handler.postDelayed(tick, CHECK_MS)
        handler.postDelayed(ownEnd, durationMs + END_MARGIN_MS)
        check()
    }

    /** End of the swim. The counts stay readable until the next [start]. */
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(ownEnd)
        displayManager?.unregisterDisplayListener(displayListener)
        if (awaySinceMs >= 0) closeAway(SystemClock.elapsedRealtime())
        wakeUpLock?.let { if (it.isHeld) it.release() }
        lockWindow.hide()
        MainActivity.isWaterLocked = false
    }

    /** Fields for the end of line 1 of the CSV. Call after [stop]. */
    fun headerFields(): String {
        val lock = if (lockWasFull) "full" else "screen"
        return "away=$awayCount,awaysec=${seconds(awayTotalMs)},awaymax=${seconds(awayMaxMs)},lock=$lock"
    }

    /** One short line for the watch screen. Call after [stop]. */
    fun screenText(): String {
        return if (awayCount == 0) "away 0" else "away $awayCount (${seconds(awayTotalMs)}s)"
    }

    private fun check() {
        if (!active) return
        try {
            val now = SystemClock.elapsedRealtime()
            val awake = isAwake()
            val inFront = MainActivity.isInFront
            if (awake && inFront) {
                if (awaySinceMs >= 0) closeAway(now)
                return
            }
            if (awaySinceMs < 0) {
                awaySinceMs = now
                awayCount++
                Log.i(TAG, "Away #$awayCount: awake=$awake, HelloWatch in front=$inFront")
            }
            if (!awake) wakeUp(now)
            if (!inFront) bringToFront(now)
        } catch (e: RuntimeException) {
            // Whatever the watch refuses here must not take the recording down.
            Log.e(TAG, "Check failed", e)
        }
    }

    private fun isAwake(): Boolean {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val displayOn = display == null || display.state == Display.STATE_ON
        return displayOn && (powerManager?.isInteractive ?: true)
    }

    private fun closeAway(now: Long) {
        val lastedMs = now - awaySinceMs
        awaySinceMs = -1L
        awayTotalMs += lastedMs
        if (lastedMs > awayMaxMs) awayMaxMs = lastedMs
        wakeUpLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "Back after $lastedMs ms")
    }

    private fun wakeUp(now: Long) {
        if (now - lastWakeUpMs < WAKE_UP_AGAIN_MS) return
        lastWakeUpMs = now
        val lock = wakeUpLock ?: return
        // The display is turned on at the moment the lock is acquired, so it must not be held.
        if (lock.isHeld) lock.release()
        lock.acquire(WAKE_HOLD_MS)
    }

    private fun bringToFront(now: Long) {
        if (now - lastReturnMs < RETURN_AGAIN_MS) return
        lastReturnMs = now
        // "New task" is required from a service. With "single top" the screen that exists is
        // brought forward and no second copy of it is made.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "HelloWatch's screen could not be brought to the front", e)
        }
    }

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)
}
