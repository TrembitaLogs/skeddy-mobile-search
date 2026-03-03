package com.skeddy.notification

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper for handling POST_NOTIFICATIONS permission on Android 13+.
 * Provides graceful degradation for older Android versions.
 */
class NotificationPermissionHelper(private val context: Context) {

    companion object {
        private const val TAG = "NotificationPermission"

        /** Minimum SDK version requiring POST_NOTIFICATIONS permission */
        const val MIN_SDK_FOR_PERMISSION = Build.VERSION_CODES.TIRAMISU // API 33
    }

    /**
     * Checks if the app has notification permission.
     *
     * @return true if:
     *   - Android < 13 (permission not required)
     *   - Android 13+ and POST_NOTIFICATIONS permission granted
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= MIN_SDK_FOR_PERMISSION) {
            val result = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!result) {
                Log.d(TAG, "POST_NOTIFICATIONS permission not granted")
            }
            result
        } else {
            // Permission not required on Android < 13
            true
        }
    }

    /**
     * Determines if the app should request notification permission.
     *
     * @param activity Activity needed for shouldShowRequestPermissionRationale check
     * @return true if Android 13+ and permission not granted yet
     */
    fun shouldRequestPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_PERMISSION) {
            return false
        }

        return !hasNotificationPermission()
    }

    /**
     * Checks if we should show rationale explaining why permission is needed.
     *
     * @param activity Activity for rationale check
     * @return true if user previously denied permission and we should explain why it's needed
     */
    fun shouldShowRationale(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_PERMISSION) {
            return false
        }

        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    /**
     * Logs the current permission status for debugging.
     */
    fun logPermissionStatus() {
        val sdkVersion = Build.VERSION.SDK_INT
        val hasPermission = hasNotificationPermission()

        Log.i(TAG, "SDK Version: $sdkVersion (Tiramisu=${Build.VERSION_CODES.TIRAMISU})")
        Log.i(TAG, "Permission required: ${sdkVersion >= MIN_SDK_FOR_PERMISSION}")
        Log.i(TAG, "Has permission: $hasPermission")
    }
}
