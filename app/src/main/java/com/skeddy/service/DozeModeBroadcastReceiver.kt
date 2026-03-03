package com.skeddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.skeddy.logging.SkeddyLogger

/**
 * BroadcastReceiver for handling device idle (Doze) mode changes.
 *
 * Receives ACTION_DEVICE_IDLE_MODE_CHANGED broadcasts to detect when the device
 * enters or exits Doze mode. This allows the service to adapt its behavior:
 * - When entering Doze: reduce activity, rely on AlarmManager wakeups
 * - When exiting Doze: resume normal monitoring
 *
 * Registration: This receiver is registered dynamically in MonitoringForegroundService
 * to receive updates only when the service is running.
 */
class DozeModeBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DozeReceiver"

        /** Broadcast action sent when Doze mode state changes */
        const val ACTION_DOZE_STATE_CHANGED = "com.skeddy.broadcast.DOZE_STATE_CHANGED"

        /** Extra key for Doze mode status (Boolean) */
        const val EXTRA_IS_DOZE_MODE = "extra_is_doze_mode"
    }

    /**
     * Called when the device enters or exits Doze mode.
     *
     * @param context The context in which the receiver is running
     * @param intent The intent with action ACTION_DEVICE_IDLE_MODE_CHANGED
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.w(TAG, "onReceive: null context or intent")
            return
        }

        if (intent.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
            Log.d(TAG, "onReceive: Ignoring unknown action: ${intent.action}")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDeviceIdleMode = powerManager.isDeviceIdleMode

        if (isDeviceIdleMode) {
            Log.i(TAG, "onReceive: Device entered Doze mode")
            SkeddyLogger.i(TAG, "Device entered Doze mode - monitoring via AlarmManager")
            onDozeEntered(context)
        } else {
            Log.i(TAG, "onReceive: Device exited Doze mode")
            SkeddyLogger.i(TAG, "Device exited Doze mode - resuming normal monitoring")
            onDozeExited(context)
        }

        // Broadcast state change for other components
        broadcastDozeStateChange(context, isDeviceIdleMode)
    }

    /**
     * Called when the device enters Doze mode.
     * The service should rely on AlarmManager.setExactAndAllowWhileIdle() during this time.
     */
    private fun onDozeEntered(context: Context) {
        // The MonitoringForegroundService will detect Doze mode and use AlarmManager
        // for scheduling wakeups instead of Handler.postDelayed()
        Log.d(TAG, "onDozeEntered: Service should use AlarmManager for wakeups")
    }

    /**
     * Called when the device exits Doze mode.
     * The service can resume normal Handler-based scheduling.
     */
    private fun onDozeExited(context: Context) {
        // The MonitoringForegroundService can resume normal scheduling
        Log.d(TAG, "onDozeExited: Service can resume Handler-based scheduling")
    }

    /**
     * Broadcasts the Doze state change to registered receivers.
     */
    private fun broadcastDozeStateChange(context: Context, isDozeMode: Boolean) {
        val broadcastIntent = Intent(ACTION_DOZE_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_IS_DOZE_MODE, isDozeMode)
        }
        context.sendBroadcast(broadcastIntent)
        Log.d(TAG, "broadcastDozeStateChange: isDozeMode=$isDozeMode")
    }
}
