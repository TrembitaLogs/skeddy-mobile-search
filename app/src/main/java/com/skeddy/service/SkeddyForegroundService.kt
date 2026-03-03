package com.skeddy.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * TODO: Foreground Service для постійного моніторингу
 *
 * Призначення:
 * - Підтримувати Accessibility Service активним
 * - Показувати persistent notification зі статусом моніторингу
 * - Керувати lifecycle моніторингу райдів
 * - Забезпечувати роботу в background
 */
class SkeddyForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // TODO: Implement binding if needed
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Start foreground with notification
        return START_STICKY
    }
}
