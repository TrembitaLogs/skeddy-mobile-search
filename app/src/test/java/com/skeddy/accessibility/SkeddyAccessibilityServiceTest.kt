package com.skeddy.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Юніт-тести для методів пошуку нод SkeddyAccessibilityService.
 *
 * Тестова стратегія:
 * 1. Mock AccessibilityNodeInfo для перевірки рекурсивного пошуку
 * 2. Перевірка пошуку на різній глибині дерева (1-10 рівнів)
 * 3. Тестування пошуку по ID, contentDescription, text
 * 4. Тестування exact/partial match
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SkeddyAccessibilityServiceTest {

    private lateinit var service: SkeddyAccessibilityService

    @Before
    fun setUp() {
        service = SkeddyAccessibilityService()
        resetLastForegroundPackage()
    }

    @After
    fun tearDown() {
        resetLastForegroundPackage()
    }

    /**
     * Resets the static lastForegroundPackage field via reflection.
     * The field lives on the outer class as a private static volatile String.
     */
    private fun resetLastForegroundPackage() {
        val field = SkeddyAccessibilityService::class.java
            .getDeclaredField("lastForegroundPackage")
        field.isAccessible = true
        field.set(null, null)
    }

    /**
     * Creates a TYPE_WINDOW_STATE_CHANGED AccessibilityEvent with the given package and class.
     */
    @Suppress("DEPRECATION")
    private fun createWindowStateEvent(packageName: String, className: String = "android.app.Activity"): AccessibilityEvent {
        return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
            this.className = className
        }
    }

    // ==================== Foreground Package Tracking Tests ====================

    @Test
    fun `foreground tracking updates for third-party app`() {
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))

        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking ignores own package`() {
        // Set Lyft as the foreground app first
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // Skeddy event should NOT overwrite the foreground package
        service.onAccessibilityEvent(createWindowStateEvent("com.skeddy"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking ignores system UI`() {
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))

        service.onAccessibilityEvent(createWindowStateEvent("com.android.systemui"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking ignores launcher packages`() {
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))

        // Various launcher package names should all be ignored
        service.onAccessibilityEvent(createWindowStateEvent("com.google.android.apps.nexuslauncher"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        service.onAccessibilityEvent(createWindowStateEvent("com.sec.android.app.launcher"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        service.onAccessibilityEvent(createWindowStateEvent("com.android.launcher3"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking updates for non-transitional app`() {
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // A different third-party app should update the foreground package
        service.onAccessibilityEvent(createWindowStateEvent("com.uber.driver"))
        assertEquals("com.uber.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking survives full automation cycle`() {
        // Simulates a full search automation cycle:
        // Lyft → Skeddy → Launcher → Lyft
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // Skeddy opens briefly to run search logic
        service.onAccessibilityEvent(createWindowStateEvent("com.skeddy"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // System UI (notification shade or status bar)
        service.onAccessibilityEvent(createWindowStateEvent("com.android.systemui"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // Launcher flashes during app switch
        service.onAccessibilityEvent(createWindowStateEvent("com.sec.android.app.launcher"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())

        // Lyft comes back to foreground
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking is null initially`() {
        assertNull(SkeddyAccessibilityService.getLastForegroundPackage())
    }

    @Test
    fun `foreground tracking ignores events with null package`() {
        service.onAccessibilityEvent(createWindowStateEvent("com.lyft.android.driver"))

        @Suppress("DEPRECATION")
        val nullPkgEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            className = "android.app.Activity"
            // packageName left as null
        }
        service.onAccessibilityEvent(nullPkgEvent)
        assertEquals("com.lyft.android.driver", SkeddyAccessibilityService.getLastForegroundPackage())
    }

    // ==================== Helper Methods ====================

    /**
     * Створює mock AccessibilityNodeInfo з вказаними параметрами.
     */
    private fun createMockNode(
        viewId: String? = null,
        text: String? = null,
        contentDesc: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        return mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { viewIdResourceName } returns viewId
            every { this@mockk.text } returns text
            every { contentDescription } returns contentDesc
            every { childCount } returns children.size
            children.forEachIndexed { index, child ->
                every { getChild(index) } returns child
            }
        }
    }

    /**
     * Створює дерево нод вказаної глибини.
     * На кожному рівні є одна нода з унікальним ID.
     */
    private fun createTreeWithDepth(depth: Int, targetDepth: Int = depth): AccessibilityNodeInfo {
        if (depth <= 0) {
            return createMockNode(
                viewId = "com.lyft.android.driver:id/target_node",
                text = "Target at depth $targetDepth"
            )
        }
        val child = createTreeWithDepth(depth - 1, targetDepth)
        return createMockNode(
            viewId = "com.lyft.android.driver:id/level_$depth",
            children = listOf(child)
        )
    }

    // ==================== findNodeById Tests ====================

    @Test
    fun `findNodeById returns null when node not found`() {
        val root = createMockNode(viewId = "com.lyft.android.driver:id/other_id")

        val result = service.findNodeById(root, "nonexistent_id")

        assertNull(result)
    }

    @Test
    fun `findNodeById finds node at root level`() {
        val root = createMockNode(viewId = "com.lyft.android.driver:id/target_id")

        val result = service.findNodeById(root, "target_id")

        assertNotNull(result)
        assertEquals("com.lyft.android.driver:id/target_id", result?.viewIdResourceName)
    }

    @Test
    fun `findNodeById finds node at depth 3`() {
        val target = createMockNode(viewId = "com.lyft.android.driver:id/deep_node")
        val level2 = createMockNode(viewId = "com.lyft.android.driver:id/level2", children = listOf(target))
        val level1 = createMockNode(viewId = "com.lyft.android.driver:id/level1", children = listOf(level2))
        val root = createMockNode(viewId = "com.lyft.android.driver:id/root", children = listOf(level1))

        val result = service.findNodeById(root, "deep_node")

        assertNotNull(result)
        assertEquals("com.lyft.android.driver:id/deep_node", result?.viewIdResourceName)
    }

    @Test
    fun `findNodeById handles short ID without package prefix`() {
        val root = createMockNode(viewId = "com.lyft.android.driver:id/scheduled_rides_button")

        val result = service.findNodeById(root, "scheduled_rides_button")

        assertNotNull(result)
    }

    @Test
    fun `findNodeById handles full ID with package prefix`() {
        val root = createMockNode(viewId = "com.lyft.android.driver:id/menu_button")

        val result = service.findNodeById(root, "com.lyft.android.driver:id/menu_button")

        assertNotNull(result)
    }

    @Test
    fun `findNodeById finds node at depth 10`() {
        val root = createTreeWithDepth(10)

        val result = service.findNodeById(root, "target_node")

        assertNotNull("Node at depth 10 should be found", result)
    }

    // ==================== findNodeByContentDesc Tests ====================

    @Test
    fun `findNodeByContentDesc returns null when not found`() {
        val root = createMockNode(contentDesc = "Other description")

        val result = service.findNodeByContentDesc(root, "Target description")

        assertNull(result)
    }

    @Test
    fun `findNodeByContentDesc finds exact match`() {
        val root = createMockNode(contentDesc = "Menu button")

        val result = service.findNodeByContentDesc(root, "Menu button", exactMatch = true)

        assertNotNull(result)
    }

    @Test
    fun `findNodeByContentDesc exact match is case sensitive`() {
        val root = createMockNode(contentDesc = "Menu Button")

        val result = service.findNodeByContentDesc(root, "menu button", exactMatch = true)

        assertNull("Exact match should be case sensitive", result)
    }

    @Test
    fun `findNodeByContentDesc partial match is case insensitive`() {
        val root = createMockNode(contentDesc = "Scheduled Rides Menu Button")

        val result = service.findNodeByContentDesc(root, "menu button", exactMatch = false)

        assertNotNull("Partial match should find substring ignoring case", result)
    }

    @Test
    fun `findNodeByContentDesc finds node in nested tree`() {
        val target = createMockNode(contentDesc = "Target button")
        val parent = createMockNode(children = listOf(target))
        val root = createMockNode(children = listOf(parent))

        val result = service.findNodeByContentDesc(root, "Target button")

        assertNotNull(result)
    }

    // ==================== findNodeByText Tests ====================

    @Test
    fun `findNodeByText returns null when not found`() {
        val root = createMockNode(text = "Other text")

        val result = service.findNodeByText(root, "Target text")

        assertNull(result)
    }

    @Test
    fun `findNodeByText finds partial match by default`() {
        val root = createMockNode(text = "Ride to Downtown - \$25.00")

        val result = service.findNodeByText(root, "\$25.00")

        assertNotNull("Default should find partial match", result)
    }

    @Test
    fun `findNodeByText exact match works correctly`() {
        val root = createMockNode(text = "\$25.00")

        val result = service.findNodeByText(root, "\$25.00", exactMatch = true)

        assertNotNull(result)
    }

    @Test
    fun `findNodeByText exact match rejects partial`() {
        val root = createMockNode(text = "Price: \$25.00")

        val result = service.findNodeByText(root, "\$25.00", exactMatch = true)

        assertNull("Exact match should reject partial matches", result)
    }

    @Test
    fun `findNodeByText partial match is case insensitive`() {
        val root = createMockNode(text = "SCHEDULED RIDES")

        val result = service.findNodeByText(root, "scheduled", exactMatch = false)

        assertNotNull(result)
    }

    // ==================== findAllNodes Tests ====================

    @Test
    fun `findAllNodesById returns empty list when none found`() {
        val root = createMockNode(viewId = "com.lyft.android.driver:id/other")

        val result = service.findAllNodesById(root, "target")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAllNodesById finds multiple nodes with same ID`() {
        val node1 = createMockNode(viewId = "com.lyft.android.driver:id/ride_item")
        val node2 = createMockNode(viewId = "com.lyft.android.driver:id/ride_item")
        val node3 = createMockNode(viewId = "com.lyft.android.driver:id/ride_item")
        val container = createMockNode(children = listOf(node1, node2, node3))

        val result = service.findAllNodesById(container, "ride_item")

        assertEquals(3, result.size)
    }

    @Test
    fun `findAllNodesByText finds all matching nodes`() {
        val node1 = createMockNode(text = "\$20.00 - Downtown")
        val node2 = createMockNode(text = "\$25.00 - Airport")
        val node3 = createMockNode(text = "\$15.00 - Mall")
        val container = createMockNode(children = listOf(node1, node2, node3))

        val result = service.findAllNodesByText(container, "\$")

        assertEquals("All nodes with $ should be found", 3, result.size)
    }

    @Test
    fun `findAllNodesByContentDesc finds all matching nodes`() {
        val node1 = createMockNode(contentDesc = "Ride option")
        val node2 = createMockNode(contentDesc = "Ride option")
        val other = createMockNode(contentDesc = "Other")
        val container = createMockNode(children = listOf(node1, node2, other))

        val result = service.findAllNodesByContentDesc(container, "Ride option")

        assertEquals(2, result.size)
    }

    // ==================== Depth Tests ====================

    @Test
    fun `search works at depth 1`() {
        val root = createTreeWithDepth(1)
        val result = service.findNodeById(root, "target_node")
        assertNotNull("Depth 1 search failed", result)
    }

    @Test
    fun `search works at depth 5`() {
        val root = createTreeWithDepth(5)
        val result = service.findNodeById(root, "target_node")
        assertNotNull("Depth 5 search failed", result)
    }

    @Test
    fun `search works at depth 10`() {
        val root = createTreeWithDepth(10)
        val result = service.findNodeById(root, "target_node")
        assertNotNull("Depth 10 search failed", result)
    }

    // ==================== Lyft-specific UI Elements Tests ====================

    @Test
    fun `can find scheduled rides button by ID`() {
        val button = createMockNode(viewId = "com.lyft.android.driver:id/scheduled_rides_button")
        val toolbar = createMockNode(children = listOf(button))
        val root = createMockNode(children = listOf(toolbar))

        val result = service.findNodeById(root, "scheduled_rides_button")

        assertNotNull("Should find scheduled rides button", result)
    }

    @Test
    fun `can find menu button by content description`() {
        val menuButton = createMockNode(contentDesc = "Open navigation menu")
        val root = createMockNode(children = listOf(menuButton))

        val result = service.findNodeByContentDesc(root, "navigation menu", exactMatch = false)

        assertNotNull("Should find menu button by partial content desc", result)
    }

    @Test
    fun `can find ride price by text`() {
        val priceText = createMockNode(text = "\$32.50")
        val rideCard = createMockNode(children = listOf(priceText))
        val root = createMockNode(children = listOf(rideCard))

        val result = service.findNodeByText(root, "\$32.50")

        assertNotNull("Should find ride price", result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles null values gracefully`() {
        val nodeWithNulls = createMockNode(viewId = null, text = null, contentDesc = null)

        assertNull(service.findNodeById(nodeWithNulls, "any_id"))
        assertNull(service.findNodeByText(nodeWithNulls, "any text"))
        assertNull(service.findNodeByContentDesc(nodeWithNulls, "any desc"))
    }

    @Test
    fun `handles empty tree`() {
        val emptyRoot = createMockNode()

        val resultById = service.findNodeById(emptyRoot, "any_id")
        val resultByText = service.findNodeByText(emptyRoot, "any")
        val allById = service.findAllNodesById(emptyRoot, "any")

        assertNull(resultById)
        assertNull(resultByText)
        assertTrue(allById.isEmpty())
    }

    @Test
    fun `handles wide tree with many siblings`() {
        val siblings = (1..20).map { i ->
            createMockNode(viewId = "com.lyft.android.driver:id/item_$i")
        }
        val root = createMockNode(children = siblings)

        val result = service.findNodeById(root, "item_15")

        assertNotNull("Should find item in wide tree", result)
    }
}
