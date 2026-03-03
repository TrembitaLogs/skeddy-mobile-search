package com.skeddy.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import com.skeddy.data.toBlacklistedRide
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftNavigator
import com.skeddy.navigation.LyftScreen
import com.skeddy.navigation.LyftUIElements
import com.skeddy.network.ApiResult
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.toRideData
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Sealed class для результату auto-accept операції.
 * Кожен підклас представляє конкретний результат спроби автоматичного прийняття райду.
 */
sealed class AutoAcceptResult {
    /**
     * Райд успішно прийнято.
     * @param ride інформація про прийнятий райд
     */
    data class Success(val ride: ScheduledRide) : AutoAcceptResult()

    /**
     * Екран деталей райду не з'явився після кліку на картку.
     * @param reason опис причини помилки
     */
    data class RideNotFound(val reason: String) : AutoAcceptResult()

    /**
     * Кнопка Accept/Reserve/Claim не знайдена на екрані деталей.
     * @param reason опис причини помилки
     */
    data class AcceptButtonNotFound(val reason: String) : AutoAcceptResult()

    /**
     * Клік на елемент не спрацював.
     * @param reason опис причини помилки
     */
    data class ClickFailed(val reason: String) : AutoAcceptResult()

    /**
     * Timeout при очікуванні підтвердження резервації.
     * @param reason опис причини помилки
     */
    data class ConfirmationTimeout(val reason: String) : AutoAcceptResult()
}

/**
 * Керує автоматичним прийняттям райдів.
 *
 * Оркеструє процес auto-accept:
 * 1. Клік на картку райду
 * 2. Очікування екрану RIDE_DETAILS
 * 3. Пошук та клік кнопки Accept/Reserve/Claim
 * 4. Обробка confirmation dialog (двоетапний процес для Reserve)
 * 5. Верифікація успіху через наявність "Cancel ride" кнопки
 * 6. Повернення назад на попередній екран
 *
 * @param accessibilityService сервіс для взаємодії з UI
 * @param navigator навігатор для роботи з екранами Lyft
 * @param serverClient client for reporting accepted rides to the server
 * @param blacklistRepository repository for blacklisting accepted rides
 * @param pendingQueue queue for ride reports that failed to send
 */
class AutoAcceptManager(
    private val accessibilityService: SkeddyAccessibilityService,
    private val navigator: LyftNavigator,
    private val serverClient: SkeddyServerClient,
    private val blacklistRepository: BlacklistRepository,
    private val pendingQueue: PendingRideQueue
) {
    companion object {
        private const val TAG = "AutoAcceptManager"
        private const val DEBUG_TAG = "LyftIdDebug"

        /** Затримка після кліку на картку перед перевіркою екрану (ms) */
        private const val POST_CARD_CLICK_DELAY = 1000L

        /** Затримка після першого кліку Reserve перед пошуком confirmation (ms) */
        private const val POST_FIRST_RESERVE_DELAY = 1500L

        /** Затримка після підтвердження резервації (ms) */
        private const val POST_CONFIRMATION_DELAY = 2000L

        /** Максимальний час очікування confirmation dialog (ms) */
        private const val CONFIRMATION_DIALOG_TIMEOUT = 5000L

        /**
         * Factory method for creating AutoAcceptManager.
         * @param serverClient client for server communication
         * @param blacklistRepository repository for ride blacklisting
         * @param pendingQueue queue for pending ride reports
         * @return AutoAcceptManager or null if AccessibilityService is unavailable
         */
        fun create(
            serverClient: SkeddyServerClient,
            blacklistRepository: BlacklistRepository,
            pendingQueue: PendingRideQueue
        ): AutoAcceptManager? {
            val service = SkeddyAccessibilityService.getInstance()
            if (service == null) {
                Log.w(TAG, "create: SkeddyAccessibilityService not available")
                return null
            }
            val nav = LyftNavigator(service)
            return AutoAcceptManager(service, nav, serverClient, blacklistRepository, pendingQueue)
        }
    }

    /**
     * Виконує автоматичний accept райду.
     *
     * Передумова: знаходимось на екрані SCHEDULED_RIDES
     *
     * Кроки:
     * 1. Клікнути на картку райду
     * 2. Дочекатись екрану RIDE_DETAILS
     * 3. Знайти та натиснути Accept/Reserve/Claim кнопку
     * 4. Обробити confirmation dialog (для Reserve - двоетапний процес)
     * 5. Верифікувати успіх через наявність "Cancel ride"
     * 6. Повернутись на попередній екран
     *
     * @param rideCard AccessibilityNodeInfo картки райду для кліку
     * @param ride інформація про райд для логування та результату
     * @return AutoAcceptResult з результатом операції
     */
    suspend fun autoAcceptRide(
        rideCard: AccessibilityNodeInfo,
        ride: ScheduledRide
    ): AutoAcceptResult {
        Log.i(TAG, "autoAcceptRide: Starting auto-accept for ride ${ride.id} (\$${ride.price})")
        Log.d(TAG, "autoAcceptRide: Ride details - ${ride.pickupTime}, ${ride.pickupLocation} -> ${ride.dropoffLocation}")

        // ========== Крок 1: Клікнути на картку ==========
        Log.d(TAG, "autoAcceptRide: Step 1 - Clicking on ride card...")
        val clickCardResult = navigator.clickOnRideCard(rideCard)
        if (!clickCardResult) {
            Log.e(TAG, "autoAcceptRide: Failed to click on ride card")
            return AutoAcceptResult.ClickFailed("Failed to click on ride card")
        }
        Log.d(TAG, "autoAcceptRide: Ride card clicked successfully")

        // ========== Крок 2: Дочекатись екрану RIDE_DETAILS ==========
        Log.d(TAG, "autoAcceptRide: Step 2 - Waiting for RIDE_DETAILS screen...")
        val detailsAppeared = navigator.waitForScreen(
            LyftScreen.RIDE_DETAILS,
            LyftUIElements.SCREEN_LOAD_TIMEOUT
        )
        if (!detailsAppeared) {
            Log.e(TAG, "autoAcceptRide: RIDE_DETAILS screen didn't appear within timeout")
            navigator.safeNavigateBack()
            return AutoAcceptResult.RideNotFound("Ride details screen didn't appear")
        }
        Log.d(TAG, "autoAcceptRide: RIDE_DETAILS screen detected")

        // Затримка для повного завантаження контенту
        delay(POST_CARD_CLICK_DELAY)

        // ========== Крок 3: Знайти Accept/Reserve/Claim кнопку ==========
        Log.d(TAG, "autoAcceptRide: Step 3 - Looking for Accept/Reserve/Claim button...")
        val acceptButton = navigator.detectAcceptButton()
        if (acceptButton == null) {
            Log.e(TAG, "autoAcceptRide: Accept/Reserve/Claim button not found on RIDE_DETAILS")
            navigator.safeNavigateBack()
            return AutoAcceptResult.AcceptButtonNotFound("Accept/Reserve/Claim button not visible on ride details screen")
        }
        Log.d(TAG, "autoAcceptRide: Accept button found")

        // ========== Крок 4: Перший клік на Accept/Reserve ==========
        Log.d(TAG, "autoAcceptRide: Step 4 - Clicking Accept/Reserve button (first click)...")
        val firstClickResult = accessibilityService.performClickOnNode(acceptButton)
        if (!firstClickResult) {
            Log.e(TAG, "autoAcceptRide: Failed to click Accept/Reserve button")
            navigator.safeNavigateBack()
            return AutoAcceptResult.ClickFailed("Failed to click Accept/Reserve button")
        }
        Log.d(TAG, "autoAcceptRide: First click on Accept/Reserve successful")

        // Затримка для появи confirmation dialog
        delay(POST_FIRST_RESERVE_DELAY)

        // ========== Крок 5: Обробка confirmation dialog ==========
        Log.d(TAG, "autoAcceptRide: Step 5 - Handling confirmation dialog...")
        val confirmationResult = handleConfirmationDialog()
        if (!confirmationResult) {
            Log.w(TAG, "autoAcceptRide: Confirmation dialog handling returned false, checking if reservation succeeded anyway...")
        }

        // Затримка після підтвердження
        delay(POST_CONFIRMATION_DELAY)

        // ========== Крок 6: Верифікація успіху ==========
        Log.d(TAG, "autoAcceptRide: Step 6 - Verifying reservation success...")
        val isSuccess = verifyReservationSuccess()
        if (!isSuccess) {
            Log.w(TAG, "autoAcceptRide: Could not verify reservation success (Cancel ride button not found)")
            // Не вважаємо це критичною помилкою - можливо UI просто змінився
        } else {
            Log.i(TAG, "autoAcceptRide: Reservation verified - Cancel ride button found")
        }

        // ========== Крок 7: Повернення назад ==========
        Log.d(TAG, "autoAcceptRide: Step 7 - Navigating back...")
        navigator.safeNavigateBack()

        // Невелика затримка для завершення анімації
        delay(500)

        Log.i(TAG, "autoAcceptRide: SUCCESS - Ride ${ride.id} (\$${ride.price}) accepted!")

        // Add to blacklist to prevent re-accepting a ride the driver later cancels in Lyft
        try {
            blacklistRepository.addToBlacklist(ride.toBlacklistedRide())
            Log.i(TAG, "Ride ${ride.id} added to blacklist after successful accept")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add ride to blacklist: ${e.message}")
        }

        // Report accepted ride to server (pessimistic: enqueue first, then try to send)
        val idempotencyKey = UUID.randomUUID().toString()
        val reportRequest = RideReportRequest(
            idempotencyKey = idempotencyKey,
            eventType = "ACCEPTED",
            rideHash = ride.id,
            timezone = java.util.TimeZone.getDefault().id,
            rideData = ride.toRideData()
        )
        try {
            pendingQueue.enqueue(reportRequest)
            val reportResult = serverClient.reportRide(reportRequest)
            if (reportResult is ApiResult.Success) {
                pendingQueue.remove(reportRequest)
                Log.i(TAG, "Ride ${ride.id} reported to server successfully")
            } else {
                Log.w(TAG, "Failed to report ride ${ride.id}: $reportResult, kept in pending queue")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reporting ride ${ride.id}: ${e.message}, kept in pending queue")
        }

        return AutoAcceptResult.Success(ride)
    }

    /**
     * Обробляє confirmation dialog для двоетапного процесу резервації.
     *
     * Lyft для Available rides вимагає два кліки:
     * 1. Перший "Reserve" - відкриває діалог "Reserve this ride?"
     * 2. Другий "Reserve" або "Confirm" - підтверджує резервацію
     *
     * @return true якщо confirmation знайдено і оброблено, false якщо діалог не знайдено
     */
    private suspend fun handleConfirmationDialog(): Boolean {
        Log.d(TAG, "handleConfirmationDialog: Looking for confirmation dialog...")

        // Стратегія пошуку confirmation кнопки (в порядку пріоритету):
        // 1. Друга кнопка "Reserve" (для Available rides)
        // 2. Кнопка "Confirm"
        // 3. Кнопка "Yes"

        val confirmationTexts = listOf(
            Pair("Reserve", true),   // Для Available rides - друга Reserve кнопка
            Pair("Confirm", false),  // Загальний варіант
            Pair("Yes", true)        // Альтернативний варіант
        )

        for ((text, exactMatch) in confirmationTexts) {
            val button = accessibilityService.findLyftNodeByText(text, exactMatch = exactMatch)
            if (button != null && (button.isClickable || button.parent?.isClickable == true)) {
                Log.d(TAG, "handleConfirmationDialog: Found confirmation button with text '$text'")

                val clickTarget = if (button.isClickable) button else button.parent!!
                val clickResult = accessibilityService.performClickOnNode(clickTarget)

                if (clickResult) {
                    Log.i(TAG, "handleConfirmationDialog: Successfully clicked confirmation button '$text'")
                    return true
                } else {
                    Log.w(TAG, "handleConfirmationDialog: Found button '$text' but click failed")
                }
            }
        }

        // Перевіряємо чи є діалог "Reserve this ride?"
        val dialogTitle = accessibilityService.findLyftNodeByText("Reserve this ride", exactMatch = false)
        if (dialogTitle != null) {
            Log.d(TAG, "handleConfirmationDialog: Found 'Reserve this ride?' dialog, searching for action button...")

            // Шукаємо будь-яку clickable кнопку в діалозі
            // Часто це може бути просто "Reserve" без додаткового тексту
            val reserveButton = navigator.detectAcceptButton()
            if (reserveButton != null) {
                val clickResult = accessibilityService.performClickOnNode(reserveButton)
                if (clickResult) {
                    Log.i(TAG, "handleConfirmationDialog: Clicked Reserve button in confirmation dialog")
                    return true
                }
            }
        }

        Log.d(TAG, "handleConfirmationDialog: No confirmation dialog found - might be single-step accept")
        return false
    }

    /**
     * Верифікує успішність резервації через наявність кнопки "Cancel ride".
     *
     * Після успішної резервації Lyft показує екран деталей підтвердженого райду,
     * на якому є кнопка "Cancel ride" або "Cancel scheduled ride".
     *
     * @return true якщо знайдено індикатор успішної резервації
     */
    private fun verifyReservationSuccess(): Boolean {
        Log.d(TAG, "verifyReservationSuccess: Looking for success indicators...")

        // Шукаємо кнопку Cancel ride - індикатор успішної резервації
        val cancelTexts = listOf(
            "Cancel ride",
            "Cancel scheduled ride",
            "Cancel"
        )

        for (text in cancelTexts) {
            val cancelButton = accessibilityService.findLyftNodeByText(text, exactMatch = false)
            if (cancelButton != null) {
                Log.d(TAG, "verifyReservationSuccess: Found '$text' button - reservation confirmed")
                return true
            }
        }

        // Альтернативна перевірка - шукаємо текст підтвердження
        val confirmationTexts = listOf(
            "reserved",
            "confirmed",
            "accepted"
        )

        for (text in confirmationTexts) {
            val confirmationNode = accessibilityService.findLyftNodeByText(text, exactMatch = false)
            if (confirmationNode != null) {
                Log.d(TAG, "verifyReservationSuccess: Found '$text' text - reservation likely confirmed")
                return true
            }
        }

        Log.d(TAG, "verifyReservationSuccess: No success indicators found")
        return false
    }
}
