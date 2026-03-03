package com.skeddy.utils

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Утиліти для перевірки та управління дозволами додатку.
 */
object PermissionUtils {

    private const val TAG = "PermissionUtils"

    /**
     * Перевіряє чи Accessibility Service увімкнений для вказаного класу сервісу.
     *
     * @param context контекст додатку
     * @param serviceClass клас Accessibility Service для перевірки
     * @return true якщо сервіс увімкнений, false якщо вимкнений
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        Log.d(TAG, "Expected ComponentName: $expectedComponentName")
        Log.d(TAG, "Expected flattened: ${expectedComponentName.flattenToString()}")

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        Log.d(TAG, "Enabled services string: $enabledServices")

        if (enabledServices.isNullOrEmpty()) {
            Log.d(TAG, "No enabled services found")
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            Log.d(TAG, "Checking component: $componentNameString")
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            Log.d(TAG, "Parsed component: $enabledComponent")
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                Log.d(TAG, "MATCH FOUND!")
                return true
            }
        }

        Log.d(TAG, "No match found")
        return false
    }

    /**
     * Відкриває налаштування Accessibility Services.
     *
     * @param context контекст для запуску Intent
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Перевіряє чи надано дозвіл на показ сповіщень.
     * Для Android 13+ (API 33) перевіряє POST_NOTIFICATIONS permission.
     * Для старіших версій повертає true (дозвіл не потрібен).
     *
     * @param context контекст додатку
     * @return true якщо дозвіл надано або не потрібен, false якщо не надано
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Перевіряє чи потрібно запитувати дозвіл на сповіщення.
     * Повертає true тільки для Android 13+ якщо дозвіл не надано.
     *
     * @param context контекст додатку
     * @return true якщо потрібно запитати дозвіл
     */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isNotificationPermissionGranted(context)
    }
}
