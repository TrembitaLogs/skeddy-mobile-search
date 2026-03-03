package com.skeddy.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * TODO: Helper для роботи з notifications
 *
 * Призначення:
 * - Створювати notification channel
 * - Показувати foreground service notification
 * - Показувати alerts про нові райди
 * - Оновлювати notification зі статусом
 */
object NotificationHelper {

    private const val CHANNEL_ID = "skeddy_monitoring"
    private const val CHANNEL_NAME = "Skeddy Monitoring"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for Skeddy monitoring service"
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    // TODO: Add methods for creating different notification types
}
