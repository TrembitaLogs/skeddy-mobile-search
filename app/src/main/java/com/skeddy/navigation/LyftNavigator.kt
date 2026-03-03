package com.skeddy.navigation

import android.util.Log
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.error.SkeddyError
import com.skeddy.logging.SkeddyLogger
import com.skeddy.util.RetryConfig
import com.skeddy.util.RetryHelper
import com.skeddy.util.RetryResult
import kotlinx.coroutines.delay

/**
 * Navigator для автоматичної взаємодії з Lyft Driver UI.
 *
 * Призначення:
 * - Визначати поточний екран Lyft Driver
 * - Автоматично натискати кнопки в Lyft додатку
 * - Навігувати до потрібних екранів (scheduled rides, ride details)
 * - Виконувати swipe/scroll дії для перегляду райдів
 *
 * @param accessibilityService instance SkeddyAccessibilityService для взаємодії з UI
 */
class LyftNavigator(
    private val accessibilityService: SkeddyAccessibilityService
) {
    companion object {
        private const val TAG = "LyftNavigator"

        /**
         * Створює LyftNavigator з поточним instance SkeddyAccessibilityService.
         * @return LyftNavigator або null якщо сервіс не активний
         */
        fun create(): LyftNavigator? {
            val service = SkeddyAccessibilityService.getInstance()
            return if (service != null) {
                LyftNavigator(service)
            } else {
                Log.w(TAG, "create: SkeddyAccessibilityService not available")
                null
            }
        }
    }

    // ==================== Screen Detection ====================

    /**
     * Визначає поточний екран Lyft Driver на основі наявності UI елементів.
     *
     * Логіка визначення:
     * 1. MAIN_SCREEN - видно hamburger menu button, немає пункту scheduled rides в меню
     * 2. SIDE_MENU - видно пункт меню "schedule" як clickable елемент
     * 3. SCHEDULED_RIDES - видно список ride cards або заголовок "Scheduled Rides"
     * 4. RIDE_DETAILS - видно детальну інформацію про одну поїздку
     * 5. UNKNOWN - не вдалося визначити екран
     *
     * @return LyftScreen що відповідає поточному стану UI
     */
    fun detectCurrentScreen(): LyftScreen {
        Log.d(TAG, "detectCurrentScreen: Starting screen detection...")

        // Перевіряємо в порядку специфічності (від найбільш специфічного до загального)
        // ВАЖЛИВО:
        // 1. SIDE_MENU перевіряється рано, бо в side menu є пункт "Scheduled Rides"
        // 2. RIDE_DETAILS перевіряється ПЕРЕД SCHEDULED_RIDES, бо:
        //    - RIDE_DETAILS має унікальні кнопки: Reserve, Dismiss, Accept, Claim
        //    - Коли відкриті деталі райду, нижній шар SCHEDULED_RIDES все ще видимий
        //    - ride_info element присутній на ОБОХ екранах
        // 3. MAIN_SCREEN перевіряється останнім серед "відомих" екранів
        return when {
            isSideMenu() -> {
                Log.i(TAG, "detectCurrentScreen: Detected SIDE_MENU")
                LyftScreen.SIDE_MENU
            }
            isRideDetailsScreen() -> {
                Log.i(TAG, "detectCurrentScreen: Detected RIDE_DETAILS")
                LyftScreen.RIDE_DETAILS
            }
            isScheduledRidesScreen() -> {
                Log.i(TAG, "detectCurrentScreen: Detected SCHEDULED_RIDES")
                LyftScreen.SCHEDULED_RIDES
            }
            isMainScreen() -> {
                Log.i(TAG, "detectCurrentScreen: Detected MAIN_SCREEN")
                LyftScreen.MAIN_SCREEN
            }
            else -> {
                Log.w(TAG, "detectCurrentScreen: Could not determine screen, returning UNKNOWN")
                LyftScreen.UNKNOWN
            }
        }
    }

    /**
     * Перевіряє чи це головний екран Lyft Driver.
     *
     * Критерії:
     * - Видно кнопку "Open menu" (hamburger button)
     * - НЕ видно пункт меню "schedule" (меню закрите)
     *
     * @return true якщо це головний екран
     */
    private fun isMainScreen(): Boolean {
        Log.d(TAG, "isMainScreen: Checking for main screen...")

        // Спочатку шукаємо кнопку меню по contentDescription (більш надійно)
        // Використовуємо findLyftNode* методи для пошуку саме у вікні Lyft
        var menuButton = accessibilityService.findLyftNodeByContentDesc(
            LyftUIElements.CONTENT_DESC_OPEN_MENU,
            exactMatch = true
        )

        // Fallback: шукаємо по resource-id
        if (menuButton == null) {
            menuButton = accessibilityService.findLyftNodeById(LyftUIElements.RES_MENU_BUTTON)
        }

        if (menuButton == null) {
            Log.d(TAG, "isMainScreen: Menu button not found by contentDesc or resource-id")
            return false
        }

        Log.d(TAG, "isMainScreen: Menu button found, checking if menu is closed...")

        // Перевіряємо що меню закрите (немає пункту schedule)
        val scheduleMenuItem = accessibilityService.findLyftNodeById(LyftUIElements.RES_SCHEDULED_RIDES)
        if (scheduleMenuItem != null) {
            Log.d(TAG, "isMainScreen: Schedule menu item visible - menu is open")
            return false
        }

        // Додаткова перевірка - шукаємо текст "schedule" в меню
        val scheduleText = accessibilityService.findLyftNodeByText("schedule", exactMatch = false)
        if (scheduleText != null && scheduleText.isClickable) {
            Log.d(TAG, "isMainScreen: Clickable 'schedule' text found - menu is open")
            return false
        }

        Log.d(TAG, "isMainScreen: Confirmed main screen")
        return true
    }

    /**
     * Перевіряє чи відкрите бічне меню.
     *
     * Критерії:
     * - Є елемент з resource-id="schedule" (пункт меню Scheduled Rides)
     * - Цей елемент або його parent є clickable (це пункт меню, не заголовок екрану)
     *
     * @return true якщо бічне меню відкрите
     */
    private fun isSideMenu(): Boolean {
        Log.d(TAG, "isSideMenu: Checking for side menu...")

        // Шукаємо пункт меню по resource-id="schedule"
        // Це специфічний ідентифікатор пункту меню в бічному меню Lyft
        val scheduleMenuItem = accessibilityService.findLyftNodeById(LyftUIElements.RES_SCHEDULED_RIDES)
        if (scheduleMenuItem != null) {
            Log.d(TAG, "isSideMenu: Found element with resource-id='schedule'")

            // Перевіряємо що це пункт меню (clickable сам або parent)
            if (scheduleMenuItem.isClickable) {
                Log.d(TAG, "isSideMenu: Element is clickable - confirmed side menu")
                return true
            }

            // Перевіряємо parent на clickable
            val parent = scheduleMenuItem.parent
            if (parent != null && parent.isClickable) {
                Log.d(TAG, "isSideMenu: Parent is clickable - confirmed side menu")
                return true
            }

            // Навіть якщо не clickable, наявність resource-id="schedule" означає бічне меню
            // бо на екрані Scheduled Rides заголовок не має цього resource-id
            Log.d(TAG, "isSideMenu: Found schedule menu item (resource-id based)")
            return true
        }

        Log.d(TAG, "isSideMenu: Side menu not detected (no resource-id='schedule' found)")
        return false
    }

    /**
     * Перевіряє чи це екран списку запланованих поїздок.
     *
     * Критерії (в порядку надійності):
     * 1. Шукаємо унікальні елементи екрану: "Your rides" або "Available rides"
     * 2. Шукаємо ride_info resource-id (картки поїздок)
     * 3. Шукаємо ТОЧНИЙ заголовок "Scheduled Rides" (exactMatch=true)
     *
     * УВАГА: Не використовуємо exactMatch=false для "Scheduled Rides",
     * бо на головному екрані є текст "Plan ahead with scheduled rides"
     *
     * @return true якщо це екран Scheduled Rides
     */
    private fun isScheduledRidesScreen(): Boolean {
        Log.d(TAG, "isScheduledRidesScreen: Checking for scheduled rides screen...")

        // Перевірка 1: Шукаємо унікальні секції екрану Scheduled Rides
        val yourRides = accessibilityService.findLyftNodeByText("Your rides", exactMatch = false)
        if (yourRides != null) {
            Log.d(TAG, "isScheduledRidesScreen: Found 'Your rides' section - confirmed")
            return true
        }

        val availableRides = accessibilityService.findLyftNodeByText("Available rides", exactMatch = false)
        if (availableRides != null) {
            Log.d(TAG, "isScheduledRidesScreen: Found 'Available rides' section - confirmed")
            return true
        }

        // Перевірка 2: Шукаємо ride_info resource-id (картки поїздок на цьому екрані)
        val rideInfo = accessibilityService.findLyftNodeById("ride_info")
        if (rideInfo != null) {
            Log.d(TAG, "isScheduledRidesScreen: Found 'ride_info' element - confirmed")
            return true
        }

        // Перевірка 3: Шукаємо ride cards по старому resource-id
        val rideCards = accessibilityService.findAllLyftNodesById(LyftUIElements.RES_RIDE_CARD)
        if (rideCards.isNotEmpty()) {
            Log.d(TAG, "isScheduledRidesScreen: Found ${rideCards.size} ride cards")
            return true
        }

        // Перевірка 4: Шукаємо ТОЧНИЙ заголовок "Scheduled Rides"
        // (не використовуємо partial match, бо на main screen є "Plan ahead with scheduled rides")
        val headerTexts = listOf("Scheduled Rides", "Scheduled rides", "SCHEDULED RIDES")
        for (headerText in headerTexts) {
            val header = accessibilityService.findLyftNodeByText(headerText, exactMatch = true)
            if (header != null) {
                Log.d(TAG, "isScheduledRidesScreen: Found exact header '$headerText'")
                return true
            }
        }

        Log.d(TAG, "isScheduledRidesScreen: Scheduled rides screen not detected")
        return false
    }

    /**
     * Перевіряє чи це екран деталей поїздки.
     *
     * Критерії:
     * - Видно детальну інформацію про одну поїздку
     * - Наприклад: повна адреса pickup/dropoff, час, ціна, кнопка Cancel
     *
     * @return true якщо це екран деталей поїздки
     */
    private fun isRideDetailsScreen(): Boolean {
        Log.d(TAG, "isRideDetailsScreen: Checking for ride details screen...")

        // DEBUG: Вивести всі текстові ноди для діагностики
        val allTextNodes = accessibilityService.collectAllLyftTextNodes()
        if (allTextNodes.isEmpty()) {
            Log.w(TAG, "isRideDetailsScreen: DEBUG - No text nodes found in Lyft window (captureLyftUIHierarchy might be null)")
        } else {
            Log.d(TAG, "isRideDetailsScreen: DEBUG - Found ${allTextNodes.size} text nodes:")
            allTextNodes.take(20).forEach { text ->
                Log.d(TAG, "  - '$text'")
            }
            if (allTextNodes.size > 20) {
                Log.d(TAG, "  ... and ${allTextNodes.size - 20} more")
            }
        }

        // ОСНОВНА ДЕТЕКЦІЯ: шукаємо унікальні елементи екрану деталей райду
        // На основі реального UI dump від 2025-01-09:
        // - "Reserve" кнопка (для Available rides)
        // - "Dismiss" кнопка (для закриття деталей)
        // - "Cancel ride" / "Cancel scheduled ride" (для вже зарезервованих)

        // Перевірка 1: Кнопка "Reserve" - унікальна для RIDE_DETAILS available rides
        val reserveButton = accessibilityService.findLyftNodeByText("Reserve", exactMatch = true)
        if (reserveButton != null) {
            Log.d(TAG, "isRideDetailsScreen: Found 'Reserve' button - confirmed RIDE_DETAILS")
            return true
        }

        // Перевірка 2: Кнопка "Dismiss" - характерна для RIDE_DETAILS
        val dismissButton = accessibilityService.findLyftNodeByText("Dismiss", exactMatch = true)
        if (dismissButton != null) {
            Log.d(TAG, "isRideDetailsScreen: Found 'Dismiss' button - confirmed RIDE_DETAILS")
            return true
        }

        // Перевірка 3: Cancel кнопки для вже зарезервованих райдів
        val cancelRideButton = accessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false)
        val cancelScheduledRide = accessibilityService.findLyftNodeByText("Cancel scheduled ride", exactMatch = false)
        if (cancelRideButton != null || cancelScheduledRide != null) {
            Log.d(TAG, "isRideDetailsScreen: Found cancel button - confirmed RIDE_DETAILS (reserved)")
            return true
        }

        // Перевірка 4: Accept/Claim кнопки (альтернативні назви)
        val acceptButton = accessibilityService.findLyftNodeByText("Accept", exactMatch = true)
        val claimButton = accessibilityService.findLyftNodeByText("Claim", exactMatch = true)
        if (acceptButton != null || claimButton != null) {
            Log.d(TAG, "isRideDetailsScreen: Found Accept/Claim button - confirmed RIDE_DETAILS")
            return true
        }

        // Перевірка 5: Кнопка "Close" (contentDescription) - для reserved ride details
        val closeButton = accessibilityService.findLyftNodeByContentDescription("Close")
        if (closeButton != null) {
            Log.d(TAG, "isRideDetailsScreen: Found 'Close' button - confirmed RIDE_DETAILS (reserved)")
            return true
        }

        // Перевірка 6: Текст "How to complete your ride" - унікальний для reserved ride details
        val howToCompleteText = accessibilityService.findLyftNodeByText("How to complete your ride", exactMatch = false)
        if (howToCompleteText != null) {
            Log.d(TAG, "isRideDetailsScreen: Found 'How to complete your ride' - confirmed RIDE_DETAILS (reserved)")
            return true
        }

        // НЕ використовуємо fallback з "min •" та рейтингом - вони є і на SCHEDULED_RIDES!
        // Покладаємося тільки на унікальні кнопки: Reserve, Dismiss, Cancel, Accept, Claim, Close

        Log.d(TAG, "isRideDetailsScreen: RIDE_DETAILS screen not detected (no unique buttons found)")
        return false
    }

    // ==================== Navigation Methods ====================

    /**
     * Відкриває бічне меню (hamburger menu) з головного екрану.
     *
     * Стратегія:
     * 1. Спершу шукаємо кнопку по contentDescription="Open menu"
     * 2. Fallback: шукаємо по resource-id="side_menu_btn"
     * 3. Фінальний fallback: клік по координатах MENU_BUTTON_CENTER
     *
     * @return true якщо клік виконано успішно, false якщо не вдалося
     */
    fun navigateToMenu(): Boolean {
        Log.d(TAG, "navigateToMenu: Attempting to open side menu...")

        // Спроба 1: Пошук по contentDescription
        val menuByContentDesc = accessibilityService.findLyftNodeByContentDesc(
            LyftUIElements.CONTENT_DESC_OPEN_MENU,
            exactMatch = true
        )
        if (menuByContentDesc != null) {
            Log.d(TAG, "navigateToMenu: Found menu button by contentDescription='${LyftUIElements.CONTENT_DESC_OPEN_MENU}'")
            val result = accessibilityService.performClickOnNode(menuByContentDesc)
            if (result) {
                Log.i(TAG, "navigateToMenu: SUCCESS via contentDescription click")
                return true
            }
            Log.w(TAG, "navigateToMenu: contentDescription node found but click failed, trying next method...")
        }

        // Спроба 2: Пошук по resource-id
        val menuById = accessibilityService.findLyftNodeById(LyftUIElements.RES_MENU_BUTTON)
        if (menuById != null) {
            Log.d(TAG, "navigateToMenu: Found menu button by resource-id='${LyftUIElements.RES_MENU_BUTTON}'")
            val result = accessibilityService.performClickOnNode(menuById)
            if (result) {
                Log.i(TAG, "navigateToMenu: SUCCESS via resource-id click")
                return true
            }
            Log.w(TAG, "navigateToMenu: resource-id node found but click failed, trying coordinates fallback...")
        }

        // Спроба 3: Fallback на координати
        Log.d(TAG, "navigateToMenu: Using coordinates fallback at ${LyftUIElements.MENU_BUTTON_CENTER}")
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.MENU_BUTTON_CENTER, screen.x, screen.y)
        val result = accessibilityService.performClick(point.x, point.y)
        if (result) {
            Log.i(TAG, "navigateToMenu: SUCCESS via coordinates fallback ($point)")
        } else {
            Log.e(TAG, "navigateToMenu: FAILED - all methods exhausted")
        }
        return result
    }

    /**
     * Переходить до екрану Scheduled Rides з бічного меню.
     *
     * Передумова: бічне меню має бути відкрите (SIDE_MENU screen).
     *
     * Стратегія:
     * 1. Перевіряємо що ми на SIDE_MENU
     * 2. Шукаємо пункт меню по resource-id="schedule"
     * 3. Fallback: клік по координатах SCHEDULED_RIDES_CENTER
     *
     * @return true якщо клік виконано успішно, false якщо не вдалося
     */
    fun navigateToScheduledRides(): Boolean {
        Log.d(TAG, "navigateToScheduledRides: Attempting to navigate to Scheduled Rides...")

        // Перевірка передумови: чи відкрите бічне меню
        val currentScreen = detectCurrentScreen()
        if (currentScreen != LyftScreen.SIDE_MENU) {
            Log.w(TAG, "navigateToScheduledRides: Current screen is $currentScreen, expected SIDE_MENU. Proceeding anyway...")
        }

        // Спроба 1: Пошук по resource-id
        val scheduleMenuItem = accessibilityService.findLyftNodeById(LyftUIElements.RES_SCHEDULED_RIDES)
        if (scheduleMenuItem != null) {
            Log.d(TAG, "navigateToScheduledRides: Found 'Scheduled Rides' by resource-id='${LyftUIElements.RES_SCHEDULED_RIDES}'")
            val result = accessibilityService.performClickOnNode(scheduleMenuItem)
            if (result) {
                Log.i(TAG, "navigateToScheduledRides: SUCCESS via resource-id click")
                return true
            }
            Log.w(TAG, "navigateToScheduledRides: resource-id node found but click failed, trying coordinates fallback...")
        }

        // Спроба 2: Пошук по тексту "Scheduled" (альтернатива)
        val scheduleByText = accessibilityService.findLyftNodeByText("Scheduled", exactMatch = false)
        if (scheduleByText != null && scheduleByText.isClickable) {
            Log.d(TAG, "navigateToScheduledRides: Found clickable 'Scheduled' text node")
            val result = accessibilityService.performClickOnNode(scheduleByText)
            if (result) {
                Log.i(TAG, "navigateToScheduledRides: SUCCESS via text search click")
                return true
            }
            Log.w(TAG, "navigateToScheduledRides: text node found but click failed, trying coordinates fallback...")
        }

        // Спроба 3: Fallback на координати
        Log.d(TAG, "navigateToScheduledRides: Using coordinates fallback at ${LyftUIElements.SCHEDULED_RIDES_CENTER}")
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.SCHEDULED_RIDES_CENTER, screen.x, screen.y)
        val result = accessibilityService.performClick(point.x, point.y)
        if (result) {
            Log.i(TAG, "navigateToScheduledRides: SUCCESS via coordinates fallback ($point)")
        } else {
            Log.e(TAG, "navigateToScheduledRides: FAILED - all methods exhausted")
        }
        return result
    }

    /**
     * Повертається на попередній екран (Back navigation).
     *
     * Стратегія:
     * 1. Спершу пробуємо глобальну дію GLOBAL_ACTION_BACK
     * 2. Шукаємо кнопку з contentDescription="Back" або "Navigate up"
     * 3. Фінальний fallback: клік по координатах BACK_BUTTON_CENTER
     *
     * @return true якщо навігація успішна, false якщо не вдалося
     */
    fun navigateBack(): Boolean {
        Log.d(TAG, "navigateBack: Attempting to go back...")

        // Спроба 1: Глобальна дія BACK
        val globalBackResult = accessibilityService.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        )
        if (globalBackResult) {
            Log.i(TAG, "navigateBack: SUCCESS via GLOBAL_ACTION_BACK")
            return true
        }
        Log.w(TAG, "navigateBack: GLOBAL_ACTION_BACK failed, trying to find Back button...")

        // Спроба 2: Пошук кнопки Back по contentDescription
        val backButton = accessibilityService.findLyftNodeByContentDesc("Back", exactMatch = false)
        if (backButton != null) {
            Log.d(TAG, "navigateBack: Found button with contentDescription containing 'Back'")
            val result = accessibilityService.performClickOnNode(backButton)
            if (result) {
                Log.i(TAG, "navigateBack: SUCCESS via 'Back' button click")
                return true
            }
            Log.w(TAG, "navigateBack: 'Back' button found but click failed, trying 'Navigate up'...")
        }

        // Спроба 3: Пошук кнопки "Navigate up"
        val navigateUpButton = accessibilityService.findLyftNodeByContentDesc("Navigate up", exactMatch = false)
        if (navigateUpButton != null) {
            Log.d(TAG, "navigateBack: Found button with contentDescription containing 'Navigate up'")
            val result = accessibilityService.performClickOnNode(navigateUpButton)
            if (result) {
                Log.i(TAG, "navigateBack: SUCCESS via 'Navigate up' button click")
                return true
            }
            Log.w(TAG, "navigateBack: 'Navigate up' button found but click failed, trying coordinates fallback...")
        }

        // Спроба 4: Fallback на координати
        Log.d(TAG, "navigateBack: Using coordinates fallback at ${LyftUIElements.BACK_BUTTON_CENTER}")
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.BACK_BUTTON_CENTER, screen.x, screen.y)
        val result = accessibilityService.performClick(point.x, point.y)
        if (result) {
            Log.i(TAG, "navigateBack: SUCCESS via coordinates fallback ($point)")
        } else {
            Log.e(TAG, "navigateBack: FAILED - all methods exhausted")
        }
        return result
    }

    // ==================== Wait/Polling Methods ====================

    /**
     * Очікує появи конкретного екрану з polling логікою.
     *
     * Suspend функція, яка періодично перевіряє поточний екран і повертає true,
     * коли екран відповідає очікуваному, або false якщо timeout вичерпано.
     *
     * @param targetScreen екран, який очікуємо
     * @param timeout максимальний час очікування в мілісекундах (за замовчуванням 5000ms)
     * @return true якщо екран з'явився до timeout, false якщо timeout вичерпано
     */
    suspend fun waitForScreen(
        targetScreen: LyftScreen,
        timeout: Long = LyftUIElements.SCREEN_LOAD_TIMEOUT
    ): Boolean {
        Log.d(TAG, "waitForScreen: Waiting for screen $targetScreen with timeout=${timeout}ms")

        val startTime = System.currentTimeMillis()
        var attemptCount = 0

        while (true) {
            attemptCount++
            val elapsedTime = System.currentTimeMillis() - startTime

            // Перевіряємо поточний екран
            val currentScreen = detectCurrentScreen()
            Log.d(TAG, "waitForScreen: Attempt #$attemptCount, elapsed=${elapsedTime}ms, currentScreen=$currentScreen")

            // Успіх - знайшли потрібний екран
            if (currentScreen == targetScreen) {
                Log.i(TAG, "waitForScreen: SUCCESS - $targetScreen detected after ${elapsedTime}ms ($attemptCount attempts)")
                return true
            }

            // Перевіряємо timeout
            if (elapsedTime >= timeout) {
                Log.w(TAG, "waitForScreen: TIMEOUT - $targetScreen not detected after ${timeout}ms ($attemptCount attempts). Last seen: $currentScreen")
                return false
            }

            // Чекаємо перед наступною перевіркою
            delay(LyftUIElements.POLLING_INTERVAL)
        }
    }

    /**
     * Очікує появи бічного меню після натискання hamburger кнопки.
     * Використовує коротший timeout для анімації меню.
     *
     * @return true якщо меню відкрилось, false якщо timeout
     */
    suspend fun waitForMenu(): Boolean {
        return waitForScreen(LyftScreen.SIDE_MENU, LyftUIElements.MENU_ANIMATION_TIMEOUT)
    }

    /**
     * Очікує завантаження екрану Scheduled Rides.
     *
     * @return true якщо екран завантажився, false якщо timeout
     */
    suspend fun waitForScheduledRides(): Boolean {
        return waitForScreen(LyftScreen.SCHEDULED_RIDES, LyftUIElements.SCREEN_LOAD_TIMEOUT)
    }

    /**
     * Очікує повернення на головний екран.
     *
     * @return true якщо повернулись на головний екран, false якщо timeout
     */
    suspend fun waitForMainScreen(): Boolean {
        return waitForScreen(LyftScreen.MAIN_SCREEN, LyftUIElements.MENU_ANIMATION_TIMEOUT)
    }

    // ==================== Retry Logic ====================

    /**
     * Виконує дію з retry логікою та exponential backoff.
     *
     * При невдачі чекає initialDelay * factor^attempt перед наступною спробою.
     * Затримка обмежена maxDelay.
     *
     * @param actionName назва дії для логування
     * @param maxAttempts максимальна кількість спроб (за замовчуванням 3)
     * @param initialDelay початкова затримка в мілісекундах (за замовчуванням 500)
     * @param maxDelay максимальна затримка в мілісекундах (за замовчуванням 2000)
     * @param factor множник для exponential backoff (за замовчуванням 2.0)
     * @param action suspend функція, яка повертає Boolean (true = успіх)
     * @return true якщо дія успішна хоча б на одній спробі, false якщо всі спроби невдалі
     */
    private suspend inline fun withRetry(
        actionName: String,
        maxAttempts: Int = LyftUIElements.RETRY_MAX_ATTEMPTS,
        initialDelay: Long = LyftUIElements.RETRY_INITIAL_DELAY,
        maxDelay: Long = LyftUIElements.RETRY_MAX_DELAY,
        factor: Double = LyftUIElements.RETRY_BACKOFF_FACTOR,
        action: () -> Boolean
    ): Boolean {
        var currentDelay = initialDelay

        repeat(maxAttempts) { attempt ->
            val attemptNumber = attempt + 1
            Log.d(TAG, "withRetry[$actionName]: Attempt #$attemptNumber of $maxAttempts")

            try {
                val result = action()
                if (result) {
                    Log.i(TAG, "withRetry[$actionName]: SUCCESS on attempt #$attemptNumber")
                    return true
                }
                Log.w(TAG, "withRetry[$actionName]: Attempt #$attemptNumber returned false")
            } catch (e: Exception) {
                Log.w(TAG, "withRetry[$actionName]: Attempt #$attemptNumber threw exception: ${e.message}")
            }

            // Якщо це не остання спроба - чекаємо
            if (attemptNumber < maxAttempts) {
                val delayToUse = currentDelay.coerceAtMost(maxDelay)
                Log.d(TAG, "withRetry[$actionName]: Waiting ${delayToUse}ms before next attempt...")
                delay(delayToUse)
                currentDelay = (currentDelay * factor).toLong()
            }
        }

        Log.e(TAG, "withRetry[$actionName]: FAILED after $maxAttempts attempts")
        return false
    }

    // ==================== Safe Navigation Methods (with Retry) ====================

    /**
     * Безпечно відкриває бічне меню з retry логікою.
     *
     * Виконує navigateToMenu() з exponential backoff retry.
     * При успішному кліку очікує появи SIDE_MENU екрану.
     *
     * @return true якщо меню успішно відкрите, false якщо всі спроби невдалі
     */
    suspend fun safeNavigateToMenu(): Boolean {
        Log.d(TAG, "safeNavigateToMenu: Starting safe navigation to menu...")

        // Затримка перед кліком для стабілізації UI
        Log.d(TAG, "safeNavigateToMenu: Waiting ${LyftUIElements.PRE_CLICK_DELAY}ms before click...")
        delay(LyftUIElements.PRE_CLICK_DELAY)

        val clickResult = withRetry("navigateToMenu") {
            navigateToMenu()
        }

        if (!clickResult) {
            Log.e(TAG, "safeNavigateToMenu: Failed to click menu button after retries")
            return false
        }

        // Очікуємо появи меню
        val menuAppeared = waitForMenu()
        if (!menuAppeared) {
            Log.w(TAG, "safeNavigateToMenu: Menu click succeeded but menu didn't appear")
        }
        return menuAppeared
    }

    /**
     * Безпечно переходить до Scheduled Rides з retry логікою.
     *
     * Виконує navigateToScheduledRides() з exponential backoff retry.
     * При успішному кліку очікує появи SCHEDULED_RIDES екрану.
     *
     * @return true якщо перехід успішний, false якщо всі спроби невдалі
     */
    suspend fun safeNavigateToScheduledRides(): Boolean {
        Log.d(TAG, "safeNavigateToScheduledRides: Starting safe navigation to Scheduled Rides...")

        // Затримка перед кліком для стабілізації UI
        Log.d(TAG, "safeNavigateToScheduledRides: Waiting ${LyftUIElements.PRE_CLICK_DELAY}ms before click...")
        delay(LyftUIElements.PRE_CLICK_DELAY)

        val clickResult = withRetry("navigateToScheduledRides") {
            navigateToScheduledRides()
        }

        if (!clickResult) {
            Log.e(TAG, "safeNavigateToScheduledRides: Failed to click Scheduled Rides after retries")
            return false
        }

        // Очікуємо появи екрану Scheduled Rides
        val screenAppeared = waitForScheduledRides()
        if (!screenAppeared) {
            Log.w(TAG, "safeNavigateToScheduledRides: Click succeeded but screen didn't appear")
        }
        return screenAppeared
    }

    /**
     * Безпечно повертається назад з retry логікою.
     *
     * Виконує navigateBack() з exponential backoff retry.
     *
     * @return true якщо навігація назад успішна, false якщо всі спроби невдалі
     */
    suspend fun safeNavigateBack(): Boolean {
        Log.d(TAG, "safeNavigateBack: Starting safe navigation back...")

        // Затримка перед кліком для стабілізації UI
        Log.d(TAG, "safeNavigateBack: Waiting ${LyftUIElements.PRE_CLICK_DELAY}ms before click...")
        delay(LyftUIElements.PRE_CLICK_DELAY)

        return withRetry("navigateBack") {
            navigateBack()
        }
    }

    // ==================== Combined Navigation Flows ====================

    /**
     * Повний flow навігації до екрану Scheduled Rides і повернення назад.
     *
     * Послідовність:
     * 1. navigateToMenu() з retry → waitForScreen(SIDE_MENU)
     * 2. navigateToScheduledRides() з retry → waitForScreen(SCHEDULED_RIDES)
     * 3. navigateBack() з retry → waitForScreen(MAIN_SCREEN)
     *
     * Кожен крок має retry logic з exponential backoff.
     *
     * @return true якщо весь flow успішний, false якщо будь-який крок невдалий
     */
    suspend fun navigateToScheduledRidesFlow(): Boolean {
        Log.i(TAG, "navigateToScheduledRidesFlow: Starting full navigation flow to Scheduled Rides...")

        // Перевіряємо поточний екран
        val currentScreen = detectCurrentScreen()
        Log.d(TAG, "navigateToScheduledRidesFlow: Current screen is $currentScreen")

        // Якщо вже на Scheduled Rides - успіх
        if (currentScreen == LyftScreen.SCHEDULED_RIDES) {
            Log.i(TAG, "navigateToScheduledRidesFlow: Already on SCHEDULED_RIDES screen")
            return true
        }

        // Якщо на SIDE_MENU - пропускаємо крок відкриття меню
        if (currentScreen != LyftScreen.SIDE_MENU) {
            // Крок 1: Відкриваємо меню
            Log.d(TAG, "navigateToScheduledRidesFlow: Step 1 - Opening side menu...")
            val menuResult = safeNavigateToMenu()
            if (!menuResult) {
                Log.e(TAG, "navigateToScheduledRidesFlow: FAILED at Step 1 - could not open menu")
                return false
            }
            Log.d(TAG, "navigateToScheduledRidesFlow: Step 1 completed - menu opened")
        } else {
            Log.d(TAG, "navigateToScheduledRidesFlow: Skipping Step 1 - already on SIDE_MENU")
        }

        // Крок 2: Переходимо до Scheduled Rides
        Log.d(TAG, "navigateToScheduledRidesFlow: Step 2 - Navigating to Scheduled Rides...")
        val scheduledResult = safeNavigateToScheduledRides()
        if (!scheduledResult) {
            Log.e(TAG, "navigateToScheduledRidesFlow: FAILED at Step 2 - could not navigate to Scheduled Rides")
            return false
        }

        Log.d(TAG, "navigateToScheduledRidesFlow: Step 2 completed - on Scheduled Rides screen")

        // Крок 3: Повертаємось на головний екран
        Log.d(TAG, "navigateToScheduledRidesFlow: Step 3 - Navigating back to main screen...")
        val backResult = safeNavigateBack()
        if (!backResult) {
            Log.e(TAG, "navigateToScheduledRidesFlow: FAILED at Step 3 - could not navigate back")
            return false
        }

        // Очікуємо появи головного екрану
        val mainScreenAppeared = waitForScreen(LyftScreen.MAIN_SCREEN, timeout = 3000L)
        if (!mainScreenAppeared) {
            Log.w(TAG, "navigateToScheduledRidesFlow: Back navigation succeeded but MAIN_SCREEN didn't appear")
            return false
        }

        Log.i(TAG, "navigateToScheduledRidesFlow: SUCCESS - completed full flow and returned to main screen")
        return true
    }

    // ==================== Accept Button Methods ====================

    /**
     * Знаходить кнопку Accept/Claim на екрані деталей райду.
     *
     * Стратегія пошуку (в порядку пріоритету):
     * 1. Текст "Accept" (exactMatch=true)
     * 2. Текст "Claim" (exactMatch=true)
     * 3. Текст "Accept ride" (exactMatch=false)
     * 4. Текст "Claim ride" (exactMatch=false)
     * 5. ContentDescription що містить "accept" або "claim"
     *
     * @return AccessibilityNodeInfo кнопки або null якщо не знайдено
     */
    fun detectAcceptButton(): android.view.accessibility.AccessibilityNodeInfo? {
        Log.d(TAG, "detectAcceptButton: Searching for Accept/Claim button...")

        // Пошук по тексту (в порядку пріоритету)
        // "Reserve" - для Available rides
        // "Accept"/"Claim" - для інших типів райдів
        val acceptTexts = listOf(
            Pair("Reserve", true),     // exactMatch - для Available rides
            Pair("Accept", true),      // exactMatch
            Pair("Claim", true),       // exactMatch
            Pair("Accept ride", false), // partialMatch
            Pair("Claim ride", false)   // partialMatch
        )

        for ((text, exactMatch) in acceptTexts) {
            val button = accessibilityService.findLyftNodeByText(text, exactMatch = exactMatch)
            if (button != null) {
                // Перевіряємо чи кнопка або її parent є clickable
                if (button.isClickable) {
                    Log.d(TAG, "detectAcceptButton: Found clickable button with text '$text'")
                    return button
                }

                // Перевіряємо parent на clickable
                val parent = button.parent
                if (parent != null && parent.isClickable) {
                    Log.d(TAG, "detectAcceptButton: Found button with text '$text' (parent clickable)")
                    return parent
                }

                Log.d(TAG, "detectAcceptButton: Found text '$text' but not clickable, trying next...")
            }
        }

        // Fallback: пошук по contentDescription
        val acceptContentDesc = accessibilityService.findLyftNodeByContentDesc("accept", exactMatch = false)
        if (acceptContentDesc != null) {
            if (acceptContentDesc.isClickable) {
                Log.d(TAG, "detectAcceptButton: Found button by contentDescription 'accept'")
                return acceptContentDesc
            }
            val parent = acceptContentDesc.parent
            if (parent != null && parent.isClickable) {
                Log.d(TAG, "detectAcceptButton: Found button by contentDescription 'accept' (parent clickable)")
                return parent
            }
        }

        val claimContentDesc = accessibilityService.findLyftNodeByContentDesc("claim", exactMatch = false)
        if (claimContentDesc != null) {
            if (claimContentDesc.isClickable) {
                Log.d(TAG, "detectAcceptButton: Found button by contentDescription 'claim'")
                return claimContentDesc
            }
            val parent = claimContentDesc.parent
            if (parent != null && parent.isClickable) {
                Log.d(TAG, "detectAcceptButton: Found button by contentDescription 'claim' (parent clickable)")
                return parent
            }
        }

        Log.w(TAG, "detectAcceptButton: Accept/Claim button not found")
        return null
    }

    /**
     * Натискає кнопку Accept/Claim з retry логікою.
     *
     * Послідовність:
     * 1. Затримка PRE_CLICK_DELAY для стабілізації UI
     * 2. Пошук кнопки через detectAcceptButton()
     * 3. Клік на кнопку з retry
     * 4. При невдачі - fallback на координати ACCEPT_BUTTON_CENTER
     *
     * @return true якщо клік успішний, false якщо кнопка не знайдена або клік невдалий
     */
    suspend fun clickAcceptButton(): Boolean {
        Log.d(TAG, "clickAcceptButton: Attempting to click Accept/Claim button...")

        // Затримка перед кліком для стабілізації UI
        Log.d(TAG, "clickAcceptButton: Waiting ${LyftUIElements.PRE_CLICK_DELAY}ms before click...")
        delay(LyftUIElements.PRE_CLICK_DELAY)

        return withRetry("clickAcceptButton") {
            val button = detectAcceptButton()
            if (button != null) {
                val result = accessibilityService.performClickOnNode(button)
                if (result) {
                    Log.i(TAG, "clickAcceptButton: Successfully clicked Accept/Claim button")
                    return@withRetry true
                }
                Log.w(TAG, "clickAcceptButton: Node found but click failed, trying coordinates fallback...")
            } else {
                Log.w(TAG, "clickAcceptButton: Button not found, trying coordinates fallback...")
            }

            // Fallback на координати
            val screen = accessibilityService.getScreenSize()
            val fallbackPoint = LyftUIElements.toAbsolute(LyftUIElements.ACCEPT_BUTTON_CENTER, screen.x, screen.y)
            val coordinatesResult = accessibilityService.performClick(fallbackPoint.x, fallbackPoint.y)
            if (coordinatesResult) {
                Log.i(TAG, "clickAcceptButton: Successfully clicked via coordinates ($fallbackPoint)")
            }
            coordinatesResult
        }
    }

    // ==================== Ride Card Methods ====================

    /**
     * Клікає на картку райду для переходу на екран деталей.
     *
     * Стратегія:
     * 1. Спроба кліку на node напряму
     * 2. Якщо node не clickable - шукає clickable parent
     * 3. Fallback: клік по центру bounds картки
     *
     * @param rideCard AccessibilityNodeInfo картки райду
     * @return true якщо клік успішний, false якщо клік невдалий
     */
    fun clickOnRideCard(rideCard: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        Log.d(TAG, "clickOnRideCard: Attempting to click on ride card...")

        // Спроба 1: Клік на node напряму
        if (rideCard.isClickable) {
            val result = accessibilityService.performClickOnNode(rideCard)
            if (result) {
                Log.i(TAG, "clickOnRideCard: Successfully clicked on ride card node")
                return true
            }
            Log.w(TAG, "clickOnRideCard: Node is clickable but click failed, trying parent...")
        }

        // Спроба 2: Шукаємо clickable parent
        var parent = rideCard.parent
        var parentLevel = 1
        while (parent != null && parentLevel <= 3) {
            if (parent.isClickable) {
                val result = accessibilityService.performClickOnNode(parent)
                if (result) {
                    Log.i(TAG, "clickOnRideCard: Successfully clicked on parent level $parentLevel")
                    return true
                }
                Log.w(TAG, "clickOnRideCard: Parent level $parentLevel is clickable but click failed")
            }
            parent = parent.parent
            parentLevel++
        }

        // Спроба 3: Fallback на координати через bounds
        Log.d(TAG, "clickOnRideCard: Trying coordinates fallback via bounds...")
        val bounds = android.graphics.Rect()
        rideCard.getBoundsInScreen(bounds)

        if (!bounds.isEmpty) {
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            Log.d(TAG, "clickOnRideCard: Clicking at center of bounds ($centerX, $centerY)")
            val result = accessibilityService.performClick(centerX, centerY)
            if (result) {
                Log.i(TAG, "clickOnRideCard: Successfully clicked via bounds coordinates")
                return true
            }
        }

        Log.e(TAG, "clickOnRideCard: All click methods failed")
        return false
    }

    // ==================== RetryResult Navigation Methods ====================

    /**
     * Конфігурація retry для навігаційних операцій.
     * Використовує delays: 1s, 2s, 4s (exponential backoff).
     */
    private val navigationRetryConfig = RetryConfig(
        maxAttempts = 3,
        initialDelayMs = 1000L,
        maxDelayMs = 4000L,
        factor = 2.0,
        tag = TAG
    )

    /**
     * Відкриває бічне меню з retry логікою та повертає RetryResult.
     *
     * Стратегія спроб:
     * - Спроба 1: findNodeByContentDesc/findNodeById + click
     * - Спроба 2-3: Fallback до координат
     *
     * @return RetryResult.Success при успіху, RetryResult.Failure з SkeddyError при невдачі
     */
    suspend fun navigateToMenuWithRetry(): RetryResult<Unit> {
        SkeddyLogger.d(TAG, "navigateToMenuWithRetry: Starting with retry logic...")
        SkeddyLogger.currentScreenState = detectCurrentScreen().name

        return RetryHelper.retryWithBackoff(
            config = navigationRetryConfig,
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            SkeddyLogger.d(TAG, "navigateToMenuWithRetry: Attempt #$attempt")

            val result = if (attempt == 1) {
                // Перша спроба: шукаємо по node
                navigateToMenuByNode()
            } else {
                // Fallback: координати
                SkeddyLogger.d(TAG, "navigateToMenuWithRetry: Using coordinates fallback")
                navigateToMenuByCoordinates()
            }

            if (result) {
                SkeddyLogger.i(TAG, "navigateToMenuWithRetry: Click succeeded on attempt #$attempt")
            }
            result
        }
    }

    /**
     * Переходить до Scheduled Rides з retry логікою та повертає RetryResult.
     *
     * Стратегія спроб:
     * - Спроба 1: findNodeById/findNodeByText + click
     * - Спроба 2-3: Fallback до координат
     *
     * @return RetryResult.Success при успіху, RetryResult.Failure з SkeddyError при невдачі
     */
    suspend fun navigateToScheduledRidesWithRetry(): RetryResult<Unit> {
        SkeddyLogger.d(TAG, "navigateToScheduledRidesWithRetry: Starting with retry logic...")
        SkeddyLogger.currentScreenState = detectCurrentScreen().name

        return RetryHelper.retryWithBackoff(
            config = navigationRetryConfig,
            errorOnFailure = SkeddyError.ScheduledRidesNotFound
        ) { attempt ->
            SkeddyLogger.d(TAG, "navigateToScheduledRidesWithRetry: Attempt #$attempt")

            val result = if (attempt == 1) {
                // Перша спроба: шукаємо по node
                navigateToScheduledRidesByNode()
            } else {
                // Fallback: координати
                SkeddyLogger.d(TAG, "navigateToScheduledRidesWithRetry: Using coordinates fallback")
                navigateToScheduledRidesByCoordinates()
            }

            if (result) {
                SkeddyLogger.i(TAG, "navigateToScheduledRidesWithRetry: Click succeeded on attempt #$attempt")
            }
            result
        }
    }

    /**
     * Повертається назад з retry логікою та повертає RetryResult.
     *
     * @return RetryResult.Success при успіху, RetryResult.Failure з SkeddyError при невдачі
     */
    suspend fun navigateBackWithRetry(): RetryResult<Unit> {
        SkeddyLogger.d(TAG, "navigateBackWithRetry: Starting with retry logic...")
        SkeddyLogger.currentScreenState = detectCurrentScreen().name

        return RetryHelper.retryWithBackoff(
            config = navigationRetryConfig,
            errorOnFailure = SkeddyError.NavigationFailed
        ) { attempt ->
            SkeddyLogger.d(TAG, "navigateBackWithRetry: Attempt #$attempt")

            val result = if (attempt == 1) {
                // Перша спроба: глобальна дія або кнопка
                navigateBackByNode()
            } else {
                // Fallback: координати
                SkeddyLogger.d(TAG, "navigateBackWithRetry: Using coordinates fallback")
                navigateBackByCoordinates()
            }

            if (result) {
                SkeddyLogger.i(TAG, "navigateBackWithRetry: Navigation succeeded on attempt #$attempt")
            }
            result
        }
    }

    /**
     * Повний flow до Scheduled Rides з retry та повертає RetryResult.
     *
     * @return RetryResult.Success при успіху, RetryResult.Failure з відповідним SkeddyError
     */
    suspend fun navigateToScheduledRidesFlowWithRetry(): RetryResult<Unit> {
        SkeddyLogger.i(TAG, "navigateToScheduledRidesFlowWithRetry: Starting full flow...")

        val currentScreen = detectCurrentScreen()
        SkeddyLogger.currentScreenState = currentScreen.name
        SkeddyLogger.d(TAG, "navigateToScheduledRidesFlowWithRetry: Current screen is $currentScreen")

        // Якщо вже на Scheduled Rides - успіх
        if (currentScreen == LyftScreen.SCHEDULED_RIDES) {
            SkeddyLogger.i(TAG, "navigateToScheduledRidesFlowWithRetry: Already on SCHEDULED_RIDES")
            return RetryResult.Success(Unit)
        }

        // Крок 1: Відкриваємо меню (якщо не на SIDE_MENU)
        if (currentScreen != LyftScreen.SIDE_MENU) {
            SkeddyLogger.d(TAG, "navigateToScheduledRidesFlowWithRetry: Step 1 - Opening menu...")

            val menuResult = navigateToMenuWithRetry()
            if (menuResult.isFailure) {
                SkeddyLogger.e(TAG, "navigateToScheduledRidesFlowWithRetry: Failed to open menu")
                return menuResult
            }

            // Очікуємо появи меню
            val menuAppeared = waitForScreen(LyftScreen.SIDE_MENU, LyftUIElements.MENU_ANIMATION_TIMEOUT)
            if (!menuAppeared) {
                SkeddyLogger.e(TAG, "navigateToScheduledRidesFlowWithRetry: Menu didn't appear after click")
                return RetryResult.Failure(
                    error = SkeddyError.ScreenTimeout("SIDE_MENU", LyftUIElements.MENU_ANIMATION_TIMEOUT),
                    attempts = 1
                )
            }
            SkeddyLogger.currentScreenState = LyftScreen.SIDE_MENU.name
        }

        // Крок 2: Переходимо до Scheduled Rides
        SkeddyLogger.d(TAG, "navigateToScheduledRidesFlowWithRetry: Step 2 - Navigating to Scheduled Rides...")

        val scheduledResult = navigateToScheduledRidesWithRetry()
        if (scheduledResult.isFailure) {
            SkeddyLogger.e(TAG, "navigateToScheduledRidesFlowWithRetry: Failed to navigate to Scheduled Rides")
            return scheduledResult
        }

        // Очікуємо появи екрану
        val screenAppeared = waitForScreen(LyftScreen.SCHEDULED_RIDES, LyftUIElements.SCREEN_LOAD_TIMEOUT)
        if (!screenAppeared) {
            SkeddyLogger.e(TAG, "navigateToScheduledRidesFlowWithRetry: Scheduled Rides screen didn't appear")
            return RetryResult.Failure(
                error = SkeddyError.ScreenTimeout("SCHEDULED_RIDES", LyftUIElements.SCREEN_LOAD_TIMEOUT),
                attempts = 1
            )
        }

        SkeddyLogger.currentScreenState = LyftScreen.SCHEDULED_RIDES.name
        SkeddyLogger.i(TAG, "navigateToScheduledRidesFlowWithRetry: SUCCESS - on Scheduled Rides screen")
        return RetryResult.Success(Unit)
    }

    // ==================== Private Navigation Helpers ====================

    /**
     * Навігація до меню через пошук node.
     */
    private fun navigateToMenuByNode(): Boolean {
        // Спроба 1: contentDescription
        val menuByContentDesc = accessibilityService.findLyftNodeByContentDesc(
            LyftUIElements.CONTENT_DESC_OPEN_MENU,
            exactMatch = true
        )
        if (menuByContentDesc != null) {
            SkeddyLogger.d(TAG, "navigateToMenuByNode: Found by contentDescription")
            if (accessibilityService.performClickOnNode(menuByContentDesc)) {
                return true
            }
        }

        // Спроба 2: resource-id
        val menuById = accessibilityService.findLyftNodeById(LyftUIElements.RES_MENU_BUTTON)
        if (menuById != null) {
            SkeddyLogger.d(TAG, "navigateToMenuByNode: Found by resource-id")
            if (accessibilityService.performClickOnNode(menuById)) {
                return true
            }
        }

        SkeddyLogger.w(TAG, "navigateToMenuByNode: Node not found or click failed")
        return false
    }

    /**
     * Навігація до меню через координати.
     */
    private fun navigateToMenuByCoordinates(): Boolean {
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.MENU_BUTTON_CENTER, screen.x, screen.y)
        SkeddyLogger.d(TAG, "navigateToMenuByCoordinates: Clicking at $point")
        return accessibilityService.performClick(point.x, point.y)
    }

    /**
     * Навігація до Scheduled Rides через пошук node.
     */
    private fun navigateToScheduledRidesByNode(): Boolean {
        // Спроба 1: resource-id
        val scheduleMenuItem = accessibilityService.findLyftNodeById(LyftUIElements.RES_SCHEDULED_RIDES)
        if (scheduleMenuItem != null) {
            SkeddyLogger.d(TAG, "navigateToScheduledRidesByNode: Found by resource-id")
            if (accessibilityService.performClickOnNode(scheduleMenuItem)) {
                return true
            }
        }

        // Спроба 2: текст "Scheduled"
        val scheduleByText = accessibilityService.findLyftNodeByText("Scheduled", exactMatch = false)
        if (scheduleByText != null && scheduleByText.isClickable) {
            SkeddyLogger.d(TAG, "navigateToScheduledRidesByNode: Found by text")
            if (accessibilityService.performClickOnNode(scheduleByText)) {
                return true
            }
        }

        SkeddyLogger.w(TAG, "navigateToScheduledRidesByNode: Node not found or click failed")
        return false
    }

    /**
     * Навігація до Scheduled Rides через координати.
     */
    private fun navigateToScheduledRidesByCoordinates(): Boolean {
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.SCHEDULED_RIDES_CENTER, screen.x, screen.y)
        SkeddyLogger.d(TAG, "navigateToScheduledRidesByCoordinates: Clicking at $point")
        return accessibilityService.performClick(point.x, point.y)
    }

    /**
     * Навігація назад через пошук node або глобальну дію.
     */
    private fun navigateBackByNode(): Boolean {
        // Спроба 1: GLOBAL_ACTION_BACK
        if (accessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )) {
            SkeddyLogger.d(TAG, "navigateBackByNode: GLOBAL_ACTION_BACK succeeded")
            return true
        }

        // Спроба 2: кнопка Back
        val backButton = accessibilityService.findLyftNodeByContentDesc("Back", exactMatch = false)
        if (backButton != null && accessibilityService.performClickOnNode(backButton)) {
            SkeddyLogger.d(TAG, "navigateBackByNode: Back button clicked")
            return true
        }

        // Спроба 3: Navigate up
        val navigateUp = accessibilityService.findLyftNodeByContentDesc("Navigate up", exactMatch = false)
        if (navigateUp != null && accessibilityService.performClickOnNode(navigateUp)) {
            SkeddyLogger.d(TAG, "navigateBackByNode: Navigate up clicked")
            return true
        }

        SkeddyLogger.w(TAG, "navigateBackByNode: All methods failed")
        return false
    }

    /**
     * Навігація назад через координати.
     */
    private fun navigateBackByCoordinates(): Boolean {
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.BACK_BUTTON_CENTER, screen.x, screen.y)
        SkeddyLogger.d(TAG, "navigateBackByCoordinates: Clicking at $point")
        return accessibilityService.performClick(point.x, point.y)
    }

    // ==================== Your Rides Tab Methods ====================

    /**
     * Checks whether the "Your rides" toggle is currently active.
     *
     * Lyft wraps the "Your rides" TextView in a checkable parent View.
     * The parent carries checkable=true and checked=true/false for the toggle state.
     *
     * @return true if the toggle is active (checked), false otherwise
     */
    fun isYourRidesTabActive(): Boolean {
        val tab = detectYourRidesTab() ?: return false
        val parent = tab.parent
        if (parent != null && parent.isCheckable) {
            val checked = parent.isChecked
            Log.d(TAG, "isYourRidesTabActive: parent.isChecked=$checked")
            return checked
        }
        Log.d(TAG, "isYourRidesTabActive: parent not checkable, returning false")
        return false
    }

    /**
     * Знаходить таб "Your rides" на екрані Scheduled Rides.
     *
     * Таб може мати текст "Your rides" або "Your rides (N)" де N - кількість райдів.
     *
     * @return AccessibilityNodeInfo табу або null якщо не знайдено
     */
    fun detectYourRidesTab(): android.view.accessibility.AccessibilityNodeInfo? {
        Log.d(TAG, "detectYourRidesTab: Searching for Your rides tab...")

        // Шукаємо таб по тексту "Your rides" (partial match для "Your rides (N)")
        val yourRidesTab = accessibilityService.findLyftNodeByText(
            LyftUIElements.TAB_YOUR_RIDES_TEXT,
            exactMatch = false
        )

        if (yourRidesTab != null) {
            Log.d(TAG, "detectYourRidesTab: Found tab with text containing '${LyftUIElements.TAB_YOUR_RIDES_TEXT}'")
            return yourRidesTab
        }

        Log.d(TAG, "detectYourRidesTab: Your rides tab not found")
        return null
    }

    /**
     * Парсить кількість райдів з табу "Your rides (N)".
     *
     * Приклади:
     * - "Your rides (5)" -> 5
     * - "Your rides (0)" -> 0
     * - "Your rides" -> -1 (немає числа)
     *
     * @return кількість райдів або -1 якщо таб не знайдено або формат невідомий
     */
    fun getYourRidesCount(): Int {
        Log.d(TAG, "getYourRidesCount: Parsing ride count from Your rides tab...")

        val yourRidesTab = detectYourRidesTab()
        if (yourRidesTab == null) {
            Log.w(TAG, "getYourRidesCount: Your rides tab not found")
            return -1
        }

        val tabText = yourRidesTab.text?.toString() ?: ""
        Log.d(TAG, "getYourRidesCount: Tab text is '$tabText'")

        val matchResult = LyftUIElements.YOUR_RIDES_COUNT_REGEX.find(tabText)
        if (matchResult != null) {
            val count = matchResult.groupValues[1].toIntOrNull() ?: -1
            Log.d(TAG, "getYourRidesCount: Parsed count = $count")
            return count
        }

        Log.d(TAG, "getYourRidesCount: Could not parse count from '$tabText'")
        return -1
    }

    /**
     * Клікає на таб "Your rides" для відображення зарезервованих райдів.
     *
     * Передумова: бути на екрані SCHEDULED_RIDES.
     *
     * @return true якщо клік успішний, false якщо таб не знайдено або клік невдалий
     */
    fun navigateToYourRidesTab(): Boolean {
        Log.d(TAG, "navigateToYourRidesTab: Attempting to click on Your rides tab...")

        val yourRidesTab = detectYourRidesTab()
        if (yourRidesTab == null) {
            Log.e(TAG, "navigateToYourRidesTab: Your rides tab not found")
            return false
        }

        // Спроба 1: Клік на node напряму
        if (yourRidesTab.isClickable) {
            val result = accessibilityService.performClickOnNode(yourRidesTab)
            if (result) {
                Log.i(TAG, "navigateToYourRidesTab: SUCCESS - clicked on Your rides tab")
                return true
            }
            Log.w(TAG, "navigateToYourRidesTab: Tab is clickable but click failed, trying parent...")
        }

        // Спроба 2: Клік на parent
        val parent = yourRidesTab.parent
        if (parent != null && parent.isClickable) {
            val result = accessibilityService.performClickOnNode(parent)
            if (result) {
                Log.i(TAG, "navigateToYourRidesTab: SUCCESS - clicked on parent of Your rides tab")
                return true
            }
            Log.w(TAG, "navigateToYourRidesTab: Parent click also failed, trying coordinates...")
        }

        // Спроба 3: Клік по координатах bounds
        val bounds = android.graphics.Rect()
        yourRidesTab.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            Log.d(TAG, "navigateToYourRidesTab: Trying coordinates ($centerX, $centerY)")
            val result = accessibilityService.performClick(centerX, centerY)
            if (result) {
                Log.i(TAG, "navigateToYourRidesTab: SUCCESS - clicked via bounds coordinates")
                return true
            }
        }

        Log.e(TAG, "navigateToYourRidesTab: FAILED - all click methods exhausted")
        return false
    }

    /**
     * Безпечно переходить до табу Your Rides з retry логікою.
     *
     * @return true якщо перехід успішний, false якщо всі спроби невдалі
     */
    suspend fun safeNavigateToYourRidesTab(): Boolean {
        Log.d(TAG, "safeNavigateToYourRidesTab: Starting safe navigation to Your rides tab...")

        // Затримка перед кліком для стабілізації UI
        delay(LyftUIElements.PRE_CLICK_DELAY)

        return withRetry("navigateToYourRidesTab") {
            navigateToYourRidesTab()
        }
    }

    // ==================== Layers-Based Navigation (Optimized Flow) ====================

    /**
     * Detects the "Layers" icon button on the main map screen (bottom-left).
     *
     * @return AccessibilityNodeInfo or null if not found
     */
    fun detectLayersButton(): android.view.accessibility.AccessibilityNodeInfo? {
        Log.d(TAG, "detectLayersButton: Searching for Layers button...")

        // Try contentDescription first
        val byDesc = accessibilityService.findLyftNodeByContentDesc(
            LyftUIElements.CONTENT_DESC_LAYERS_BUTTON, exactMatch = false
        )
        if (byDesc != null) {
            Log.d(TAG, "detectLayersButton: Found by contentDescription")
            return byDesc
        }

        Log.d(TAG, "detectLayersButton: Layers button not found by accessibility properties")
        return null
    }

    /**
     * Taps the Layers icon on the main map screen.
     *
     * @return true if click succeeded
     */
    fun tapLayersButton(): Boolean {
        Log.d(TAG, "tapLayersButton: Attempting to tap Layers button...")

        val button = detectLayersButton()
        if (button != null) {
            // Try 1: direct click on the node
            if (button.isClickable) {
                val result = accessibilityService.performClickOnNode(button)
                if (result) {
                    Log.i(TAG, "tapLayersButton: SUCCESS - clicked on node")
                    return true
                }
            }
            // Try 2: click on parent
            val parent = button.parent
            if (parent != null && parent.isClickable) {
                val result = accessibilityService.performClickOnNode(parent)
                if (result) {
                    Log.i(TAG, "tapLayersButton: SUCCESS - clicked on parent")
                    return true
                }
            }
            // Try 3: click on node bounds
            val bounds = android.graphics.Rect()
            button.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val result = accessibilityService.performClick(bounds.centerX(), bounds.centerY())
                if (result) {
                    Log.i(TAG, "tapLayersButton: SUCCESS - clicked via bounds (${bounds.centerX()}, ${bounds.centerY()})")
                    return true
                }
            }
        }

        // Fallback: coordinate-based click
        Log.d(TAG, "tapLayersButton: Falling back to coordinates")
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.LAYERS_BUTTON_CENTER, screen.x, screen.y)
        return accessibilityService.performClick(point.x, point.y)
    }

    /**
     * Checks if the "Maximize your earnings" bottom sheet is currently visible.
     *
     * @return true if the sheet is showing
     */
    fun isMaximizeEarningsSheetVisible(): Boolean {
        val node = accessibilityService.findLyftNodeByText("Maximize your earnings", exactMatch = false)
        val visible = node != null
        Log.d(TAG, "isMaximizeEarningsSheetVisible: $visible")
        return visible
    }

    /**
     * Taps "Scheduled rides" item within the "Maximize your earnings" bottom sheet.
     *
     * @return true if click succeeded
     */
    fun tapScheduledRidesInSheet(): Boolean {
        Log.d(TAG, "tapScheduledRidesInSheet: Searching for 'Scheduled rides' in sheet...")

        val scheduledItem = accessibilityService.findLyftNodeByText("Scheduled rides", exactMatch = false)
        if (scheduledItem != null) {
            // Try 1: direct click
            if (scheduledItem.isClickable) {
                val result = accessibilityService.performClickOnNode(scheduledItem)
                if (result) {
                    Log.i(TAG, "tapScheduledRidesInSheet: SUCCESS - clicked on node")
                    return true
                }
            }
            // Try 2: click on parent (row/container)
            val parent = scheduledItem.parent
            if (parent != null && parent.isClickable) {
                val result = accessibilityService.performClickOnNode(parent)
                if (result) {
                    Log.i(TAG, "tapScheduledRidesInSheet: SUCCESS - clicked on parent")
                    return true
                }
            }
            // Try 3: click on bounds
            val bounds = android.graphics.Rect()
            scheduledItem.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val result = accessibilityService.performClick(bounds.centerX(), bounds.centerY())
                if (result) {
                    Log.i(TAG, "tapScheduledRidesInSheet: SUCCESS - clicked via bounds (${bounds.centerX()}, ${bounds.centerY()})")
                    return true
                }
            }
        }

        // Fallback: coordinate-based click
        Log.d(TAG, "tapScheduledRidesInSheet: Falling back to coordinates")
        val screen = accessibilityService.getScreenSize()
        val point = LyftUIElements.toAbsolute(LyftUIElements.SCHEDULED_RIDES_SHEET_CENTER, screen.x, screen.y)
        return accessibilityService.performClick(point.x, point.y)
    }

    /**
     * Full navigation flow to Scheduled Rides via the Layers path.
     *
     * Steps:
     * 1. Ensure on main screen (navigate back if needed)
     * 2. Tap Layers icon
     * 3. Wait for "Maximize your earnings" sheet
     * 4. Tap "Scheduled rides" item
     * 5. Wait for SCHEDULED_RIDES screen
     *
     * @return true if navigation succeeded
     */
    suspend fun navigateToScheduledRidesViaLayers(): Boolean {
        Log.i(TAG, "navigateToScheduledRidesViaLayers: Starting Layers-based navigation...")

        // Check if already on Scheduled Rides
        val currentScreen = detectCurrentScreen()
        if (currentScreen == LyftScreen.SCHEDULED_RIDES) {
            Log.i(TAG, "navigateToScheduledRidesViaLayers: Already on Scheduled Rides screen")
            return true
        }

        // Navigate to main screen if needed
        if (currentScreen != LyftScreen.MAIN_SCREEN) {
            Log.d(TAG, "navigateToScheduledRidesViaLayers: Not on main screen ($currentScreen), navigating back...")
            repeat(3) { attempt ->
                navigateBack()
                delay(500)
                if (detectCurrentScreen() == LyftScreen.MAIN_SCREEN) {
                    Log.d(TAG, "navigateToScheduledRidesViaLayers: Reached main screen after ${attempt + 1} back presses")
                    return@repeat
                }
            }
            if (detectCurrentScreen() != LyftScreen.MAIN_SCREEN) {
                Log.e(TAG, "navigateToScheduledRidesViaLayers: Could not reach main screen")
                return false
            }
        }

        // Tap Layers button
        delay(LyftUIElements.PRE_CLICK_DELAY)
        Log.d(TAG, "navigateToScheduledRidesViaLayers: Tapping Layers button...")
        if (!tapLayersButton()) {
            Log.e(TAG, "navigateToScheduledRidesViaLayers: Failed to tap Layers button")
            return false
        }

        // Wait for "Maximize your earnings" sheet to appear
        val sheetTimeout = 3000L
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < sheetTimeout) {
            if (isMaximizeEarningsSheetVisible()) break
            delay(LyftUIElements.POLLING_INTERVAL)
        }
        if (!isMaximizeEarningsSheetVisible()) {
            Log.e(TAG, "navigateToScheduledRidesViaLayers: Maximize earnings sheet didn't appear")
            return false
        }
        Log.d(TAG, "navigateToScheduledRidesViaLayers: Maximize earnings sheet visible")

        // Tap "Scheduled rides" in the sheet
        delay(500)
        if (!tapScheduledRidesInSheet()) {
            Log.e(TAG, "navigateToScheduledRidesViaLayers: Failed to tap Scheduled rides in sheet")
            return false
        }

        // Wait for Scheduled Rides screen
        val result = waitForScreen(LyftScreen.SCHEDULED_RIDES, timeout = 5000)
        if (result) {
            Log.i(TAG, "navigateToScheduledRidesViaLayers: SUCCESS - on Scheduled Rides screen")
        } else {
            Log.e(TAG, "navigateToScheduledRidesViaLayers: Scheduled Rides screen didn't appear")
        }
        return result
    }

    /**
     * Navigates to the "Available rides" tab (counterpart to [navigateToYourRidesTab]).
     * Taps the tab that shows available rides for pickup.
     *
     * @return true if click succeeded
     */
    fun navigateToAvailableRidesTab(): Boolean {
        Log.d(TAG, "navigateToAvailableRidesTab: Searching for Available rides tab...")

        // The tab shows different text: "Airport", "Available rides", or no explicit tab
        // On the Scheduled Rides screen, if "Your rides" is active, the other tabs
        // like "Airport" or ride-type buttons become available
        val availableTab = accessibilityService.findLyftNodeByText("Airport", exactMatch = false)
            ?: accessibilityService.findLyftNodeByText("Available", exactMatch = false)

        if (availableTab != null) {
            if (availableTab.isClickable) {
                val result = accessibilityService.performClickOnNode(availableTab)
                if (result) {
                    Log.i(TAG, "navigateToAvailableRidesTab: SUCCESS - clicked on tab")
                    return true
                }
            }
            val parent = availableTab.parent
            if (parent != null && parent.isClickable) {
                val result = accessibilityService.performClickOnNode(parent)
                if (result) {
                    Log.i(TAG, "navigateToAvailableRidesTab: SUCCESS - clicked on parent")
                    return true
                }
            }
            val bounds = android.graphics.Rect()
            availableTab.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val result = accessibilityService.performClick(bounds.centerX(), bounds.centerY())
                if (result) {
                    Log.i(TAG, "navigateToAvailableRidesTab: SUCCESS - clicked via bounds")
                    return true
                }
            }
        }

        Log.e(TAG, "navigateToAvailableRidesTab: FAILED - tab not found")
        return false
    }

    // ==================== Scroll Methods ====================

    /**
     * Виконує scroll down в списку райдів.
     *
     * Використовує swipe жест від SCROLL_DOWN_START до SCROLL_DOWN_END.
     *
     * @return true якщо scroll успішний, false якщо не вдалося
     */
    suspend fun scrollDownInList(): Boolean {
        Log.d(TAG, "scrollDownInList: Performing scroll down in list...")

        val screen = accessibilityService.getScreenSize()
        val start = LyftUIElements.toAbsolute(LyftUIElements.SCROLL_DOWN_START, screen.x, screen.y)
        val end = LyftUIElements.toAbsolute(LyftUIElements.SCROLL_DOWN_END, screen.x, screen.y)
        val result = accessibilityService.performSwipe(
            startX = start.x,
            startY = start.y,
            endX = end.x,
            endY = end.y,
            durationMs = LyftUIElements.SWIPE_DURATION
        )

        if (result) {
            Log.i(TAG, "scrollDownInList: SUCCESS - scroll performed")
            // Даємо час на анімацію scroll
            delay(LyftUIElements.POLLING_INTERVAL)
        } else {
            Log.w(TAG, "scrollDownInList: Swipe failed")
        }

        return result
    }

    /**
     * Виконує scroll down на екрані деталей райду для досягнення Cancel кнопки.
     *
     * Використовує більший діапазон scroll ніж для списку.
     *
     * @return true якщо scroll успішний, false якщо не вдалося
     */
    suspend fun scrollToBottomOfDetails(): Boolean {
        Log.d(TAG, "scrollToBottomOfDetails: Performing scroll down on details screen...")

        val screen = accessibilityService.getScreenSize()
        val start = LyftUIElements.toAbsolute(LyftUIElements.DETAILS_SCROLL_DOWN_START, screen.x, screen.y)
        val end = LyftUIElements.toAbsolute(LyftUIElements.DETAILS_SCROLL_DOWN_END, screen.x, screen.y)
        val result = accessibilityService.performSwipe(
            startX = start.x,
            startY = start.y,
            endX = end.x,
            endY = end.y,
            durationMs = LyftUIElements.SWIPE_DURATION
        )

        if (result) {
            Log.i(TAG, "scrollToBottomOfDetails: SUCCESS - scroll performed")
            // Даємо час на анімацію scroll
            delay(LyftUIElements.POLLING_INTERVAL)
        } else {
            Log.w(TAG, "scrollToBottomOfDetails: Swipe failed")
        }

        return result
    }

}
