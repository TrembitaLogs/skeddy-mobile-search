package com.skeddy.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.skeddy.logging.SkeddyLogger

/**
 * WorkManager Worker for restarting MonitoringForegroundService.
 *
 * This is a fallback mechanism for Android 12+ where JobScheduler
 * has additional restrictions. WorkManager provides better support
 * for background work on newer Android versions.
 */
class ServiceRestartWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val TAG = "ServiceRestartWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "doWork: WorkManager restart triggered")
        SkeddyLogger.i(TAG, "WorkManager restart triggered - attempting to restart monitoring service")

        // Check if monitoring was active before kill
        if (!ServiceRestartJobService.wasMonitoringActive(applicationContext)) {
            Log.d(TAG, "doWork: Monitoring was not active, skipping restart")
            SkeddyLogger.d(TAG, "Monitoring was not active before kill, skipping restart")
            return Result.success()
        }

        return try {
            // Start the monitoring service
            val intent = Intent(applicationContext, MonitoringForegroundService::class.java).apply {
                action = MonitoringForegroundService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(applicationContext, intent)
            } else {
                applicationContext.startService(intent)
            }

            Log.i(TAG, "doWork: MonitoringForegroundService restart initiated via WorkManager")
            SkeddyLogger.i(TAG, "MonitoringForegroundService restart initiated via WorkManager")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: Failed to restart service", e)
            SkeddyLogger.e(TAG, "WorkManager failed to restart MonitoringForegroundService: ${e.message}")

            // Retry on failure
            Result.retry()
        }
    }
}
