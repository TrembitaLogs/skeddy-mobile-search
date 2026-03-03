package com.skeddy.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.skeddy.R
import com.skeddy.ui.MainActivity

/**
 * Manages Skeddy monitoring notification for the foreground service.
 *
 * Provides the monitoring notification channel (low priority) for foreground service status.
 *
 * @param context Application context for creating notifications
 */
class SkeddyNotificationManager(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        /** Channel ID for monitoring service status (low priority) */
        const val CHANNEL_MONITORING = "skeddy_monitoring"

        /** Notification ID for the persistent monitoring notification */
        const val NOTIFICATION_MONITORING_ID = 1
    }

    /**
     * Creates the monitoring notification channel.
     * Should be called once during app/service initialization.
     */
    fun createChannels() {
        val channel = NotificationChannel(
            CHANNEL_MONITORING,
            "Skeddy Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows monitoring status"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Creates a notification for the foreground monitoring service.
     *
     * @param isSearching Whether the service is actively searching
     * @param intervalSeconds Current search interval in seconds, shown when searching
     * @return Notification suitable for foreground service
     */
    fun getMonitoringNotification(isSearching: Boolean, intervalSeconds: Int? = null): Notification {
        val contentText = if (isSearching && intervalSeconds != null) {
            context.getString(R.string.notification_searching, intervalSeconds)
        } else {
            context.getString(R.string.notification_stopped)
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
