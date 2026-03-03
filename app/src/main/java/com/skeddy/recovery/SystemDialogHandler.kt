package com.skeddy.recovery

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.logging.SkeddyLogger
import kotlinx.coroutines.delay

/**
 * Результат спроби закриття системного діалогу.
 */
sealed class DismissResult {
    /** Діалог успішно закрито через кнопку dismiss/cancel/close */
    data object DismissedByButton : DismissResult()

    /** Виконано GLOBAL_ACTION_BACK для закриття діалогу */
    data object BackPerformed : DismissResult()

    /** Не вдалося закрити діалог */
    data object Failed : DismissResult()

    /** Системний діалог не знайдено (не потребує дій) */
    data object NoDialogPresent : DismissResult()

    val isSuccess: Boolean
        get() = this is DismissedByButton || this is BackPerformed || this is NoDialogPresent
}

/**
 * Обробник системних діалогів та overlay windows.
 *
 * Відповідає за:
 * - Виявлення системних діалогів (permission requests, battery warnings, etc.)
 * - Автоматичне закриття діалогів через dismiss buttons або back gesture
 * - Інтеграцію з AutoRecoveryManager для перевірки перед UI actions
 *
 * @param service instance SkeddyAccessibilityService для взаємодії з UI
 */
class SystemDialogHandler(
    private val service: SkeddyAccessibilityService
) {
    companion object {
        private const val TAG = "SystemDialogHandler"

        /** Системні packages що можуть показувати діалоги */
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.settings",
            "com.samsung.android.permissioncontroller" // Samsung devices
        )
        // Note: com.android.systemui виключено - це status bar/notification shade, не діалог

        /** Тексти кнопок для закриття діалогів (case-insensitive) */
        private val DISMISS_BUTTON_TEXTS = listOf(
            "cancel", "dismiss", "close", "no", "later", "deny",
            "not now", "skip", "відміна", "закрити", "ні", "пізніше",
            "don't allow", "не дозволяти"
        )

        /** Максимальна кількість спроб закриття діалогу */
        private const val MAX_DISMISS_ATTEMPTS = 3

        /** Затримка між спробами закриття (ms) */
        private const val DISMISS_RETRY_DELAY = 300L

        /** Затримка після виконання дії для оновлення UI (ms) */
        private const val POST_ACTION_DELAY = 200L

        /**
         * Створює SystemDialogHandler з поточним instance SkeddyAccessibilityService.
         *
         * @return SystemDialogHandler або null якщо сервіс не активний
         */
        fun create(): SystemDialogHandler? {
            val service = SkeddyAccessibilityService.getInstance()
            return if (service != null) {
                SystemDialogHandler(service)
            } else {
                SkeddyLogger.w(TAG, "create: SkeddyAccessibilityService not available")
                null
            }
        }
    }

    /**
     * Перевіряє чи на екрані присутній системний діалог.
     *
     * Детектує:
     * - Вікна від системних packages що показують діалоги (permissioncontroller, packageinstaller)
     * - TYPE_SYSTEM windows (крім systemui status bar)
     *
     * НЕ детектує:
     * - com.android.systemui (status bar, notification shade) - це не діалоги
     *
     * @return true якщо системний діалог присутній
     */
    fun isSystemDialogPresent(): Boolean {
        return try {
            // Метод 1: Перевіряємо rootInActiveWindow
            val activeRoot = service.rootInActiveWindow
            val activePackage = activeRoot?.packageName?.toString()

            SkeddyLogger.d(TAG, "isSystemDialogPresent: Active window package=$activePackage")

            // Перевірка за package name
            if (activePackage != null && activePackage != "com.android.systemui") {
                if (SYSTEM_PACKAGES.contains(activePackage)) {
                    SkeddyLogger.d(TAG, "isSystemDialogPresent: Active window is system dialog: $activePackage")
                    return true
                }
            }

            // Метод 2: Якщо package=null, перевіряємо за характерними UI елементами permission dialog
            if (activePackage == null && activeRoot != null) {
                SkeddyLogger.d(TAG, "isSystemDialogPresent: package=null, checking UI elements...")
                if (hasPermissionDialogElements(activeRoot)) {
                    SkeddyLogger.d(TAG, "isSystemDialogPresent: Detected permission dialog by UI elements")
                    return true
                }
                SkeddyLogger.d(TAG, "isSystemDialogPresent: No permission dialog elements found")
            }

            // Метод 3: Перевіряємо всі windows
            val windows = service.windows
            SkeddyLogger.d(TAG, "isSystemDialogPresent: Found ${windows.size} windows")

            var hasUnknownAppWindow = false
            var hasSkeddy = false
            var hasLyft = false

            for (window in windows) {
                val root = window.root
                val packageName = root?.packageName?.toString()
                val windowType = window.type

                SkeddyLogger.d(TAG, "isSystemDialogPresent: Window type=$windowType, package=$packageName")

                // Перевіряємо наявність наших apps
                if (packageName == "com.skeddy") {
                    hasSkeddy = true
                }
                if (packageName == "com.lyft.android.driver") {
                    hasLyft = true
                }

                // TYPE_APPLICATION (1) з package=null - можливо permission dialog
                if (windowType == AccessibilityWindowInfo.TYPE_APPLICATION && packageName == null) {
                    hasUnknownAppWindow = true
                }

                if (packageName == null) {
                    continue
                }

                // Ігноруємо systemui - це status bar/notification shade, не діалог
                if (packageName == "com.android.systemui") {
                    continue
                }

                // Перевіряємо windows від системних packages що показують діалоги
                if (SYSTEM_PACKAGES.contains(packageName)) {
                    SkeddyLogger.d(TAG, "isSystemDialogPresent: Found system dialog package: $packageName")
                    return true
                }

                // TYPE_SYSTEM windows від інших packages (крім systemui)
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    SkeddyLogger.d(TAG, "isSystemDialogPresent: Found TYPE_SYSTEM window, package=$packageName")
                    return true
                }
            }

            // Метод 4: Якщо є unknown app window (package=null, type=APPLICATION) і activeRoot=null
            // Це може бути permission dialog який блокує доступ до свого content
            if (hasUnknownAppWindow && activeRoot == null) {
                SkeddyLogger.d(TAG, "isSystemDialogPresent: Detected blocked dialog (unknown app window + null activeRoot)")
                return true
            }

            SkeddyLogger.d(TAG, "isSystemDialogPresent: No system dialog detected (skeddy=$hasSkeddy, lyft=$hasLyft, unknownApp=$hasUnknownAppWindow)")
            false
        } catch (e: Exception) {
            SkeddyLogger.e(TAG, "isSystemDialogPresent: Error checking for system dialogs", e)
            false
        }
    }

    /**
     * Перевіряє чи UI hierarchy містить характерні елементи permission dialog.
     * Використовується коли package=null (Android 14+ приховує package для permission dialogs).
     */
    private fun hasPermissionDialogElements(root: AccessibilityNodeInfo): Boolean {
        // Шукаємо характерні кнопки permission dialog
        val permissionButtonTexts = listOf("allow", "don't allow", "deny", "while using the app", "only this time")

        return findNodeByTexts(root, permissionButtonTexts) != null
    }

    /**
     * Отримує root node системного діалогу якщо він присутній.
     *
     * @return AccessibilityNodeInfo root діалогу або null
     */
    private fun getSystemDialogRoot(): AccessibilityNodeInfo? {
        return try {
            val windows = service.windows

            for (window in windows) {
                val root = window.root ?: continue
                val packageName = root.packageName?.toString() ?: continue

                // Ігноруємо systemui - це status bar/notification shade
                if (packageName == "com.android.systemui") {
                    continue
                }

                // Windows від системних packages що показують діалоги
                if (SYSTEM_PACKAGES.contains(packageName)) {
                    SkeddyLogger.d(TAG, "getSystemDialogRoot: Found system dialog root: $packageName")
                    return root
                }

                // TYPE_SYSTEM windows від інших packages
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    SkeddyLogger.d(TAG, "getSystemDialogRoot: Found TYPE_SYSTEM root, package=$packageName")
                    return root
                }
            }

            null
        } catch (e: Exception) {
            SkeddyLogger.e(TAG, "getSystemDialogRoot: Error getting system dialog root", e)
            null
        }
    }

    /**
     * Спробувати закрити системний діалог.
     *
     * Стратегії закриття (в порядку пріоритету):
     * 1. Знайти та натиснути кнопку dismiss/cancel/close/no/later
     * 2. Виконати GLOBAL_ACTION_BACK
     *
     * @return DismissResult з результатом спроби
     */
    fun tryDismissDialog(): DismissResult {
        SkeddyLogger.i(TAG, "tryDismissDialog: Attempting to dismiss system dialog...")

        // Перевіряємо чи є діалог
        if (!isSystemDialogPresent()) {
            SkeddyLogger.d(TAG, "tryDismissDialog: No system dialog present")
            return DismissResult.NoDialogPresent
        }

        val dialogRoot = getSystemDialogRoot()
        if (dialogRoot == null) {
            SkeddyLogger.w(TAG, "tryDismissDialog: Dialog detected but cannot get root node")
            return performBackAction()
        }

        // Strategy 1: Знайти та натиснути dismiss button
        val dismissButton = findDismissButton(dialogRoot)
        if (dismissButton != null) {
            SkeddyLogger.d(TAG, "tryDismissDialog: Found dismiss button, clicking...")
            val clicked = clickNode(dismissButton)
            if (clicked) {
                SkeddyLogger.i(TAG, "tryDismissDialog: Successfully clicked dismiss button")
                return DismissResult.DismissedByButton
            }
            SkeddyLogger.w(TAG, "tryDismissDialog: Failed to click dismiss button")
        }

        // Strategy 2: Виконати back gesture
        SkeddyLogger.d(TAG, "tryDismissDialog: No dismiss button found, trying BACK action...")
        return performBackAction()
    }

    /**
     * Спробувати закрити системний діалог з retry logic.
     *
     * @param maxAttempts максимальна кількість спроб
     * @return DismissResult з результатом
     */
    suspend fun tryDismissDialogWithRetry(maxAttempts: Int = MAX_DISMISS_ATTEMPTS): DismissResult {
        SkeddyLogger.i(TAG, "tryDismissDialogWithRetry: Starting with maxAttempts=$maxAttempts")

        repeat(maxAttempts) { attempt ->
            val attemptNumber = attempt + 1
            SkeddyLogger.d(TAG, "tryDismissDialogWithRetry: Attempt #$attemptNumber of $maxAttempts")

            val result = tryDismissDialog()

            when (result) {
                is DismissResult.NoDialogPresent -> {
                    SkeddyLogger.i(TAG, "tryDismissDialogWithRetry: No dialog present, success!")
                    return result
                }
                is DismissResult.DismissedByButton,
                is DismissResult.BackPerformed -> {
                    // Чекаємо на оновлення UI
                    delay(POST_ACTION_DELAY)

                    // Перевіряємо чи діалог закрився
                    if (!isSystemDialogPresent()) {
                        SkeddyLogger.i(TAG, "tryDismissDialogWithRetry: Dialog dismissed on attempt #$attemptNumber")
                        return result
                    }
                    SkeddyLogger.w(TAG, "tryDismissDialogWithRetry: Dialog still present after action")
                }
                is DismissResult.Failed -> {
                    SkeddyLogger.w(TAG, "tryDismissDialogWithRetry: Dismiss failed on attempt #$attemptNumber")
                }
            }

            if (attemptNumber < maxAttempts) {
                delay(DISMISS_RETRY_DELAY)
            }
        }

        SkeddyLogger.e(TAG, "tryDismissDialogWithRetry: Failed after $maxAttempts attempts")
        return DismissResult.Failed
    }

    /**
     * Перевіряє та закриває системний діалог якщо присутній.
     * Зручний метод для виклику перед UI операціями.
     *
     * @return true якщо немає діалогу або він успішно закритий
     */
    suspend fun ensureNoSystemDialog(): Boolean {
        if (!isSystemDialogPresent()) {
            return true
        }

        SkeddyLogger.i(TAG, "ensureNoSystemDialog: System dialog detected, attempting to dismiss...")
        val result = tryDismissDialogWithRetry()
        return result.isSuccess
    }

    /**
     * Шукає кнопку для закриття діалогу.
     *
     * @param root root node діалогу
     * @return AccessibilityNodeInfo кнопки або null
     */
    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByTexts(root, DISMISS_BUTTON_TEXTS)
    }

    /**
     * Рекурсивний пошук ноди по списку текстів.
     *
     * @param node початкова нода
     * @param texts список текстів для пошуку (case-insensitive)
     * @return перша знайдена clickable нода або null
     */
    private fun findNodeByTexts(
        node: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        // Перевіряємо текст ноди
        val nodeText = node.text?.toString()?.lowercase()
        val contentDesc = node.contentDescription?.toString()?.lowercase()

        for (text in texts) {
            val lowerText = text.lowercase()
            if (nodeText?.contains(lowerText) == true || contentDesc?.contains(lowerText) == true) {
                // Перевіряємо чи нода clickable або шукаємо clickable parent
                if (node.isClickable) {
                    SkeddyLogger.d(TAG, "findNodeByTexts: Found clickable node with text '$text'")
                    return node
                }

                // Шукаємо clickable parent
                val clickableParent = findClickableParent(node)
                if (clickableParent != null) {
                    SkeddyLogger.d(TAG, "findNodeByTexts: Found clickable parent for node with text '$text'")
                    return clickableParent
                }
            }
        }

        // Рекурсивний пошук у дочірніх елементах
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findNodeByTexts(child, texts)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    /**
     * Шукає найближчий clickable parent для ноди.
     *
     * @param node початкова нода
     * @return clickable parent або null
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        val maxDepth = 5 // Обмежуємо глибину пошуку

        while (parent != null && depth < maxDepth) {
            if (parent.isClickable) {
                return parent
            }
            parent = parent.parent
            depth++
        }

        return null
    }

    /**
     * Виконує клік на ноду.
     *
     * @param node нода для кліку
     * @return true якщо клік успішний
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            SkeddyLogger.e(TAG, "clickNode: Failed to click", e)
            false
        }
    }

    /**
     * Виконує GLOBAL_ACTION_BACK.
     *
     * @return DismissResult.BackPerformed якщо успішно, DismissResult.Failed якщо ні
     */
    private fun performBackAction(): DismissResult {
        return try {
            val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (result) {
                SkeddyLogger.i(TAG, "performBackAction: GLOBAL_ACTION_BACK performed successfully")
                DismissResult.BackPerformed
            } else {
                SkeddyLogger.w(TAG, "performBackAction: GLOBAL_ACTION_BACK returned false")
                DismissResult.Failed
            }
        } catch (e: Exception) {
            SkeddyLogger.e(TAG, "performBackAction: Failed to perform GLOBAL_ACTION_BACK", e)
            DismissResult.Failed
        }
    }

    /**
     * Отримує інформацію про поточний системний діалог для логування.
     *
     * @return опис діалогу або null якщо немає
     */
    fun getSystemDialogInfo(): String? {
        val dialogRoot = getSystemDialogRoot() ?: return null

        return buildString {
            append("SystemDialog[")
            append("package=${dialogRoot.packageName}")
            append(", class=${dialogRoot.className}")

            // Спробуємо знайти текст діалогу
            val textContent = findFirstText(dialogRoot)
            if (textContent != null) {
                append(", text='$textContent'")
            }

            append("]")
        }
    }

    /**
     * Знаходить перший текст в дереві нод.
     */
    private fun findFirstText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null

        node.text?.toString()?.let { text ->
            if (text.isNotBlank() && text.length < 100) {
                return text
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findFirstText(child)?.let { return it }
        }

        return null
    }
}
