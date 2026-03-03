package com.skeddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Debug BroadcastReceiver for starting/stopping MonitoringForegroundService via ADB.
 *
 * Usage:
 * - Start: adb shell am broadcast -a com.skeddy.action.DEBUG_START_MONITORING
 * - Stop: adb shell am broadcast -a com.skeddy.action.DEBUG_STOP_MONITORING
 *
 * This receiver is only for debug/testing purposes and should be disabled in production.
 */
class MonitoringDebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MonitoringDebug"

        const val ACTION_DEBUG_START = "com.skeddy.action.DEBUG_START_MONITORING"
        const val ACTION_DEBUG_STOP = "com.skeddy.action.DEBUG_STOP_MONITORING"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "onReceive: action=${intent?.action}")

        when (intent?.action) {
            ACTION_DEBUG_START -> {
                Log.i(TAG, "Starting MonitoringForegroundService...")
                val serviceIntent = Intent(context, MonitoringForegroundService::class.java).apply {
                    action = MonitoringForegroundService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "MonitoringForegroundService start requested")
            }
            ACTION_DEBUG_STOP -> {
                Log.i(TAG, "Stopping MonitoringForegroundService...")
                val serviceIntent = Intent(context, MonitoringForegroundService::class.java).apply {
                    action = MonitoringForegroundService.ACTION_STOP
                }
                context.startService(serviceIntent)
                Log.i(TAG, "MonitoringForegroundService stop requested")
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
    }
}
