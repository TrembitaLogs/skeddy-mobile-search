package com.skeddy.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper object for managing battery optimization settings.
 *
 * Android's battery optimization (Doze mode) can delay or prevent background work.
 * For reliable background monitoring, the app needs to request exemption from
 * battery optimizations.
 *
 * Usage:
 * - Check status: BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
 * - Request exemption: BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(activity)
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * Checks if the app is currently exempt from battery optimizations.
     *
     * @param context Application or Activity context
     * @return true if the app is ignoring battery optimizations, false otherwise
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        Log.d(TAG, "isIgnoringBatteryOptimizations: $isIgnoring")
        return isIgnoring
    }

    /**
     * Requests the user to disable battery optimizations for this app.
     *
     * This shows a system dialog asking the user to allow the app to run
     * without battery restrictions. The user can decline this request.
     *
     * Note: This permission allows the app to use setExactAndAllowWhileIdle()
     * for alarms that work during Doze mode.
     *
     * @param activity The activity context (needed for startActivity)
     * @return true if the request was launched, false if already exempt
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity): Boolean {
        if (isIgnoringBatteryOptimizations(activity)) {
            Log.d(TAG, "requestIgnoreBatteryOptimizations: Already exempt, skipping request")
            return false
        }

        Log.i(TAG, "requestIgnoreBatteryOptimizations: Requesting exemption")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
        return true
    }

    /**
     * Opens the battery optimization settings page for manual configuration.
     *
     * Use this as a fallback if the direct request fails or if you want to
     * give users more control over their settings.
     *
     * @param context Application or Activity context
     */
    fun openBatteryOptimizationSettings(context: Context) {
        Log.i(TAG, "openBatteryOptimizationSettings: Opening settings")
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Gets a user-friendly status message about battery optimization.
     *
     * @param context Application or Activity context
     * @return Localized status message
     */
    fun getStatusMessage(context: Context): String {
        return if (isIgnoringBatteryOptimizations(context)) {
            "Battery optimization disabled - monitoring will work reliably"
        } else {
            "Battery optimization enabled - monitoring may be interrupted"
        }
    }
}
