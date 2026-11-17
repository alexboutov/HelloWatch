package com.natatometry.hellowatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.floor

/** Foreground-service notification of SwimRecordingService. */
object SwimNotification {
    private const val CHANNEL_ID = "swim_recording_channel"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Swim Sensor Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun build(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("HelloWatch Swim Sensor")
            .setContentText("Hold MAP to start a swim")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}

/**
 * Partial wake lock held during a swim. Keeps the CPU and sensors running if the
 * screen turns off or the app is sent to the background.
 */
class SwimWakeLock(context: Context) {
    private val lock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelloWatch:SwimRecording")
            .apply { setReferenceCounted(false) }

    /** Acquires the lock; it is released automatically after [timeoutMs]. */
    fun acquire(timeoutMs: Long) {
        lock.acquire(timeoutMs)
    }

    fun release() {
        if (lock.isHeld) lock.release()
    }
}

/** Seconds for display: whole values without decimals ("3"), others as they are ("2.5"). */
fun formatSeconds(seconds: Double): String {
    return if (seconds.isFinite() && seconds == floor(seconds)) {
        seconds.toLong().toString()
    } else {
        seconds.toString()
    }
}
