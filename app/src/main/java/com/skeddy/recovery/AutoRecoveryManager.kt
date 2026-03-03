package com.skeddy.recovery

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.error.SkeddyError
import com.skeddy.logging.SkeddyLogger
import com.skeddy.navigation.LyftNavigator
import com.skeddy.navigation.LyftScreen
import com.skeddy.navigation.LyftUIElements
import com.skeddy.util.LyftAppMonitor
import com.skeddy.util.RetryConfig
import com.skeddy.util.RetryHelper
import com.skeddy.util.RetryResult
import kotlinx.coroutines.delay

/**
 * Менеджер автоматичного відновлення для моніторингу Lyft.
 *
 * Відповідає за:
 * - Перевірку та відкриття Lyft додатку
 * - Повернення на головний екран з будь-якого стану
 * - Підготовку до циклу моніторингу
 *
 * Використовує RetryResult для повернення результатів з детальною інформацією про помилки.
 *
 * @param context Application context для запуску Intent
 * @param accessibilityService instance SkeddyAccessibilityService для взаємодії з UI
 */
class AutoRecoveryManager(
    private val context: Context,
    private val accessibilityService: SkeddyAccessibilityService
) {
    companion object {
        private const val TAG = "AutoRecoveryManager"

        /** Максимальна кількість спроб navigateBack для повернення на main screen */
        private const val MAX_BACK_ATTEMPTS = 3

        /** Timeout очікування завантаження Lyft після запуску (ms) */
        private const val LYFT_LAUNCH_TIMEOUT = 5000L

        /** Інтервал перевірки при очікуванні завантаження Lyft (ms) */
        private const val LYFT_CHECK_INTERVAL = 500L

        /** Затримка між спробами navigateBack (ms) */
        private const val BACK_NAVIGATION_DELAY = 500L

        /**
         * Створює AutoRecoveryManager з поточним instance SkeddyAccessibilityService.
         *
         * @param context Application context
         * @return AutoRecoveryManager або null якщо сервіс не активний
         */
        fun create(context: Context): AutoRecoveryManager? {
            val service = SkeddyAccessibilityService.getInstance()
            return if (service != null) {
                AutoRecoveryManager(context, service)
            } else {
                SkeddyLogger.w(TAG, "create: SkeddyAccessibilityService not available")
                null
            }
        }
    }

    private val navigator = LyftNavigator(accessibilityService)
    private val systemDialogHandler = SystemDialogHandler(accessibilityService)

    /**
     * Перевіряє чи Lyft Driver у foreground і відкриває його якщо потрібно.
     *
     * Логіка:
     * 1. Перевіряємо чи Lyft Driver зараз активний (у foreground)
     * 2. Якщо ні - відкриваємо через Intent
     * 3. Чекаємо до 5 секунд на завантаження
     *
     * @return RetryResult.Success якщо Lyft активний, RetryResult.Failure з SkeddyError якщо не вдалось
     */
    suspend fun ensureLyftActive(): RetryResult<Unit> {
        SkeddyLogger.i(TAG, "ensureLyftActive: Checking if Lyft Driver is active...")
        SkeddyLogger.currentScreenState = "CHECKING_LYFT"

        // Перевіряємо чи Lyft вже активний
        if (isLyftInForeground()) {
            SkeddyLogger.i(TAG, "ensureLyftActive: Lyft Driver is already in foreground")
            return RetryResult.Success(Unit)
        }

        SkeddyLogger.i(TAG, "ensureLyftActive: Lyft Driver not in foreground, launching...")

        // Спробуємо відкрити Lyft
        val launched = launchLyftDriver()
        if (!launched) {
            SkeddyLogger.e(TAG, "ensureLyftActive: Failed to launch Lyft Driver - app not found")
            return RetryResult.Failure(
                error = SkeddyError.LyftAppNotFound,
                attempts = 1
            )
        }

        // Чекаємо на завантаження
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < LYFT_LAUNCH_TIMEOUT) {
            delay(LYFT_CHECK_INTERVAL)

            if (isLyftInForeground()) {
                val elapsedTime = System.currentTimeMillis() - startTime
                SkeddyLogger.i(TAG, "ensureLyftActive: Lyft Driver loaded after ${elapsedTime}ms")
                SkeddyLogger.currentScreenState = "LYFT_ACTIVE"
                return RetryResult.Success(Unit)
            }

            SkeddyLogger.d(TAG, "ensureLyftActive: Still waiting for Lyft to load...")
        }

        SkeddyLogger.e(TAG, "ensureLyftActive: Timeout waiting for Lyft Driver to load")
        return RetryResult.Failure(
            error = SkeddyError.ScreenTimeout("LYFT_DRIVER", LYFT_LAUNCH_TIMEOUT),
            attempts = 1
        )
    }

    /**
     * Забезпечує перехід на головний екран Lyft Driver.
     *
     * Логіка:
     * 1. Перевіряємо поточний екран
     * 2. Якщо вже на MAIN_SCREEN - успіх
     * 3. Якщо ні - виконуємо navigateBack() до 3 разів
     *
     * @return RetryResult.Success якщо на main screen, RetryResult.Failure якщо не вдалось
     */
    suspend fun ensureOnMainScreen(): RetryResult<Unit> {
        SkeddyLogger.i(TAG, "ensureOnMainScreen: Checking current screen...")

        val currentScreen = navigator.detectCurrentScreen()
        SkeddyLogger.currentScreenState = currentScreen.name
        SkeddyLogger.d(TAG, "ensureOnMainScreen: Current screen is $currentScreen")

        // Якщо вже на головному екрані - успіх
        if (currentScreen == LyftScreen.MAIN_SCREEN) {
            SkeddyLogger.i(TAG, "ensureOnMainScreen: Already on MAIN_SCREEN")
            return RetryResult.Success(Unit)
        }

        // Якщо UNKNOWN - можливо Lyft не активний, пробуємо все одно
        if (currentScreen == LyftScreen.UNKNOWN) {
            SkeddyLogger.w(TAG, "ensureOnMainScreen: Screen is UNKNOWN, attempting recovery anyway")
        }

        // Пробуємо повернутись на main screen через navigateBack
        SkeddyLogger.i(TAG, "ensureOnMainScreen: Attempting to navigate back to main screen...")

        repeat(MAX_BACK_ATTEMPTS) { attempt ->
            val attemptNumber = attempt + 1
            SkeddyLogger.d(TAG, "ensureOnMainScreen: Back navigation attempt #$attemptNumber of $MAX_BACK_ATTEMPTS")

            // Виконуємо navigateBack
            val backResult = navigator.navigateBack()
            if (!backResult) {
                SkeddyLogger.w(TAG, "ensureOnMainScreen: navigateBack() returned false on attempt #$attemptNumber")
            }

            // Чекаємо на оновлення UI
            delay(BACK_NAVIGATION_DELAY)

            // Перевіряємо результат
            val newScreen = navigator.detectCurrentScreen()
            SkeddyLogger.currentScreenState = newScreen.name
            SkeddyLogger.d(TAG, "ensureOnMainScreen: After attempt #$attemptNumber, screen is $newScreen")

            if (newScreen == LyftScreen.MAIN_SCREEN) {
                SkeddyLogger.i(TAG, "ensureOnMainScreen: SUCCESS - reached MAIN_SCREEN after $attemptNumber attempts")
                return RetryResult.Success(Unit)
            }
        }

        // Всі спроби вичерпані
        val finalScreen = navigator.detectCurrentScreen()
        SkeddyLogger.e(TAG, "ensureOnMainScreen: FAILED after $MAX_BACK_ATTEMPTS attempts. Final screen: $finalScreen")

        return RetryResult.Failure(
            error = SkeddyError.NavigationFailed,
            attempts = MAX_BACK_ATTEMPTS
        )
    }

    /**
     * Перевіряє та закриває системний діалог якщо присутній.
     *
     * Системні діалоги (permission requests, battery warnings, etc.) можуть блокувати
     * взаємодію з Lyft UI. Цей метод намагається їх автоматично закрити.
     *
     * @return RetryResult.Success якщо немає діалогу або він закритий, RetryResult.Failure якщо не вдалось
     */
    suspend fun ensureNoSystemDialog(): RetryResult<Unit> {
        SkeddyLogger.i(TAG, "ensureNoSystemDialog: Checking for system dialogs...")

        if (!systemDialogHandler.isSystemDialogPresent()) {
            SkeddyLogger.d(TAG, "ensureNoSystemDialog: No system dialog detected")
            return RetryResult.Success(Unit)
        }

        // Логуємо інформацію про діалог
        val dialogInfo = systemDialogHandler.getSystemDialogInfo()
        SkeddyLogger.w(TAG, "ensureNoSystemDialog: System dialog detected: $dialogInfo")

        // Пробуємо закрити
        val dismissed = systemDialogHandler.ensureNoSystemDialog()
        return if (dismissed) {
            SkeddyLogger.i(TAG, "ensureNoSystemDialog: System dialog dismissed successfully")
            RetryResult.Success(Unit)
        } else {
            SkeddyLogger.e(TAG, "ensureNoSystemDialog: Failed to dismiss system dialog")
            RetryResult.Failure(
                error = SkeddyError.SystemDialogBlocking,
                attempts = 1
            )
        }
    }

    /**
     * Підготовка до циклу моніторингу.
     *
     * Виконує послідовно:
     * 1. ensureNoSystemDialog() - закриває системні діалоги якщо є
     * 2. ensureLyftActive() - перевіряє/відкриває Lyft
     * 3. ensureOnMainScreen() - повертає на головний екран
     *
     * @return RetryResult.Success якщо готовий до моніторингу, RetryResult.Failure з першою помилкою
     */
    suspend fun prepareForMonitoring(): RetryResult<Unit> {
        SkeddyLogger.i(TAG, "prepareForMonitoring: Starting preparation for monitoring cycle...")

        // Крок 1: Закриваємо системні діалоги якщо є
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 1 - Checking for system dialogs...")
        val dialogResult = ensureNoSystemDialog()
        if (dialogResult.isFailure) {
            SkeddyLogger.e(TAG, "prepareForMonitoring: FAILED at Step 1 - System dialog blocking")
            return dialogResult
        }
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 1 completed - No system dialogs blocking")

        // Крок 2: Переконуємось що Lyft активний
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 2 - Ensuring Lyft is active...")
        val lyftResult = ensureLyftActive()
        if (lyftResult.isFailure) {
            SkeddyLogger.e(TAG, "prepareForMonitoring: FAILED at Step 2 - Lyft not active")
            return lyftResult
        }
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 2 completed - Lyft is active")

        // Невелика затримка для стабілізації після можливого запуску Lyft
        delay(500)

        // Крок 3: Переконуємось що на головному екрані
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 3 - Ensuring on main screen...")
        val mainScreenResult = ensureOnMainScreen()
        if (mainScreenResult.isFailure) {
            SkeddyLogger.e(TAG, "prepareForMonitoring: FAILED at Step 3 - Not on main screen")
            return mainScreenResult
        }
        SkeddyLogger.d(TAG, "prepareForMonitoring: Step 3 completed - on main screen")

        SkeddyLogger.i(TAG, "prepareForMonitoring: SUCCESS - ready for monitoring")
        return RetryResult.Success(Unit)
    }

    /**
     * Перевіряє чи Lyft Driver зараз у foreground.
     *
     * Використовує комбінацію методів для надійної перевірки:
     * 1. LyftAppMonitor.isLyftInForeground() - через ActivityManager
     * 2. AccessibilityService.captureLyftUIHierarchy() - через UI hierarchy
     * 3. AccessibilityService.windows - через список вікон
     *
     * @return true якщо Lyft Driver активний, false якщо ні
     */
    private fun isLyftInForeground(): Boolean {
        // Спосіб 1: Через LyftAppMonitor (ActivityManager)
        if (LyftAppMonitor.isLyftInForeground(context)) {
            SkeddyLogger.d(TAG, "isLyftInForeground: Detected via LyftAppMonitor (ActivityManager)")
            return true
        }

        // Спосіб 2: Через AccessibilityService - перевіряємо чи є вікно Lyft
        val lyftHierarchy = accessibilityService.captureLyftUIHierarchy()
        if (lyftHierarchy != null) {
            SkeddyLogger.d(TAG, "isLyftInForeground: Lyft UI hierarchy found via AccessibilityService")
            return true
        }

        // Спосіб 3: Перевіряємо windows через AccessibilityService
        val windows = accessibilityService.windows
        for (window in windows) {
            val packageName = window.root?.packageName?.toString()
            if (packageName == LyftUIElements.LYFT_DRIVER_PACKAGE) {
                SkeddyLogger.d(TAG, "isLyftInForeground: Found Lyft window in windows list")
                return true
            }
        }

        SkeddyLogger.d(TAG, "isLyftInForeground: Lyft not found in foreground")
        return false
    }

    /**
     * Відкриває Lyft Driver додаток через Intent.
     *
     * Делегує запуск до LyftAppMonitor для уникнення дублювання коду.
     *
     * @return true якщо Intent відправлено успішно, false якщо додаток не знайдено
     */
    private fun launchLyftDriver(): Boolean {
        val result = LyftAppMonitor.launchLyftDriver(context)

        return when (result) {
            is LyftAppMonitor.LaunchResult.Success -> {
                SkeddyLogger.i(TAG, "launchLyftDriver: Successfully launched via LyftAppMonitor")
                true
            }
            is LyftAppMonitor.LaunchResult.AppNotInstalled -> {
                SkeddyLogger.e(TAG, "launchLyftDriver: Lyft Driver not installed")
                false
            }
            is LyftAppMonitor.LaunchResult.NoLaunchIntent -> {
                SkeddyLogger.e(TAG, "launchLyftDriver: No launch intent available")
                false
            }
            is LyftAppMonitor.LaunchResult.Error -> {
                SkeddyLogger.e(TAG, "launchLyftDriver: Launch failed with error", result.exception)
                false
            }
        }
    }

    /**
     * Перевіряє чи Lyft Driver встановлений на пристрої.
     *
     * @return true якщо встановлений, false якщо ні
     */
    fun isLyftInstalled(): Boolean {
        return LyftAppMonitor.isLyftInstalled(context)
    }
}
