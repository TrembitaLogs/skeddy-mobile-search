package com.skeddy.service

import android.app.job.JobParameters
import android.app.job.JobService
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
 * JobService for restarting MonitoringForegroundService after it's killed.
 *
 * This service is scheduled via JobScheduler when:
 * - User swipes away the app (onTaskRemoved)
 * - System kills the service due to resource constraints
 *
 * Provides a reliable backup mechanism for START_STICKY restart.
 */
class ServiceRestartJobService : JobService() {

    companion object {
        const val TAG = "ServiceRestartJob"

        /** Job ID for service restart job */
        const val JOB_ID = 1001

        /** SharedPreferences key for checking if monitoring was active */
        private const val PREFS_NAME = "service_restart_prefs"
        private const val KEY_MONITORING_WAS_ACTIVE = "monitoring_was_active"
        private const val KEY_RESTART_TIMESTAMP = "restart_timestamp"

        /**
         * Marks that monitoring was active (called before potential kill).
         * Used to determine if service should be restarted.
         */
        fun markMonitoringActive(context: Context, isActive: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITORING_WAS_ACTIVE, isActive)
                .apply()
            Log.d(TAG, "markMonitoringActive: $isActive")
        }

        /**
         * Checks if monitoring was active before kill.
         */
        fun wasMonitoringActive(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING_WAS_ACTIVE, false)
        }

        /**
         * Clears the monitoring active flag.
         */
        fun clearMonitoringFlag(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MONITORING_WAS_ACTIVE)
                .apply()
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "onStartJob: Service restart job triggered")
        SkeddyLogger.i(TAG, "Service restart job triggered - attempting to restart monitoring service")

        // Check if monitoring was active before kill
        if (!wasMonitoringActive(this)) {
            Log.d(TAG, "onStartJob: Monitoring was not active, skipping restart")
            SkeddyLogger.d(TAG, "Monitoring was not active before kill, skipping restart")
            return false // Job completed, no need to reschedule
        }

        // Check app state - only restart service in LOGGED_IN or FORCE_UPDATE states
        val deviceTokenManager = DeviceTokenManager(applicationContext)
        val preferences = SkeddyPreferences(applicationContext)
        val appStateDeterminer = AppStateDeterminer(deviceTokenManager, preferences)
        val isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            applicationContext, SkeddyAccessibilityService::class.java
        )
        val currentState = appStateDeterminer.determine(isAccessibilityEnabled)

        when (currentState) {
            is AppState.LoggedIn, is AppState.ForceUpdate -> {
                Log.i(TAG, "onStartJob: App state=$currentState, proceeding with restart")
                SkeddyLogger.i(TAG, "JobService: app state=$currentState, restarting service")
            }
            is AppState.NotLoggedIn, is AppState.NotConfigured -> {
                Log.i(TAG, "onStartJob: Skipping restart, state=$currentState")
                SkeddyLogger.i(TAG, "JobService: skipping restart, state=$currentState")
                clearMonitoringFlag(this)
                return false
            }
        }

        // Record restart timestamp for debugging
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_RESTART_TIMESTAMP, System.currentTimeMillis())
            .apply()

        try {
            // Start the monitoring service
            val intent = Intent(this, MonitoringForegroundService::class.java).apply {
                action = MonitoringForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }

            Log.i(TAG, "onStartJob: MonitoringForegroundService restart initiated")
            SkeddyLogger.i(TAG, "MonitoringForegroundService restart initiated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "onStartJob: Failed to restart service", e)
            SkeddyLogger.e(TAG, "Failed to restart MonitoringForegroundService: ${e.message}")
        }

        // Return false - job is complete, no need for rescheduling
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.w(TAG, "onStopJob: Job stopped by system")
        SkeddyLogger.w(TAG, "Service restart job stopped by system")
        // Return true to reschedule the job if it was interrupted
        return true
    }
}
