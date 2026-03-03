package com.skeddy.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.skeddy.accessibility.SkeddyAccessibilityService

/**
 * Utility object для моніторингу та запуску Lyft Driver додатку.
 *
 * Забезпечує:
 * - Перевірку чи Lyft Driver встановлений
 * - Перевірку чи Lyft Driver у foreground
 * - Запуск Lyft Driver через Intent
 *
 * Працює незалежно від AccessibilityService, тому може використовуватись
 * для початкових перевірок перед активацією моніторингу.
 *
 * Для Android 11+ потрібен або QUERY_ALL_PACKAGES permission,
 * або декларація в <queries> блоці manifest для конкретного пакету.
 */
object LyftAppMonitor {
    private const val TAG = "LyftAppMonitor"

    /** Package name для Lyft Driver додатку */
    const val LYFT_DRIVER_PACKAGE = "com.lyft.android.driver"

    /**
     * Результат спроби запуску Lyft Driver.
     */
    sealed class LaunchResult {
        /** Успішно запущено */
        object Success : LaunchResult()

        /** Додаток не встановлений */
        object AppNotInstalled : LaunchResult()

        /** Не вдалось отримати launch intent */
        object NoLaunchIntent : LaunchResult()

        /** Виникла помилка при запуску */
        data class Error(val exception: Exception) : LaunchResult()
    }

    /**
     * Перевіряє чи Lyft Driver додаток встановлений на пристрої.
     *
     * Використовує PackageManager.getPackageInfo() для перевірки.
     * На Android 11+ потребує декларації пакету в <queries> блоці manifest.
     *
     * @param context Application context
     * @return true якщо Lyft Driver встановлений, false якщо ні
     */
    fun isLyftInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    LYFT_DRIVER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(LYFT_DRIVER_PACKAGE, 0)
            }
            Log.d(TAG, "isLyftInstalled: Lyft Driver is installed")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "isLyftInstalled: Lyft Driver is NOT installed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "isLyftInstalled: Error checking package", e)
            false
        }
    }

    /**
     * Checks whether Lyft Driver is currently in the foreground.
     *
     * Uses [SkeddyAccessibilityService.getLastForegroundPackage] as the
     * primary source (reliable on all Android versions). Falls back to
     * the deprecated [ActivityManager.getRunningTasks] when the
     * accessibility service has not reported any events yet.
     *
     * @param context Application context
     * @return true if Lyft Driver is in the foreground, false otherwise
     */
    fun isLyftInForeground(context: Context): Boolean {
        // Primary: use AccessibilityService tracked foreground package
        val accessibilityPackage = SkeddyAccessibilityService.getLastForegroundPackage()
        if (accessibilityPackage != null) {
            val isLyft = accessibilityPackage == LYFT_DRIVER_PACKAGE
            Log.d(TAG, "isLyftInForeground: Accessibility reports '$accessibilityPackage', isLyft=$isLyft")
            return isLyft
        }

        // Fallback: deprecated getRunningTasks (unreliable on Android 5+)
        Log.d(TAG, "isLyftInForeground: No accessibility data, falling back to getRunningTasks")
        return isLyftInForegroundLegacy(context)
    }

    @Suppress("DEPRECATION")
    private fun isLyftInForegroundLegacy(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNullOrEmpty()) {
                Log.d(TAG, "isLyftInForegroundLegacy: No running tasks found")
                return false
            }

            val topPackage = runningTasks[0].topActivity?.packageName
            val isLyftOnTop = topPackage == LYFT_DRIVER_PACKAGE
            Log.d(TAG, "isLyftInForegroundLegacy: Top package is '$topPackage', isLyft=$isLyftOnTop")
            isLyftOnTop
        } catch (e: SecurityException) {
            Log.w(TAG, "isLyftInForegroundLegacy: SecurityException - permission denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "isLyftInForegroundLegacy: Error checking foreground app", e)
            false
        }
    }

    /**
     * Запускає Lyft Driver додаток.
     *
     * Отримує launch intent через PackageManager та запускає Activity.
     * Додає FLAG_ACTIVITY_NEW_TASK для запуску з non-Activity context.
     *
     * @param context Application context
     * @return LaunchResult що вказує на результат операції
     */
    fun launchLyftDriver(context: Context): LaunchResult {
        Log.d(TAG, "launchLyftDriver: Attempting to launch Lyft Driver...")

        // Спочатку перевіряємо чи додаток встановлений
        if (!isLyftInstalled(context)) {
            Log.w(TAG, "launchLyftDriver: Lyft Driver is not installed")
            return LaunchResult.AppNotInstalled
        }

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(LYFT_DRIVER_PACKAGE)

            if (launchIntent == null) {
                Log.e(TAG, "launchLyftDriver: No launch intent available for $LYFT_DRIVER_PACKAGE")
                return LaunchResult.NoLaunchIntent
            }

            // Налаштовуємо intent для запуску з будь-якого контексту
            launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            context.startActivity(launchIntent)
            Log.i(TAG, "launchLyftDriver: Successfully launched Lyft Driver")
            LaunchResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "launchLyftDriver: Failed to launch Lyft Driver", e)
            LaunchResult.Error(e)
        }
    }

    /**
     * Перевіряє чи Lyft Driver активний, і запускає його якщо ні.
     *
     * Комбінує isLyftInForeground() та launchLyftDriver() для зручності.
     *
     * @param context Application context
     * @return true якщо Lyft вже активний або успішно запущений,
     *         false якщо не вдалось запустити
     */
    fun ensureLyftRunning(context: Context): Boolean {
        Log.d(TAG, "ensureLyftRunning: Checking Lyft state...")

        // Якщо вже активний - нічого робити не потрібно
        if (isLyftInForeground(context)) {
            Log.d(TAG, "ensureLyftRunning: Lyft already in foreground")
            return true
        }

        // Спробуємо запустити
        return when (val result = launchLyftDriver(context)) {
            is LaunchResult.Success -> {
                Log.i(TAG, "ensureLyftRunning: Lyft launched successfully")
                true
            }
            is LaunchResult.AppNotInstalled -> {
                Log.e(TAG, "ensureLyftRunning: Lyft is not installed")
                false
            }
            is LaunchResult.NoLaunchIntent -> {
                Log.e(TAG, "ensureLyftRunning: Cannot get launch intent")
                false
            }
            is LaunchResult.Error -> {
                Log.e(TAG, "ensureLyftRunning: Launch failed with error", result.exception)
                false
            }
        }
    }

    /**
     * Отримує версію встановленого Lyft Driver.
     *
     * @param context Application context
     * @return Версія додатку як string, або null якщо не встановлений
     */
    fun getLyftVersion(context: Context): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    LYFT_DRIVER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(LYFT_DRIVER_PACKAGE, 0)
            }
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
