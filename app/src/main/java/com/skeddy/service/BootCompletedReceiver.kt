package com.skeddy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.SkeddyPreferences
import com.skeddy.logging.SkeddyLogger
import com.skeddy.network.DeviceTokenManager
import com.skeddy.ui.AppState
import com.skeddy.ui.AppStateDeterminer
import com.skeddy.utils.PermissionUtils

/**
 * BroadcastReceiver that starts the monitoring service after device boot.
 *
 * Triggered by:
 * - BOOT_COMPLETED - device finished booting
 * - QUICKBOOT_POWERON - some devices use this instead
 * - LOCKED_BOOT_COMPLETED - for direct boot aware apps (Android 7+)
 *
 * Only starts the service if monitoring was previously active.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompletedReceiver"

        /** SharedPreferences for auto-start settings */
        private const val PREFS_NAME = "boot_receiver_prefs"
        private const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        private const val KEY_LAST_BOOT_START = "last_boot_start"

        /**
         * Enables or disables auto-start on boot.
         * Should be called when user starts/stops monitoring.
         */
        fun setAutoStartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START_ENABLED, enabled)
                .apply()
            Log.d(TAG, "setAutoStartEnabled: $enabled")
        }

        /**
         * Checks if auto-start on boot is enabled.
         */
        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START_ENABLED, false)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "onReceive: Received action: $action")
        SkeddyLogger.i(TAG, "Boot broadcast received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Direct boot mode - limited functionality available
                Log.d(TAG, "onReceive: Locked boot completed, waiting for full boot")
                SkeddyLogger.d(TAG, "Locked boot completed, waiting for full boot")
            }
            else -> {
                Log.w(TAG, "onReceive: Unknown action: $action")
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        Log.i(TAG, "handleBootCompleted: Device boot completed")
        SkeddyLogger.i(TAG, "Device boot completed - checking if service should auto-start")

        // Check if auto-start is enabled
        if (!isAutoStartEnabled(context)) {
            Log.d(TAG, "handleBootCompleted: Auto-start disabled, skipping service start")
            SkeddyLogger.d(TAG, "Auto-start disabled, not starting monitoring service")
            return
        }

        // Also check if monitoring was active before reboot (via ServiceRestartJobService flag)
        val wasMonitoringActive = ServiceRestartJobService.wasMonitoringActive(context)
        if (!wasMonitoringActive) {
            Log.d(TAG, "handleBootCompleted: Monitoring was not active before reboot, skipping")
            SkeddyLogger.d(TAG, "Monitoring was not active before reboot, not starting")
            return
        }

        // Check app state - only start service in LOGGED_IN or FORCE_UPDATE states
        val deviceTokenManager = DeviceTokenManager(context)
        val preferences = SkeddyPreferences(context)
        val appStateDeterminer = AppStateDeterminer(deviceTokenManager, preferences)
        val isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            context, SkeddyAccessibilityService::class.java
        )
        val currentState = appStateDeterminer.determine(isAccessibilityEnabled)

        when (currentState) {
            is AppState.LoggedIn, is AppState.ForceUpdate -> {
                Log.i(TAG, "handleBootCompleted: App state=$currentState, proceeding with service start")
                SkeddyLogger.i(TAG, "Boot: app state=$currentState, starting service")
            }
            is AppState.NotLoggedIn -> {
                Log.i(TAG, "handleBootCompleted: Skipping service start - device not logged in")
                SkeddyLogger.i(TAG, "Boot: skipping service start - device not logged in")
                return
            }
            is AppState.NotConfigured -> {
                Log.i(TAG, "handleBootCompleted: Skipping service start - accessibility not configured")
                SkeddyLogger.i(TAG, "Boot: skipping service start - accessibility not configured")
                return
            }
        }

        // Record boot start for debugging
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BOOT_START, System.currentTimeMillis())
            .apply()

        try {
            Log.i(TAG, "handleBootCompleted: Starting MonitoringForegroundService")
            SkeddyLogger.i(TAG, "Starting MonitoringForegroundService after boot")

            val serviceIntent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = MonitoringForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "handleBootCompleted: Service start initiated successfully")
            SkeddyLogger.i(TAG, "MonitoringForegroundService start initiated after boot")

        } catch (e: Exception) {
            Log.e(TAG, "handleBootCompleted: Failed to start service", e)
            SkeddyLogger.e(TAG, "Failed to start MonitoringForegroundService after boot: ${e.message}")
        }
    }
}
