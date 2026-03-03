package com.skeddy.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Accessibility Service для моніторингу Lyft Driver додатку.
 *
 * Призначення:
 * - Відстежувати UI events з Lyft додатку
 * - Парсити інформацію про райди (ціна, час, відстань)
 * - Передавати дані до RideParser для обробки
 * - Ініціювати автоматичну навігацію через LyftNavigator
 */
class SkeddyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SkeddyAccessibility"

        /** Тривалість tap gesture в мілісекундах */
        private const val TAP_DURATION_MS = 100L
        /** Тривалість long click gesture в мілісекундах */
        private const val LONG_CLICK_DURATION_MS = 500L
        /** Дефолтна тривалість swipe gesture в мілісекундах */
        private const val DEFAULT_SWIPE_DURATION_MS = 300L

        @Volatile
        private var instance: SkeddyAccessibilityService? = null

        @Volatile
        private var lastForegroundPackage: String? = null

        /**
         * Отримати поточний instance сервісу.
         * @return instance сервісу або null якщо сервіс не активний
         */
        fun getInstance(): SkeddyAccessibilityService? = instance

        /**
         * Перевірити чи сервіс увімкнений та активний.
         * @return true якщо сервіс активний
         */
        fun isServiceEnabled(): Boolean = instance != null

        /**
         * Returns the package name of the last foreground app detected via
         * TYPE_WINDOW_STATE_CHANGED events. More reliable than
         * ActivityManager.getRunningTasks() on Android 5+.
         *
         * @return package name or null if no event has been received yet
         */
        fun getLastForegroundPackage(): String? = lastForegroundPackage

        /** Our own application package, ignored for foreground tracking */
        private const val APPLICATION_ID = "com.skeddy"

        /** Android system UI package (notification shade, status bar) */
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** Action для debug тестування кліків через ADB broadcast */
        const val ACTION_TEST_CLICK = "com.skeddy.accessibility.TEST_CLICK"

        /** Action для debug тестування detectCurrentScreen через ADB broadcast */
        const val ACTION_TEST_SCREEN_DETECT = "com.skeddy.accessibility.TEST_SCREEN_DETECT"

        /** Action для debug тестування навігаційних методів через ADB broadcast */
        const val ACTION_TEST_NAVIGATION = "com.skeddy.accessibility.TEST_NAVIGATION"

        /** Action для debug тестування SystemDialogHandler через ADB broadcast */
        const val ACTION_TEST_SYSTEM_DIALOG = "com.skeddy.accessibility.TEST_SYSTEM_DIALOG"
    }

    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerDebugReceiver()
        Log.d(TAG, "onServiceConnected: Accessibility Service підключено")
        Log.i(TAG, "Service info: ${serviceInfo?.packageNames?.joinToString() ?: "all packages"}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "onAccessibilityEvent: TYPE_WINDOW_STATE_CHANGED")
                Log.d(TAG, "  Package: ${event.packageName}")
                Log.d(TAG, "  Class: ${event.className}")
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.v(TAG, "onAccessibilityEvent: TYPE_WINDOW_CONTENT_CHANGED")
                Log.v(TAG, "  Package: ${event.packageName}")
                handleWindowContentChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "onAccessibilityEvent: TYPE_VIEW_CLICKED")
                Log.d(TAG, "  Package: ${event.packageName}")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                Log.v(TAG, "onAccessibilityEvent: TYPE_VIEW_TEXT_CHANGED")
            }
            else -> {
                Log.v(TAG, "onAccessibilityEvent: type=${event.eventType}")
            }
        }
    }

    /**
     * Обробка зміни стану вікна (нова Activity, діалог тощо).
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return

        // Track the foreground app for reliable detection (replaces getRunningTasks).
        // Ignore our own package, system UI and launchers to avoid false negatives
        // during search automation cycles (Lyft → Skeddy → Launcher → Lyft).
        if (!isTransitionalPackage(packageName)) {
            lastForegroundPackage = packageName
        }

        Log.d(TAG, "handleWindowStateChanged: $packageName / $className")

        // DEBUG: Test captureUIHierarchy when Lyft Driver window changes
        if (packageName == "com.lyft.android.driver") {
            Log.i(TAG, "Lyft Driver detected, capturing UI hierarchy...")
            val root = captureUIHierarchy()
            if (root != null) {
                Log.i(TAG, "UI hierarchy captured: package=${root.packageName}, children=${root.childCount}")
            }
        }

        // TODO: Передавати до RideParser для аналізу (буде в task 2.4+)
    }

    /**
     * Обробка зміни контенту вікна (оновлення UI елементів).
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // TODO: Аналізувати зміни контенту для виявлення нових райдів (буде в task 2.4+)
    }

    /**
     * Returns true for packages that represent transitional states during
     * the search automation cycle and should not overwrite [lastForegroundPackage].
     * This prevents false negatives in [getLastForegroundPackage] when the
     * automation briefly switches through Skeddy or the home launcher.
     */
    private fun isTransitionalPackage(packageName: String): Boolean {
        return packageName == APPLICATION_ID ||
                packageName == SYSTEM_UI_PACKAGE ||
                packageName.contains("launcher", ignoreCase = true)
    }

    // ==================== Screen Size ====================

    /**
     * Returns the real screen dimensions (width x height) in pixels.
     * Accounts for the full display including navigation bar area.
     *
     * @return Point where x = width, y = height
     */
    @Suppress("DEPRECATION")
    fun getScreenSize(): Point {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    // ==================== UI Hierarchy Methods ====================

    /**
     * Захоплює повний UI hierarchy поточного активного вікна.
     *
     * @return root AccessibilityNodeInfo або null якщо:
     *         - сервіс не активний
     *         - немає активного вікна
     *         - виникла помилка при захопленні
     */
    fun captureUIHierarchy(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "captureUIHierarchy: rootInActiveWindow is null (service not active or no foreground window)")
                return null
            }

            // Refresh для отримання актуальних даних
            root.refresh()

            Log.d(TAG, "captureUIHierarchy: UI Hierarchy captured successfully")
            Log.d(TAG, "  Package: ${root.packageName}")
            Log.d(TAG, "  Class: ${root.className}")
            Log.d(TAG, "  Child count: ${root.childCount}")

            root
        } catch (e: Exception) {
            Log.e(TAG, "captureUIHierarchy: Failed to capture UI hierarchy", e)
            null
        }
    }

    /**
     * Захоплює UI hierarchy конкретно від Lyft Driver додатку.
     * Шукає вікно Lyft серед усіх відкритих вікон, навіть якщо воно не активне.
     *
     * Це важливо для моніторингу, коли Skeddy може бути в foreground,
     * а Lyft працює у фоні.
     *
     * @return root AccessibilityNodeInfo від Lyft Driver або null якщо:
     *         - вікно Lyft не знайдено
     *         - немає дозволу на доступ до вікон
     *         - виникла помилка
     */
    fun captureLyftUIHierarchy(): AccessibilityNodeInfo? {
        return try {
            val lyftPackage = "com.lyft.android.driver"

            // Спочатку перевіряємо активне вікно
            val activeRoot = rootInActiveWindow
            if (activeRoot?.packageName == lyftPackage) {
                activeRoot.refresh()
                Log.d(TAG, "captureLyftUIHierarchy: Lyft is active window")
                Log.d(TAG, "  Package: ${activeRoot.packageName}")
                Log.d(TAG, "  Class: ${activeRoot.className}")
                Log.d(TAG, "  Child count: ${activeRoot.childCount}")
                return activeRoot
            }

            // Шукаємо вікно Lyft серед усіх вікон
            Log.d(TAG, "captureLyftUIHierarchy: Searching for Lyft window among all windows...")
            val allWindows = windows

            if (allWindows.isEmpty()) {
                Log.w(TAG, "captureLyftUIHierarchy: No windows available (missing FLAG_RETRIEVE_INTERACTIVE_WINDOWS?)")
                return null
            }

            Log.d(TAG, "captureLyftUIHierarchy: Found ${allWindows.size} windows")

            for (window in allWindows) {
                val windowRoot = window.root
                if (windowRoot != null) {
                    val pkg = windowRoot.packageName?.toString()
                    Log.v(TAG, "captureLyftUIHierarchy: Window package=$pkg, type=${window.type}")

                    if (pkg == lyftPackage) {
                        windowRoot.refresh()
                        Log.d(TAG, "captureLyftUIHierarchy: Found Lyft window!")
                        Log.d(TAG, "  Package: ${windowRoot.packageName}")
                        Log.d(TAG, "  Class: ${windowRoot.className}")
                        Log.d(TAG, "  Child count: ${windowRoot.childCount}")
                        Log.d(TAG, "  Window type: ${window.type}, layer: ${window.layer}")
                        return windowRoot
                    }
                }
            }

            Log.w(TAG, "captureLyftUIHierarchy: Lyft window not found among ${allWindows.size} windows")
            Log.d(TAG, "captureLyftUIHierarchy: Available packages: ${
                allWindows.mapNotNull { it.root?.packageName?.toString() }.distinct().joinToString()
            }")
            null
        } catch (e: Exception) {
            Log.e(TAG, "captureLyftUIHierarchy: Failed to capture Lyft UI hierarchy", e)
            null
        }
    }

    /**
     * Створює текстовий dump UI дерева для debug purposes.
     *
     * @param node початкова нода для обходу
     * @param depth поточна глибина в дереві (для відступів)
     * @return форматований string з UI деревом
     */
    fun dumpUITree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return "null"

        val indent = "  ".repeat(depth)
        val sb = StringBuilder()

        // Базова інформація про ноду
        sb.appendLine("$indent├─ ${node.className}")
        sb.appendLine("$indent│  viewId: ${node.viewIdResourceName ?: "N/A"}")
        sb.appendLine("$indent│  text: ${node.text ?: "N/A"}")
        sb.appendLine("$indent│  contentDesc: ${node.contentDescription ?: "N/A"}")
        sb.appendLine("$indent│  clickable: ${node.isClickable}, enabled: ${node.isEnabled}, visible: ${node.isVisibleToUser}")

        // Рекурсивний обхід дочірніх елементів
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                sb.append(dumpUITree(child, depth + 1))
                // Note: recycle() deprecated in API 33+, система автоматично керує пам'яттю
            }
        }

        return sb.toString()
    }

    /**
     * Захоплює та логує повний UI dump поточного вікна.
     * Корисно для debug та аналізу структури Lyft Driver UI.
     */
    fun logUITreeDump() {
        val root = captureUIHierarchy()
        if (root != null) {
            Log.d(TAG, "=== UI Tree Dump Start ===")
            Log.d(TAG, dumpUITree(root))
            Log.d(TAG, "=== UI Tree Dump End ===")
            // Note: recycle() deprecated in API 33+, система автоматично керує пам'яттю
        } else {
            Log.w(TAG, "logUITreeDump: Cannot dump UI tree - no root available")
        }
    }

    // ==================== Node Search Methods ====================

    /**
     * Базовий рекурсивний пошук ноди по предикату.
     * Обходить дерево в depth-first порядку.
     *
     * @param node початкова нода для пошуку
     * @param predicate умова для пошуку
     * @return перша нода що відповідає predicate або null
     */
    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        // Перевіряємо поточну ноду
        if (predicate(node)) {
            return node
        }

        // Рекурсивний пошук у дочірніх елементах
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findNode(child, predicate)
                if (result != null) {
                    return result
                }
                // Note: не робимо recycle() на child якщо не знайшли результат,
                // бо API 33+ автоматично керує пам'яттю
            }
        }

        return null
    }

    /**
     * Базовий рекурсивний пошук ВСІХ нод що відповідають предикату.
     *
     * @param node початкова нода для пошуку
     * @param predicate умова для пошуку
     * @param results список для накопичення результатів
     */
    private fun findAllNodesInternal(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        // Перевіряємо поточну ноду
        if (predicate(node)) {
            results.add(node)
        }

        // Рекурсивний пошук у дочірніх елементах
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllNodesInternal(child, predicate, results)
            }
        }
    }

    /**
     * Пошук ноди по resource-id.
     *
     * @param resourceId ID ресурсу (наприклад "scheduled_rides_button" або повний "com.lyft.android.driver:id/scheduled_rides_button")
     * @return AccessibilityNodeInfo або null якщо не знайдено
     */
    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = captureUIHierarchy() ?: return null
        return findNodeById(root, resourceId)
    }

    /**
     * Пошук ноди по resource-id з вказаним root (для тестування).
     */
    internal fun findNodeById(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        // Додаємо повний package prefix якщо не вказано
        val fullId = if (resourceId.contains(":")) {
            resourceId
        } else {
            "com.lyft.android.driver:id/$resourceId"
        }

        Log.d(TAG, "findNodeById: Searching for id='$fullId' (input: '$resourceId')")

        // Спочатку шукаємо з повним ID
        var result = findNode(root) { node ->
            node.viewIdResourceName == fullId
        }

        // Якщо не знайдено, пробуємо шукати без package prefix (для Compose elements)
        if (result == null && !resourceId.contains(":")) {
            Log.d(TAG, "findNodeById: Not found with full id, trying short id '$resourceId'")
            result = findNode(root) { node ->
                node.viewIdResourceName == resourceId ||
                node.viewIdResourceName?.endsWith(":id/$resourceId") == true
            }
        }

        if (result != null) {
            Log.d(TAG, "findNodeById: Found node with viewIdResourceName='${result.viewIdResourceName}'")
        } else {
            Log.d(TAG, "findNodeById: Node not found with id=$fullId or $resourceId")
        }

        return result
    }

    /**
     * Пошук ноди по content-description.
     *
     * @param desc текст content-description для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return AccessibilityNodeInfo або null якщо не знайдено
     */
    fun findNodeByContentDesc(desc: String, exactMatch: Boolean = true): AccessibilityNodeInfo? {
        val root = captureUIHierarchy() ?: return null
        return findNodeByContentDesc(root, desc, exactMatch)
    }

    /**
     * Пошук ноди по content-description з вказаним root (для тестування).
     */
    internal fun findNodeByContentDesc(root: AccessibilityNodeInfo, desc: String, exactMatch: Boolean = true): AccessibilityNodeInfo? {
        val result = findNode(root) { node ->
            val contentDesc = node.contentDescription?.toString()
            if (contentDesc == null) {
                false
            } else if (exactMatch) {
                contentDesc == desc
            } else {
                contentDesc.contains(desc, ignoreCase = true)
            }
        }

        if (result != null) {
            Log.d(TAG, "findNodeByContentDesc: Found node with contentDesc containing '$desc'")
        } else {
            Log.d(TAG, "findNodeByContentDesc: Node not found with contentDesc '$desc'")
        }

        return result
    }

    /**
     * Пошук ноди по тексту.
     *
     * @param text текст для пошуку
     * @param exactMatch true для точного співпадіння, false для contains (default)
     * @return AccessibilityNodeInfo або null якщо не знайдено
     */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = captureUIHierarchy() ?: return null
        return findNodeByText(root, text, exactMatch)
    }

    /**
     * Пошук ноди по тексту з вказаним root (для тестування).
     */
    internal fun findNodeByText(root: AccessibilityNodeInfo, text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val result = findNode(root) { node ->
            val nodeText = node.text?.toString()
            if (nodeText == null) {
                false
            } else if (exactMatch) {
                nodeText == text
            } else {
                nodeText.contains(text, ignoreCase = true)
            }
        }

        if (result != null) {
            Log.d(TAG, "findNodeByText: Found node with text containing '$text'")
        } else {
            Log.d(TAG, "findNodeByText: Node not found with text '$text'")
        }

        return result
    }

    /**
     * Пошук ВСІХ нод по resource-id.
     *
     * @param resourceId ID ресурсу
     * @return список всіх нод з цим ID (може бути пустим)
     */
    fun findAllNodesById(resourceId: String): List<AccessibilityNodeInfo> {
        val root = captureUIHierarchy() ?: return emptyList()
        return findAllNodesById(root, resourceId)
    }

    /**
     * Пошук ВСІХ нод по resource-id з вказаним root (для тестування).
     */
    internal fun findAllNodesById(root: AccessibilityNodeInfo, resourceId: String): List<AccessibilityNodeInfo> {
        val fullId = if (resourceId.contains(":")) {
            resourceId
        } else {
            "com.lyft.android.driver:id/$resourceId"
        }

        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesInternal(root, { it.viewIdResourceName == fullId }, results)

        Log.d(TAG, "findAllNodesById: Found ${results.size} nodes with id=$fullId")
        return results
    }

    /**
     * Пошук ВСІХ нод по content-description.
     *
     * @param desc текст content-description для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return список всіх нод (може бути пустим)
     */
    fun findAllNodesByContentDesc(desc: String, exactMatch: Boolean = true): List<AccessibilityNodeInfo> {
        val root = captureUIHierarchy() ?: return emptyList()
        return findAllNodesByContentDesc(root, desc, exactMatch)
    }

    /**
     * Пошук ВСІХ нод по content-description з вказаним root (для тестування).
     */
    internal fun findAllNodesByContentDesc(root: AccessibilityNodeInfo, desc: String, exactMatch: Boolean = true): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesInternal(root, { node ->
            val contentDesc = node.contentDescription?.toString()
            if (contentDesc == null) {
                false
            } else if (exactMatch) {
                contentDesc == desc
            } else {
                contentDesc.contains(desc, ignoreCase = true)
            }
        }, results)

        Log.d(TAG, "findAllNodesByContentDesc: Found ${results.size} nodes with contentDesc '$desc'")
        return results
    }

    /**
     * Пошук ВСІХ нод по тексту.
     *
     * @param text текст для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return список всіх нод (може бути пустим)
     */
    fun findAllNodesByText(text: String, exactMatch: Boolean = false): List<AccessibilityNodeInfo> {
        val root = captureUIHierarchy() ?: return emptyList()
        return findAllNodesByText(root, text, exactMatch)
    }

    /**
     * Пошук ВСІХ нод по тексту з вказаним root (для тестування).
     */
    internal fun findAllNodesByText(root: AccessibilityNodeInfo, text: String, exactMatch: Boolean = false): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesInternal(root, { node ->
            val nodeText = node.text?.toString()
            if (nodeText == null) {
                false
            } else if (exactMatch) {
                nodeText == text
            } else {
                nodeText.contains(text, ignoreCase = true)
            }
        }, results)

        Log.d(TAG, "findAllNodesByText: Found ${results.size} nodes with text '$text'")
        return results
    }

    /**
     * Пошук ноди за кастомним предикатом.
     * Дозволяє виконувати складні пошуки з комбінацією умов.
     *
     * @param predicate умова для пошуку
     * @return AccessibilityNodeInfo або null
     */
    fun findNodeByPredicate(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = captureUIHierarchy() ?: return null
        return findNode(root, predicate)
    }

    /**
     * Пошук ВСІХ нод за кастомним предикатом.
     *
     * @param predicate умова для пошуку
     * @return список всіх нод що відповідають умові
     */
    fun findAllNodesByPredicate(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = captureUIHierarchy() ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesInternal(root, predicate, results)
        return results
    }

    // ==================== Lyft-Specific Find Methods ====================
    // Ці методи шукають елементи конкретно у вікні Lyft Driver,
    // навіть якщо активне інше вікно (наприклад, Skeddy).

    /**
     * Пошук ноди по resource-id у вікні Lyft Driver.
     *
     * @param resourceId ID ресурсу (з або без package prefix)
     * @return AccessibilityNodeInfo або null
     */
    fun findLyftNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = captureLyftUIHierarchy() ?: return null
        return findNodeById(root, resourceId)
    }

    /**
     * Пошук ноди по content-description у вікні Lyft Driver.
     *
     * @param desc текст content-description для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return AccessibilityNodeInfo або null
     */
    fun findLyftNodeByContentDesc(desc: String, exactMatch: Boolean = true): AccessibilityNodeInfo? {
        val root = captureLyftUIHierarchy() ?: return null
        return findNodeByContentDesc(root, desc, exactMatch)
    }

    /**
     * Пошук ноди по тексту у вікні Lyft Driver.
     *
     * @param text текст для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return AccessibilityNodeInfo або null
     */
    fun findLyftNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = captureLyftUIHierarchy() ?: return null
        return findNodeByText(root, text, exactMatch)
    }

    /**
     * Збирає всі текстові ноди з Lyft Driver для діагностики.
     * Використовується для debug-логування, щоб побачити що саме бачить AccessibilityService.
     *
     * @return список всіх текстів з нод у Lyft window
     */
    fun collectAllLyftTextNodes(): List<String> {
        val root = captureLyftUIHierarchy()
        if (root == null) {
            Log.w(TAG, "collectAllLyftTextNodes: captureLyftUIHierarchy returned null")
            return emptyList()
        }

        val texts = mutableListOf<String>()
        collectAllTexts(root, texts)
        return texts
    }

    /**
     * Рекурсивно збирає всі тексти з дерева нод.
     */
    private fun collectAllTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return

        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            texts.add(nodeText)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectAllTexts(child, texts)
        }
    }

    /**
     * Пошук ноди по contentDescription у вікні Lyft Driver.
     *
     * @param desc текст contentDescription для пошуку
     * @param exactMatch true для точного співпадіння, false для contains
     * @return AccessibilityNodeInfo або null
     */
    fun findLyftNodeByContentDescription(desc: String, exactMatch: Boolean = true): AccessibilityNodeInfo? {
        val root = captureLyftUIHierarchy() ?: return null
        return findNodeByContentDesc(root, desc, exactMatch)
    }

    /**
     * Пошук ВСІХ нод по resource-id у вікні Lyft Driver.
     *
     * @param resourceId ID ресурсу
     * @return список всіх нод з цим ID (може бути пустим)
     */
    fun findAllLyftNodesById(resourceId: String): List<AccessibilityNodeInfo> {
        val root = captureLyftUIHierarchy() ?: return emptyList()
        return findAllNodesById(root, resourceId)
    }

    // ==================== Click & Gesture Methods ====================

    /**
     * Виконує клік на AccessibilityNodeInfo.
     * Якщо нода не clickable, рекурсивно шукає clickable parent.
     *
     * @param node нода для кліку
     * @return true якщо клік успішний, false якщо не вдалося
     */
    fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "performClickOnNode: click on ${node.className} - success=$result")
                result
            } else {
                // Шукаємо clickable parent
                val parent = node.parent
                if (parent != null) {
                    Log.d(TAG, "performClickOnNode: node not clickable, trying parent")
                    performClickOnNode(parent)
                } else {
                    Log.w(TAG, "performClickOnNode: no clickable node found in hierarchy")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "performClickOnNode: failed", e)
            false
        }
    }

    /**
     * Виконує клік по координатах через GestureDescription.
     *
     * @param x координата X
     * @param y координата Y
     * @return true якщо gesture dispatch успішний, false якщо не вдалося
     */
    fun performClick(x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()

            val result = dispatchGesture(gesture, null, null)
            Log.d(TAG, "performClick: tap at ($x, $y) - dispatched=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performClick: failed at ($x, $y)", e)
            false
        }
    }

    /**
     * Виконує клік по центру ноди через GestureDescription.
     * Корисно коли performAction не працює для деяких UI елементів.
     *
     * @param node нода для кліку
     * @return true якщо gesture dispatch успішний, false якщо не вдалося
     */
    fun performClickOnNodeByCoordinates(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        Log.d(TAG, "performClickOnNodeByCoordinates: clicking center of ${node.className} at ($centerX, $centerY)")
        return performClick(centerX, centerY)
    }

    /**
     * Виконує long click на AccessibilityNodeInfo.
     * Якщо нода не long-clickable, рекурсивно шукає long-clickable parent.
     *
     * @param node нода для long click
     * @return true якщо long click успішний, false якщо не вдалося
     */
    fun performLongClickOnNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isLongClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                Log.d(TAG, "performLongClickOnNode: long click on ${node.className} - success=$result")
                result
            } else {
                // Шукаємо long-clickable parent
                val parent = node.parent
                if (parent != null) {
                    Log.d(TAG, "performLongClickOnNode: node not long-clickable, trying parent")
                    performLongClickOnNode(parent)
                } else {
                    Log.w(TAG, "performLongClickOnNode: no long-clickable node found in hierarchy")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "performLongClickOnNode: failed", e)
            false
        }
    }

    /**
     * Виконує long click по координатах через GestureDescription.
     *
     * @param x координата X
     * @param y координата Y
     * @param durationMs тривалість long click (default 500ms)
     * @return true якщо gesture dispatch успішний, false якщо не вдалося
     */
    fun performLongClick(x: Int, y: Int, durationMs: Long = LONG_CLICK_DURATION_MS): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val result = dispatchGesture(gesture, null, null)
            Log.d(TAG, "performLongClick: long tap at ($x, $y) duration=${durationMs}ms - dispatched=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performLongClick: failed at ($x, $y)", e)
            false
        }
    }

    /**
     * Виконує long click по центру ноди через GestureDescription.
     *
     * @param node нода для long click
     * @param durationMs тривалість long click (default 500ms)
     * @return true якщо gesture dispatch успішний, false якщо не вдалося
     */
    fun performLongClickOnNodeByCoordinates(node: AccessibilityNodeInfo, durationMs: Long = LONG_CLICK_DURATION_MS): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        Log.d(TAG, "performLongClickOnNodeByCoordinates: long clicking center of ${node.className} at ($centerX, $centerY)")
        return performLongClick(centerX, centerY, durationMs)
    }

    /**
     * Виконує swipe gesture від однієї точки до іншої.
     * Корисно для scroll та drag операцій.
     *
     * @param startX початкова координата X
     * @param startY початкова координата Y
     * @param endX кінцева координата X
     * @param endY кінцева координата Y
     * @param durationMs тривалість swipe (default 300ms)
     * @return true якщо gesture dispatch успішний, false якщо не вдалося
     */
    fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val result = dispatchGesture(gesture, null, null)
            Log.d(TAG, "performSwipe: from ($startX, $startY) to ($endX, $endY) duration=${durationMs}ms - dispatched=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe: failed from ($startX, $startY) to ($endX, $endY)", e)
            false
        }
    }

    /**
     * Виконує scroll вниз на екрані.
     * Swipe від центру екрану вгору (контент рухається вниз).
     *
     * @param screenHeight висота екрану
     * @param screenWidth ширина екрану
     * @param durationMs тривалість swipe
     * @return true якщо gesture dispatch успішний
     */
    fun performScrollDown(screenWidth: Int, screenHeight: Int, durationMs: Long = DEFAULT_SWIPE_DURATION_MS): Boolean {
        val centerX = screenWidth / 2
        val startY = (screenHeight * 0.7).toInt()
        val endY = (screenHeight * 0.3).toInt()
        Log.d(TAG, "performScrollDown: swiping up to scroll down")
        return performSwipe(centerX, startY, centerX, endY, durationMs)
    }

    /**
     * Виконує scroll вгору на екрані.
     * Swipe від центру екрану вниз (контент рухається вгору).
     *
     * @param screenHeight висота екрану
     * @param screenWidth ширина екрану
     * @param durationMs тривалість swipe
     * @return true якщо gesture dispatch успішний
     */
    fun performScrollUp(screenWidth: Int, screenHeight: Int, durationMs: Long = DEFAULT_SWIPE_DURATION_MS): Boolean {
        val centerX = screenWidth / 2
        val startY = (screenHeight * 0.3).toInt()
        val endY = (screenHeight * 0.7).toInt()
        Log.d(TAG, "performScrollUp: swiping down to scroll up")
        return performSwipe(centerX, startY, centerX, endY, durationMs)
    }

    // ==================== Pinch-to-Zoom Methods ====================

    /**
     * Performs a symmetric pinch-to-zoom gesture using two simultaneous touch points.
     *
     * For ZOOM OUT: startDistance < endDistance (fingers move apart).
     * For ZOOM IN: startDistance > endDistance (fingers move together).
     *
     * Both fingers move along the Y axis symmetrically from the center point
     * to avoid shifting the map center.
     *
     * @param centerX center X coordinate of the pinch
     * @param centerY center Y coordinate of the pinch
     * @param startDistance initial distance from center to each finger (pixels)
     * @param endDistance final distance from center to each finger (pixels)
     * @param durationMs duration of the gesture in milliseconds
     * @return true if gesture was dispatched successfully
     */
    fun performPinchToZoom(
        centerX: Int,
        centerY: Int,
        startDistance: Int,
        endDistance: Int,
        durationMs: Long = 500L
    ): Boolean {
        return try {
            val gesture = buildPinchGesture(centerX, centerY, startDistance, endDistance, durationMs)
            val result = dispatchGesture(gesture, null, null)
            Log.d(TAG, "performPinchToZoom: center=($centerX,$centerY) " +
                "dist=$startDistance->$endDistance duration=${durationMs}ms dispatched=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performPinchToZoom: failed", e)
            false
        }
    }

    /**
     * Suspend version of [performPinchToZoom] that waits for gesture completion.
     * Returns true only when the gesture has fully completed (not just dispatched).
     */
    suspend fun performPinchToZoomAwait(
        centerX: Int,
        centerY: Int,
        startDistance: Int,
        endDistance: Int,
        durationMs: Long = 500L
    ): Boolean {
        return try {
            val gesture = buildPinchGesture(centerX, centerY, startDistance, endDistance, durationMs)
            val completed = CompletableDeferred<Boolean>()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "performPinchToZoomAwait: gesture COMPLETED")
                    completed.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "performPinchToZoomAwait: gesture CANCELLED")
                    completed.complete(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.w(TAG, "performPinchToZoomAwait: dispatch returned false")
                return false
            }
            Log.d(TAG, "performPinchToZoomAwait: center=($centerX,$centerY) " +
                "dist=$startDistance->$endDistance duration=${durationMs}ms — waiting for completion...")
            completed.await()
        } catch (e: Exception) {
            Log.e(TAG, "performPinchToZoomAwait: failed", e)
            false
        }
    }

    private fun buildPinchGesture(
        centerX: Int,
        centerY: Int,
        startDistance: Int,
        endDistance: Int,
        durationMs: Long
    ): GestureDescription {
        // Diagonal pinch: fingers move along both X and Y axes (45° angle)
        // This is more natural and better recognized by map views like MapLibre
        val diagStart = (startDistance * 0.707f).toInt() // cos(45°) ≈ 0.707
        val diagEnd = (endDistance * 0.707f).toInt()

        // Finger 1 (top-left → center): moves diagonally
        val path1 = Path().apply {
            moveTo((centerX - diagStart).toFloat(), (centerY - diagStart).toFloat())
            lineTo((centerX - diagEnd).toFloat(), (centerY - diagEnd).toFloat())
        }

        // Finger 2 (bottom-right → center): moves diagonally
        val path2 = Path().apply {
            moveTo((centerX + diagStart).toFloat(), (centerY + diagStart).toFloat())
            lineTo((centerX + diagEnd).toFloat(), (centerY + diagEnd).toFloat())
        }

        Log.d(TAG, "buildPinchGesture: finger1=(${centerX - diagStart},${centerY - diagStart})->(${centerX - diagEnd},${centerY - diagEnd}) " +
            "finger2=(${centerX + diagStart},${centerY + diagStart})->(${centerX + diagEnd},${centerY + diagEnd})")

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()
    }

    // ==================== Debug & Testing Methods ====================

    /**
     * Реєструє BroadcastReceiver для debug тестування через ADB.
     * Використання:
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_CLICK
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_SCREEN_DETECT
     */
    private fun registerDebugReceiver() {
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_TEST_CLICK -> {
                        Log.i(TAG, "Debug broadcast received, running click tests...")
                        runClickTests()
                    }
                    ACTION_TEST_SCREEN_DETECT -> {
                        Log.i(TAG, "Debug broadcast received, testing screen detection...")
                        runScreenDetectionTest()
                    }
                    ACTION_TEST_NAVIGATION -> {
                        val step = intent.getStringExtra("step") ?: "menu"
                        Log.i(TAG, "Debug broadcast received, testing navigation step=$step...")
                        runNavigationTest(step)
                    }
                    ACTION_TEST_SYSTEM_DIALOG -> {
                        val action = intent.getStringExtra("action") ?: "check"
                        Log.i(TAG, "Debug broadcast received, testing system dialog action=$action...")
                        runSystemDialogTest(action)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_CLICK)
            addAction(ACTION_TEST_SCREEN_DETECT)
            addAction(ACTION_TEST_NAVIGATION)
            addAction(ACTION_TEST_SYSTEM_DIALOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(debugReceiver, filter)
        }
        Log.d(TAG, "Debug receiver registered.")
        Log.d(TAG, "  - Click test: adb shell am broadcast -a $ACTION_TEST_CLICK")
        Log.d(TAG, "  - Screen detect: adb shell am broadcast -a $ACTION_TEST_SCREEN_DETECT")
        Log.d(TAG, "  - Navigation test: adb shell am broadcast -a $ACTION_TEST_NAVIGATION --es step menu|scheduled|back")
        Log.d(TAG, "  - System dialog test: adb shell am broadcast -a $ACTION_TEST_SYSTEM_DIALOG --es action check|dismiss")
    }

    /**
     * Відміна реєстрації debug receiver.
     */
    private fun unregisterDebugReceiver() {
        debugReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Debug receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister debug receiver", e)
            }
        }
        debugReceiver = null
    }

    /**
     * Виконує серію тестів для перевірки click методів.
     * Тестування:
     * 1. performClickOnNode - клік на знайдену ноду
     * 2. performClick - клік по координатах центру екрану
     * 3. performSwipe - swipe жест
     */
    private fun runClickTests() {
        Log.i(TAG, "=== Starting Click Tests ===")

        val root = captureUIHierarchy()
        if (root == null) {
            Log.e(TAG, "TEST FAILED: Cannot capture UI hierarchy")
            return
        }

        Log.i(TAG, "UI Root: package=${root.packageName}, class=${root.className}")

        // Test 1: Знайти будь-яку clickable ноду та клікнути
        Log.i(TAG, "--- Test 1: performClickOnNode ---")
        val clickableNode = findNodeByPredicate { it.isClickable && it.isVisibleToUser }
        if (clickableNode != null) {
            Log.i(TAG, "Found clickable node: ${clickableNode.className}, text=${clickableNode.text}")
            val bounds = Rect()
            clickableNode.getBoundsInScreen(bounds)
            Log.i(TAG, "Node bounds: $bounds")
            // НЕ виконуємо реальний клік в тесті щоб не зіпсувати UI стан
            // val result = performClickOnNode(clickableNode)
            Log.i(TAG, "Test 1 PASSED: Found clickable node (click skipped to preserve UI state)")
        } else {
            Log.w(TAG, "Test 1 SKIPPED: No clickable node found")
        }

        // Test 2: Тест performClick по координатах (центр екрану)
        Log.i(TAG, "--- Test 2: performClick by coordinates ---")
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val centerX = rootBounds.centerX()
        val centerY = rootBounds.centerY()
        Log.i(TAG, "Screen center: ($centerX, $centerY)")
        // НЕ виконуємо реальний клік
        // val clickResult = performClick(centerX, centerY)
        Log.i(TAG, "Test 2 PASSED: Would click at ($centerX, $centerY)")

        // Test 3: Тест performSwipe
        Log.i(TAG, "--- Test 3: performSwipe ---")
        val startY = (rootBounds.height() * 0.7).toInt()
        val endY = (rootBounds.height() * 0.3).toInt()
        Log.i(TAG, "Swipe coordinates: from ($centerX, $startY) to ($centerX, $endY)")
        // НЕ виконуємо реальний swipe
        // val swipeResult = performSwipe(centerX, startY, centerX, endY)
        Log.i(TAG, "Test 3 PASSED: Would swipe from ($centerX, $startY) to ($centerX, $endY)")

        // Test 4: Реальний тест dispatch gesture (tap на безпечній позиції за межами UI)
        Log.i(TAG, "--- Test 4: Real gesture dispatch test ---")
        val safeX = 10
        val safeY = 10
        val gestureResult = performClick(safeX, safeY)
        Log.i(TAG, "Test 4: Gesture dispatch at ($safeX, $safeY) - result=$gestureResult")

        Log.i(TAG, "=== Click Tests Completed ===")
    }

    /**
     * Тестує LyftNavigator.detectCurrentScreen().
     * Виводить результат детекції екрану в логи.
     * Використання: adb shell am broadcast -a com.skeddy.accessibility.TEST_SCREEN_DETECT
     */
    private fun runScreenDetectionTest() {
        Log.i(TAG, "=== Starting Screen Detection Test ===")

        val root = captureUIHierarchy()
        if (root == null) {
            Log.e(TAG, "TEST FAILED: Cannot capture UI hierarchy")
            return
        }

        Log.i(TAG, "UI Root: package=${root.packageName}, class=${root.className}")

        // Створюємо LyftNavigator та тестуємо detectCurrentScreen
        val navigator = com.skeddy.navigation.LyftNavigator(this)
        val detectedScreen = navigator.detectCurrentScreen()

        Log.i(TAG, "===========================================")
        Log.i(TAG, "DETECTED SCREEN: $detectedScreen")
        Log.i(TAG, "===========================================")

        // Додатково логуємо UI дерево для аналізу
        Log.i(TAG, "Dumping UI tree for analysis...")
        logUITreeDump()

        Log.i(TAG, "=== Screen Detection Test Completed ===")
    }

    /**
     * Тестує LyftNavigator навігаційні методи.
     * Використання:
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_NAVIGATION --es step menu
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_NAVIGATION --es step scheduled
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_NAVIGATION --es step back
     *
     * @param step навігаційний крок для тестування: "menu", "scheduled", або "back"
     */
    private fun runNavigationTest(step: String) {
        Log.i(TAG, "=== Starting Navigation Test: step=$step ===")

        val navigator = com.skeddy.navigation.LyftNavigator(this)

        // Спершу визначаємо поточний екран
        val currentScreen = navigator.detectCurrentScreen()
        Log.i(TAG, "Current screen before navigation: $currentScreen")

        // Для safe методів потрібен coroutine scope
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

        when (step) {
            // Базові методи (без retry)
            "menu" -> {
                Log.i(TAG, "--- Testing navigateToMenu() ---")
                val result = navigator.navigateToMenu()
                logNavigationResult(step, result, navigator)
            }
            "scheduled" -> {
                Log.i(TAG, "--- Testing navigateToScheduledRides() ---")
                val result = navigator.navigateToScheduledRides()
                logNavigationResult(step, result, navigator)
            }
            "back" -> {
                Log.i(TAG, "--- Testing navigateBack() ---")
                val result = navigator.navigateBack()
                logNavigationResult(step, result, navigator)
            }
            // Safe методи з retry (suspend функції)
            "safe_menu" -> {
                Log.i(TAG, "--- Testing safeNavigateToMenu() with RETRY ---")
                scope.launch {
                    val result = navigator.safeNavigateToMenu()
                    logNavigationResult(step, result, navigator)
                }
            }
            "safe_scheduled" -> {
                Log.i(TAG, "--- Testing safeNavigateToScheduledRides() with RETRY ---")
                scope.launch {
                    val result = navigator.safeNavigateToScheduledRides()
                    logNavigationResult(step, result, navigator)
                }
            }
            "safe_back" -> {
                Log.i(TAG, "--- Testing safeNavigateBack() with RETRY ---")
                scope.launch {
                    val result = navigator.safeNavigateBack()
                    logNavigationResult(step, result, navigator)
                }
            }
            // Повний flow з retry на кожному кроці
            "flow" -> {
                Log.i(TAG, "--- Testing navigateToScheduledRidesFlow() FULL FLOW with RETRY ---")
                scope.launch {
                    val result = navigator.navigateToScheduledRidesFlow()
                    logNavigationResult(step, result, navigator)
                }
            }
            // Accept button detection (для задачі 13.2)
            "accept_detect" -> {
                Log.i(TAG, "--- Testing detectAcceptButton() ---")
                val button = navigator.detectAcceptButton()
                Log.i(TAG, "===========================================")
                if (button != null) {
                    Log.i(TAG, "ACCEPT BUTTON FOUND!")
                    Log.i(TAG, "  Class: ${button.className}")
                    Log.i(TAG, "  Text: ${button.text}")
                    Log.i(TAG, "  ContentDesc: ${button.contentDescription}")
                    Log.i(TAG, "  Clickable: ${button.isClickable}")
                    val bounds = android.graphics.Rect()
                    button.getBoundsInScreen(bounds)
                    Log.i(TAG, "  Bounds: $bounds")
                } else {
                    Log.w(TAG, "ACCEPT BUTTON NOT FOUND")
                    Log.i(TAG, "Make sure you are on RIDE_DETAILS screen")
                }
                Log.i(TAG, "===========================================")
            }
            // Accept button click (для задачі 13.2)
            "accept_click" -> {
                Log.i(TAG, "--- Testing clickAcceptButton() with RETRY ---")
                scope.launch {
                    val result = navigator.clickAcceptButton()
                    Log.i(TAG, "===========================================")
                    Log.i(TAG, "CLICK ACCEPT RESULT: ${if (result) "SUCCESS" else "FAILED"}")
                    Log.i(TAG, "===========================================")
                }
            }
            // Auto-accept full flow (для задачі 13.3)
            // Передумова: знаходимось на SCHEDULED_RIDES і є хоча б один райд
            "auto_accept" -> {
                Log.i(TAG, "--- Testing AutoAcceptManager.autoAcceptRide() FULL FLOW ---")
                scope.launch {
                    testAutoAcceptFlow()
                }
            }
            // БЕЗПЕЧНИЙ ТЕСТ: тільки клік на картку райду, без accept
            // Використовується для дослідження UI екрану RIDE_DETAILS
            "card_click" -> {
                Log.i(TAG, "--- Testing SAFE card click (NO ACCEPT) ---")
                scope.launch {
                    testCardClickOnly()
                }
            }
            // Your Rides tab click (для задачі 14.2)
            "your_rides_click" -> {
                Log.i(TAG, "--- Testing navigateToYourRidesTab() ---")
                val result = navigator.navigateToYourRidesTab()
                Log.i(TAG, "===========================================")
                Log.i(TAG, "YOUR RIDES TAB CLICK: ${if (result) "SUCCESS" else "FAILED"}")
                Log.i(TAG, "===========================================")
            }
            // Your Rides tab detection (для задачі 14.2)
            "your_rides_detect" -> {
                Log.i(TAG, "--- Testing detectYourRidesTab() ---")
                val tab = navigator.detectYourRidesTab()
                Log.i(TAG, "===========================================")
                if (tab != null) {
                    Log.i(TAG, "YOUR RIDES TAB FOUND!")
                    Log.i(TAG, "  Class: ${tab.className}")
                    Log.i(TAG, "  Text: ${tab.text}")
                    Log.i(TAG, "  ContentDesc: ${tab.contentDescription}")
                    Log.i(TAG, "  Clickable: ${tab.isClickable}")
                    val bounds = android.graphics.Rect()
                    tab.getBoundsInScreen(bounds)
                    Log.i(TAG, "  Bounds: $bounds")
                    // Також парсимо кількість
                    val count = navigator.getYourRidesCount()
                    Log.i(TAG, "  Parsed ride count: $count")
                } else {
                    Log.w(TAG, "YOUR RIDES TAB NOT FOUND")
                    Log.i(TAG, "Make sure you are on SCHEDULED_RIDES screen")
                }
                Log.i(TAG, "===========================================")
            }
            else -> {
                Log.e(TAG, "Unknown navigation step: $step")
                Log.e(TAG, "Available steps: menu, scheduled, back, safe_menu, safe_scheduled, safe_back, flow, accept_detect, accept_click, auto_accept, card_click, your_rides_detect, your_rides_click")
            }
        }
    }

    /**
     * Рекурсивно логує текстовий вміст ноди для debug.
     */
    private fun logCardTextContent(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!text.isNullOrEmpty()) {
            Log.i(TAG, "${indent}text: $text")
        }
        if (!contentDesc.isNullOrEmpty() && contentDesc != text) {
            Log.i(TAG, "${indent}desc: $contentDesc")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                logCardTextContent(child, depth + 1)
            }
        }
    }

    private fun logNavigationResult(step: String, result: Boolean, navigator: com.skeddy.navigation.LyftNavigator) {
        Log.i(TAG, "===========================================")
        Log.i(TAG, "NAVIGATION RESULT for '$step': ${if (result) "SUCCESS" else "FAILED"}")
        Log.i(TAG, "===========================================")

        // Даємо час на анімацію та перевіряємо новий стан
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val newScreen = navigator.detectCurrentScreen()
            Log.i(TAG, "Screen after navigation (500ms delay): $newScreen")
            Log.i(TAG, "=== Navigation Test Completed ===")
        }, 500)
    }

    /**
     * БЕЗПЕЧНИЙ ТЕСТ: тільки клік на картку райду без accept.
     * Залишається на екрані RIDE_DETAILS для дослідження UI.
     *
     * Використання:
     * adb shell am broadcast -a com.skeddy.accessibility.TEST_NAVIGATION --es step card_click
     */
    private suspend fun testCardClickOnly() {
        Log.i(TAG, "===========================================")
        Log.i(TAG, "=== SAFE CARD CLICK TEST (NO ACCEPT) ===")
        Log.i(TAG, "===========================================")

        val navigator = com.skeddy.navigation.LyftNavigator(this)
        val currentScreen = navigator.detectCurrentScreen()
        Log.i(TAG, "Current screen: $currentScreen")

        if (currentScreen != com.skeddy.navigation.LyftScreen.SCHEDULED_RIDES) {
            Log.e(TAG, "ERROR: Must be on SCHEDULED_RIDES screen!")
            return
        }

        val lyftRoot = captureLyftUIHierarchy() ?: run {
            Log.e(TAG, "ERROR: Cannot capture Lyft UI")
            return
        }

        val rideCards = com.skeddy.parser.RideParser.findRideCards(lyftRoot)
        Log.i(TAG, "Found ${rideCards.size} ride cards")

        if (rideCards.isEmpty()) {
            Log.e(TAG, "ERROR: No ride cards found!")
            return
        }

        val firstCard = rideCards.first()
        Log.i(TAG, "Clicking on first ride card...")

        val clickResult = navigator.clickOnRideCard(firstCard)
        Log.i(TAG, "Click result: $clickResult")

        // Чекаємо щоб екран завантажився
        kotlinx.coroutines.delay(2000)

        Log.i(TAG, "===========================================")
        Log.i(TAG, "CARD CLICKED - NOW ON RIDE DETAILS SCREEN")
        Log.i(TAG, "Use 'adb shell uiautomator dump' to inspect UI")
        Log.i(TAG, "Use 'adb shell am broadcast ... --es step back' to go back")
        Log.i(TAG, "===========================================")
    }

    /**
     * Тестує повний flow AutoAcceptManager.
     * Передумова: знаходимось на екрані SCHEDULED_RIDES з хоча б одним райдом.
     *
     * Використання:
     * adb shell am broadcast -a com.skeddy.accessibility.TEST_NAVIGATION --es step auto_accept
     */
    private suspend fun testAutoAcceptFlow() {
        Log.i(TAG, "===========================================")
        Log.i(TAG, "=== STARTING AUTO-ACCEPT TEST ===")
        Log.i(TAG, "===========================================")

        val navigator = com.skeddy.navigation.LyftNavigator(this)

        // Перевіряємо чи ми на правильному екрані
        val currentScreen = navigator.detectCurrentScreen()
        Log.i(TAG, "Current screen: $currentScreen")

        if (currentScreen != com.skeddy.navigation.LyftScreen.SCHEDULED_RIDES) {
            Log.e(TAG, "ERROR: Must be on SCHEDULED_RIDES screen!")
            Log.e(TAG, "Current screen: $currentScreen")
            Log.i(TAG, "=== AUTO-ACCEPT TEST ABORTED ===")
            return
        }

        // Шукаємо картки райдів
        val lyftRoot = captureLyftUIHierarchy()
        if (lyftRoot == null) {
            Log.e(TAG, "ERROR: Cannot capture Lyft UI hierarchy")
            Log.i(TAG, "=== AUTO-ACCEPT TEST ABORTED ===")
            return
        }

        val rideCards = com.skeddy.parser.RideParser.findRideCards(lyftRoot)
        Log.i(TAG, "Found ${rideCards.size} ride cards")

        if (rideCards.isEmpty()) {
            Log.e(TAG, "ERROR: No ride cards found on screen!")
            Log.i(TAG, "=== AUTO-ACCEPT TEST ABORTED ===")
            return
        }

        // Беремо перший райд для тесту
        val firstCard = rideCards.first()
        val parsedRide = com.skeddy.parser.RideParser.parseRideCard(firstCard)

        if (parsedRide == null) {
            Log.e(TAG, "ERROR: Could not parse first ride card")
            Log.i(TAG, "=== AUTO-ACCEPT TEST ABORTED ===")
            return
        }

        Log.i(TAG, "Testing with ride:")
        Log.i(TAG, "  ID: ${parsedRide.id}")
        Log.i(TAG, "  Price: \$${parsedRide.price}")
        Log.i(TAG, "  Pickup: ${parsedRide.pickupTime} @ ${parsedRide.pickupLocation}")
        Log.i(TAG, "  Dropoff: ${parsedRide.dropoffLocation}")

        // Create AutoAcceptManager with dependencies for debug testing
        val deviceTokenManager = com.skeddy.network.DeviceTokenManager(this)
        val api = com.skeddy.network.NetworkModule.createService<com.skeddy.network.SkeddyApi>()
        val debugServerClient = com.skeddy.network.SkeddyServerClient(api, deviceTokenManager)
        val blacklistDb = com.skeddy.data.BlacklistDatabase.getInstance(this)
        val debugBlacklistRepo = com.skeddy.data.BlacklistRepository(blacklistDb.blacklistDao())
        val debugPendingQueue = com.skeddy.service.PendingRideQueue(this)
        val autoAcceptManager = com.skeddy.service.AutoAcceptManager(
            this, navigator, debugServerClient, debugBlacklistRepo, debugPendingQueue
        )

        Log.i(TAG, "-------------------------------------------")
        Log.i(TAG, "Starting autoAcceptRide()...")
        Log.i(TAG, "-------------------------------------------")

        val result = autoAcceptManager.autoAcceptRide(firstCard, parsedRide)

        Log.i(TAG, "===========================================")
        when (result) {
            is com.skeddy.service.AutoAcceptResult.Success -> {
                Log.i(TAG, "AUTO-ACCEPT RESULT: SUCCESS!")
                Log.i(TAG, "Ride accepted: \$${result.ride.price}")
            }
            is com.skeddy.service.AutoAcceptResult.RideNotFound -> {
                Log.w(TAG, "AUTO-ACCEPT RESULT: RIDE_NOT_FOUND")
                Log.w(TAG, "Reason: ${result.reason}")
            }
            is com.skeddy.service.AutoAcceptResult.AcceptButtonNotFound -> {
                Log.w(TAG, "AUTO-ACCEPT RESULT: ACCEPT_BUTTON_NOT_FOUND")
                Log.w(TAG, "Reason: ${result.reason}")
            }
            is com.skeddy.service.AutoAcceptResult.ClickFailed -> {
                Log.e(TAG, "AUTO-ACCEPT RESULT: CLICK_FAILED")
                Log.e(TAG, "Reason: ${result.reason}")
            }
            is com.skeddy.service.AutoAcceptResult.ConfirmationTimeout -> {
                Log.w(TAG, "AUTO-ACCEPT RESULT: CONFIRMATION_TIMEOUT")
                Log.w(TAG, "Reason: ${result.reason}")
            }
        }
        Log.i(TAG, "===========================================")

        // Перевіряємо на якому екрані ми опинились
        kotlinx.coroutines.delay(500)
        val finalScreen = navigator.detectCurrentScreen()
        Log.i(TAG, "Final screen: $finalScreen")
        Log.i(TAG, "=== AUTO-ACCEPT TEST COMPLETED ===")
    }

    /**
     * Тестує SystemDialogHandler.
     * Використання:
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_SYSTEM_DIALOG --es action check
     * - adb shell am broadcast -a com.skeddy.accessibility.TEST_SYSTEM_DIALOG --es action dismiss
     *
     * @param action дія для тестування: "check" або "dismiss"
     */
    private fun runSystemDialogTest(action: String) {
        Log.i(TAG, "=== Starting System Dialog Test: action=$action ===")

        val handler = com.skeddy.recovery.SystemDialogHandler(this)

        when (action) {
            "check" -> {
                Log.i(TAG, "--- Testing isSystemDialogPresent() ---")
                val isPresent = handler.isSystemDialogPresent()
                Log.i(TAG, "===========================================")
                Log.i(TAG, "SYSTEM DIALOG PRESENT: $isPresent")
                if (isPresent) {
                    val info = handler.getSystemDialogInfo()
                    Log.i(TAG, "Dialog info: $info")
                }
                Log.i(TAG, "===========================================")
            }
            "dismiss" -> {
                Log.i(TAG, "--- Testing tryDismissDialog() ---")
                val result = handler.tryDismissDialog()
                Log.i(TAG, "===========================================")
                Log.i(TAG, "DISMISS RESULT: $result")
                Log.i(TAG, "===========================================")

                // Перевіряємо результат через 500ms
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val stillPresent = handler.isSystemDialogPresent()
                    Log.i(TAG, "Dialog still present after 500ms: $stillPresent")
                    Log.i(TAG, "=== System Dialog Test Completed ===")
                }, 500)
            }
            else -> {
                Log.e(TAG, "Unknown action: $action")
                Log.e(TAG, "Available actions: check, dismiss")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: Accessibility Service перервано")
        // Очистка ресурсів при перериванні
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: Accessibility Service відключається")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Accessibility Service знищується")
        unregisterDebugReceiver()
        instance = null
        super.onDestroy()
    }
}
