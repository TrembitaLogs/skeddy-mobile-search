package com.skeddy.parser

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RideParserTest {

    /**
     * Creates a mock AccessibilityNodeInfo with specified parameters.
     */
    private fun createMockNode(
        viewId: String? = null,
        isClickable: Boolean = false,
        boundsWidth: Int = 0,
        boundsHeight: Int = 100,
        text: String? = null,
        contentDescription: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        val rectSlot = slot<Rect>()
        return mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { viewIdResourceName } returns viewId
            every { this@mockk.isClickable } returns isClickable
            every { childCount } returns children.size
            every { this@mockk.text } returns text
            every { this@mockk.contentDescription } returns contentDescription
            children.forEachIndexed { index, child ->
                every { getChild(index) } returns child
            }
            every { getBoundsInScreen(capture(rectSlot)) } answers {
                rectSlot.captured.set(0, 0, boundsWidth, boundsHeight)
            }
        }
    }

    // ==================== findRideCards - Empty/Null Cases ====================

    @Test
    fun `findRideCards returns empty list for null root`() {
        val result = RideParser.findRideCards(null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findRideCards returns empty list for empty tree`() {
        val root = createMockNode()

        val result = RideParser.findRideCards(root)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findRideCards returns empty list when no ride cards found`() {
        val child1 = createMockNode(viewId = "some_view", isClickable = false)
        val child2 = createMockNode(viewId = "another_view", isClickable = true, boundsWidth = 500)
        val root = createMockNode(children = listOf(child1, child2))

        val result = RideParser.findRideCards(root)

        assertTrue(result.isEmpty())
    }

    // ==================== findRideCards - Width Detection ====================

    @Test
    fun `findRideCards finds clickable card with exact expected width`() {
        val rideCard = createMockNode(
            isClickable = true,
            boundsWidth = 1038
        )
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards finds clickable card within width tolerance - lower bound`() {
        val rideCard = createMockNode(
            isClickable = true,
            boundsWidth = 988 // 1038 - 50
        )
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards finds clickable card within width tolerance - upper bound`() {
        val rideCard = createMockNode(
            isClickable = true,
            boundsWidth = 1088 // 1038 + 50
        )
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards ignores non-clickable card with correct width`() {
        val notClickable = createMockNode(
            isClickable = false,
            boundsWidth = 1038
        )
        val root = createMockNode(children = listOf(notClickable))

        val result = RideParser.findRideCards(root)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findRideCards ignores clickable card outside width tolerance`() {
        val tooNarrow = createMockNode(isClickable = true, boundsWidth = 900)
        val tooWide = createMockNode(isClickable = true, boundsWidth = 1200)
        val root = createMockNode(children = listOf(tooNarrow, tooWide))

        val result = RideParser.findRideCards(root)

        assertTrue(result.isEmpty())
    }

    // ==================== findRideCards - Resource ID Detection ====================

    @Test
    fun `findRideCards finds clickable card with lyft_text child`() {
        val lyftTextChild = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val rideCard = createMockNode(isClickable = true, children = listOf(lyftTextChild))
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards finds clickable card with ride_info child`() {
        val rideInfoChild = createMockNode(viewId = "com.lyft.android.driver:id/ride_info")
        val rideCard = createMockNode(isClickable = true, children = listOf(rideInfoChild))
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards finds clickable card with nested lyft_text grandchild`() {
        val lyftTextGrandchild = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val intermediateChild = createMockNode(children = listOf(lyftTextGrandchild))
        val rideCard = createMockNode(isClickable = true, children = listOf(intermediateChild))
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards resource ID matching is case insensitive`() {
        val upperCaseChild = createMockNode(viewId = "com.lyft.android.driver:id/LYFT_TEXT")
        val rideCard = createMockNode(isClickable = true, children = listOf(upperCaseChild))
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    // ==================== findRideCards - Multiple Cards ====================

    @Test
    fun `findRideCards finds multiple ride cards`() {
        val card1 = createMockNode(isClickable = true, boundsWidth = 1038)
        val card2 = createMockNode(isClickable = true, boundsWidth = 1040)
        val card3 = createMockNode(isClickable = true, boundsWidth = 1035)
        val root = createMockNode(children = listOf(card1, card2, card3))

        val result = RideParser.findRideCards(root)

        assertEquals(3, result.size)
    }

    @Test
    fun `findRideCards finds cards with mixed detection methods`() {
        // Card found by width
        val cardByWidth = createMockNode(isClickable = true, boundsWidth = 1038)
        // Card found by resource ID (must also be clickable)
        val lyftTextChild = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val cardByResourceId = createMockNode(isClickable = true, children = listOf(lyftTextChild))

        val root = createMockNode(children = listOf(cardByWidth, cardByResourceId))

        val result = RideParser.findRideCards(root)

        assertEquals(2, result.size)
    }

    // ==================== findRideCards - Nested Structure ====================

    @Test
    fun `findRideCards finds cards in nested tree`() {
        val rideCard = createMockNode(isClickable = true, boundsWidth = 1038)
        val level2 = createMockNode(children = listOf(rideCard))
        val level1 = createMockNode(children = listOf(level2))
        val root = createMockNode(children = listOf(level1))

        val result = RideParser.findRideCards(root)

        assertEquals(1, result.size)
    }

    @Test
    fun `findRideCards finds cards at different depths`() {
        val shallowCard = createMockNode(isClickable = true, boundsWidth = 1038)
        val deepCard = createMockNode(isClickable = true, boundsWidth = 1040)
        val deepWrapper = createMockNode(children = listOf(deepCard))
        val deeperWrapper = createMockNode(children = listOf(deepWrapper))

        val root = createMockNode(children = listOf(shallowCard, deeperWrapper))

        val result = RideParser.findRideCards(root)

        assertEquals(2, result.size)
    }

    // ==================== findRideCards - Deduplication ====================

    @Test
    fun `findRideCards does not return duplicate cards`() {
        // A card that matches both width AND resource ID criteria
        val lyftTextChild = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val rideCard = createMockNode(
            isClickable = true,
            boundsWidth = 1038,
            children = listOf(lyftTextChild)
        )
        val root = createMockNode(children = listOf(rideCard))

        val result = RideParser.findRideCards(root)

        assertEquals("Should not duplicate card matching multiple criteria", 1, result.size)
    }

    // ==================== Realistic Lyft UI Structure ====================

    @Test
    fun `findRideCards works with realistic Lyft scheduled rides structure`() {
        // Simulate realistic Lyft UI structure
        val priceText = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val riderInfo = createMockNode(viewId = "com.lyft.android.driver:id/ride_info")
        val rideCardContent = createMockNode(children = listOf(priceText, riderInfo))
        val rideCard1 = createMockNode(
            isClickable = true,
            boundsWidth = 1038,
            children = listOf(rideCardContent)
        )

        val rideCard2 = createMockNode(
            isClickable = true,
            boundsWidth = 1038,
            children = listOf(
                createMockNode(children = listOf(
                    createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
                ))
            )
        )

        val recyclerView = createMockNode(children = listOf(rideCard1, rideCard2))
        val screenContent = createMockNode(children = listOf(recyclerView))
        val root = createMockNode(children = listOf(screenContent))

        val result = RideParser.findRideCards(root)

        assertEquals(2, result.size)
    }

    // ==================== parsePrice Tests ====================

    @Test
    fun `parsePrice handles standard format`() {
        assertEquals(9.95, RideParser.parsePrice("$9.95"), 0.001)
    }

    @Test
    fun `parsePrice handles price above threshold`() {
        assertEquals(25.50, RideParser.parsePrice("$25.50"), 0.001)
    }

    @Test
    fun `parsePrice handles high value`() {
        assertEquals(100.00, RideParser.parsePrice("$100.00"), 0.001)
    }

    @Test
    fun `parsePrice handles no cents`() {
        assertEquals(9.0, RideParser.parsePrice("$9"), 0.001)
    }

    @Test
    fun `parsePrice handles comma in thousands`() {
        assertEquals(1500.00, RideParser.parsePrice("$1,500.00"), 0.001)
    }

    @Test
    fun `parsePrice returns zero for invalid input`() {
        assertEquals(0.0, RideParser.parsePrice("invalid"), 0.001)
    }

    @Test
    fun `parsePrice handles extra spaces`() {
        assertEquals(25.50, RideParser.parsePrice(" $25.50 "), 0.001)
    }

    @Test
    fun `parsePrice handles complex thousands format`() {
        assertEquals(1234.56, RideParser.parsePrice("$1,234.56"), 0.001)
    }

    @Test
    fun `parsePrice handles just dollar sign`() {
        assertEquals(0.0, RideParser.parsePrice("$"), 0.001)
    }

    @Test
    fun `parsePrice handles empty string`() {
        assertEquals(0.0, RideParser.parsePrice(""), 0.001)
    }

    @Test
    fun `parsePrice handles negative-like format`() {
        // Price should never be negative in real UI, but parser parses it as is
        // The "-" is retained after removing "$", so it parses to -25.50
        assertEquals(-25.50, RideParser.parsePrice("-$25.50"), 0.001)
    }

    // ==================== parseBonus Tests ====================

    @Test
    fun `parseBonus extracts bonus from standard format`() {
        assertEquals(3.60, RideParser.parseBonus("Incl. \$3.60 bonus")!!, 0.001)
    }

    @Test
    fun `parseBonus extracts bonus with higher value`() {
        assertEquals(10.50, RideParser.parseBonus("Incl. \$10.50 bonus")!!, 0.001)
    }

    @Test
    fun `parseBonus extracts bonus without decimal`() {
        assertEquals(10.0, RideParser.parseBonus("Incl. \$10 bonus")!!, 0.001)
    }

    @Test
    fun `parseBonus returns null for text without dollar sign`() {
        assertNull(RideParser.parseBonus("Incl. bonus"))
    }

    @Test
    fun `parseBonus returns null for empty string`() {
        assertNull(RideParser.parseBonus(""))
    }

    @Test
    fun `parseBonus returns null for invalid text`() {
        assertNull(RideParser.parseBonus("Invalid text"))
    }

    @Test
    fun `parseBonus extracts bonus with comma in value`() {
        assertEquals(1234.56, RideParser.parseBonus("Incl. \$1,234.56 bonus")!!, 0.001)
    }

    // ==================== extractTextFromNode Tests ====================

    @Test
    fun `extractTextFromNode finds text by resource ID`() {
        val targetNode = createMockNode(
            viewId = "com.lyft.android.driver:id/price_text",
            text = "$25.50"
        )
        val root = createMockNode(children = listOf(targetNode))

        val result = RideParser.extractTextFromNode(root, "price_text")

        assertEquals("$25.50", result)
    }

    @Test
    fun `extractTextFromNode finds nested node`() {
        val targetNode = createMockNode(
            viewId = "com.lyft.android.driver:id/price_text",
            text = "$25.50"
        )
        val wrapper = createMockNode(children = listOf(targetNode))
        val root = createMockNode(children = listOf(wrapper))

        val result = RideParser.extractTextFromNode(root, "price_text")

        assertEquals("$25.50", result)
    }

    @Test
    fun `extractTextFromNode returns null when not found`() {
        val otherNode = createMockNode(
            viewId = "com.lyft.android.driver:id/other_text",
            text = "other"
        )
        val root = createMockNode(children = listOf(otherNode))

        val result = RideParser.extractTextFromNode(root, "price_text")

        assertNull(result)
    }

    // ==================== findChildByResourceId Tests ====================

    @Test
    fun `findChildByResourceId finds matching node`() {
        val targetNode = createMockNode(viewId = "com.lyft.android.driver:id/lyft_text")
        val root = createMockNode(children = listOf(targetNode))

        val result = RideParser.findChildByResourceId(root, "lyft_text")

        assertNotNull(result)
        assertEquals("com.lyft.android.driver:id/lyft_text", result?.viewIdResourceName)
    }

    @Test
    fun `findChildByResourceId is case insensitive`() {
        val targetNode = createMockNode(viewId = "com.lyft.android.driver:id/LYFT_TEXT")
        val root = createMockNode(children = listOf(targetNode))

        val result = RideParser.findChildByResourceId(root, "lyft_text")

        assertNotNull(result)
    }

    @Test
    fun `findChildByResourceId returns null when not found`() {
        val otherNode = createMockNode(viewId = "com.lyft.android.driver:id/other")
        val root = createMockNode(children = listOf(otherNode))

        val result = RideParser.findChildByResourceId(root, "lyft_text")

        assertNull(result)
    }

    // ==================== parseRideCard Tests ====================

    /**
     * Creates a complete mock ride card with all required fields.
     */
    private fun createCompleteRideCard(
        price: String = "$25.50",
        bonus: String? = "Incl. \$3.60 bonus",
        pickupTime: String = "Today · 6:05AM",
        pickupLocation: String = "Maida Ter & Maida Way",
        dropoffLocation: String = "East Rd & Leonardville Rd",
        durationDistance: String = "9 min • 3.6 mi",
        riderName: String = "Kathleen",
        isVerified: Boolean = true,
        riderRating: String = "5.0"
    ): AccessibilityNodeInfo {
        val children = mutableListOf<AccessibilityNodeInfo>()

        // Price node
        children.add(createMockNode(text = price))

        // Bonus node (if present)
        if (bonus != null) {
            children.add(createMockNode(text = bonus))
        }

        // Time node
        children.add(createMockNode(text = pickupTime))

        // Locations
        children.add(createMockNode(text = pickupLocation))
        children.add(createMockNode(text = durationDistance))
        children.add(createMockNode(text = dropoffLocation))

        // Rider info
        children.add(createMockNode(text = riderName))
        if (isVerified) {
            children.add(createMockNode(text = "Verified"))
        }
        children.add(createMockNode(text = riderRating))

        return createMockNode(
            isClickable = true,
            boundsWidth = 1038,
            children = children
        )
    }

    @Test
    fun `parseRideCard parses complete ride card successfully`() {
        val rideCard = createCompleteRideCard()

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertEquals(25.50, result!!.price, 0.001)
        assertEquals(3.60, result.bonus!!, 0.001)
        assertEquals("Today · 6:05AM", result.pickupTime)
        assertEquals("Maida Ter & Maida Way", result.pickupLocation)
        assertEquals("East Rd & Leonardville Rd", result.dropoffLocation)
        assertEquals("9 min", result.duration)
        assertEquals("3.6 mi", result.distance)
        assertEquals("Kathleen", result.riderName)
        assertEquals(5.0, result.riderRating, 0.001)
        assertTrue(result.isVerified)
    }

    @Test
    fun `parseRideCard handles card without bonus`() {
        val rideCard = createCompleteRideCard(bonus = null)

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertNull(result!!.bonus)
        assertEquals(25.50, result.price, 0.001)
    }

    @Test
    fun `parseRideCard handles unverified rider`() {
        val rideCard = createCompleteRideCard(isVerified = false)

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertFalse(result!!.isVerified)
    }

    @Test
    fun `parseRideCard returns null when price missing`() {
        val children = listOf(
            createMockNode(text = "Today · 6:05AM"),
            createMockNode(text = "Maida Ter & Maida Way"),
            createMockNode(text = "East Rd & Leonardville Rd")
        )
        val rideCard = createMockNode(isClickable = true, children = children)

        val result = RideParser.parseRideCard(rideCard)

        assertNull(result)
    }

    @Test
    fun `parseRideCard returns null when pickup time missing`() {
        val children = listOf(
            createMockNode(text = "$25.50"),
            createMockNode(text = "Maida Ter & Maida Way"),
            createMockNode(text = "East Rd & Leonardville Rd")
        )
        val rideCard = createMockNode(isClickable = true, children = children)

        val result = RideParser.parseRideCard(rideCard)

        assertNull(result)
    }

    @Test
    fun `parseRideCard returns null for empty card`() {
        val rideCard = createMockNode(isClickable = true)

        val result = RideParser.parseRideCard(rideCard)

        assertNull(result)
    }

    @Test
    fun `parseRideCard handles Tomorrow time format`() {
        val rideCard = createCompleteRideCard(pickupTime = "Tomorrow · 8:30AM")

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertEquals("Tomorrow · 8:30AM", result!!.pickupTime)
    }

    @Test
    fun `parseRideCard handles PM time format`() {
        val rideCard = createCompleteRideCard(pickupTime = "Today · 6:05PM")

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertEquals("Today · 6:05PM", result!!.pickupTime)
    }

    @Test
    fun `parseRideCard generates consistent ID for same ride`() {
        val rideCard1 = createCompleteRideCard()
        val rideCard2 = createCompleteRideCard()

        val result1 = RideParser.parseRideCard(rideCard1)
        val result2 = RideParser.parseRideCard(rideCard2)

        assertEquals(result1!!.id, result2!!.id)
    }

    @Test
    fun `parseRideCard generates different ID for different rides`() {
        val rideCard1 = createCompleteRideCard(pickupTime = "Today · 6:05AM")
        val rideCard2 = createCompleteRideCard(pickupTime = "Today · 7:00AM")

        val result1 = RideParser.parseRideCard(rideCard1)
        val result2 = RideParser.parseRideCard(rideCard2)

        assertNotEquals(result1!!.id, result2!!.id)
    }

    @Test
    fun `parseRideCard handles high value ride`() {
        val rideCard = createCompleteRideCard(price = "$100.00", bonus = "Incl. \$15.00 bonus")

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertEquals(100.00, result!!.price, 0.001)
        assertEquals(15.00, result.bonus!!, 0.001)
    }

    @Test
    fun `parseRideCard handles different rider ratings`() {
        val rideCard = createCompleteRideCard(riderRating = "4.8")

        val result = RideParser.parseRideCard(rideCard)

        assertNotNull(result)
        assertEquals(4.8, result!!.riderRating, 0.001)
    }

    // ==================== Integration Tests with UI Dump Fixture ====================

    /**
     * Helper class to parse JSON fixture and build mock node tree.
     */
    private data class UINodeFixture(
        val className: String? = null,
        val resourceId: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val isClickable: Boolean = false,
        val bounds: BoundsFixture? = null,
        val children: List<UINodeFixture> = emptyList()
    )

    private data class BoundsFixture(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class ExpectedRide(
        val price: Double,
        val bonus: Double?,
        val pickupTime: String,
        val pickupLocation: String,
        val dropoffLocation: String,
        val duration: String,
        val distance: String,
        val riderName: String,
        val riderRating: Double,
        val isVerified: Boolean
    )

    /**
     * Builds a mock AccessibilityNodeInfo tree from fixture data.
     */
    private fun buildMockNodeFromFixture(fixture: UINodeFixture): AccessibilityNodeInfo {
        val childNodes = fixture.children.map { buildMockNodeFromFixture(it) }
        return createMockNode(
            viewId = fixture.resourceId,
            isClickable = fixture.isClickable,
            boundsWidth = fixture.bounds?.width ?: 0,
            boundsHeight = fixture.bounds?.height ?: 100,
            text = fixture.text,
            contentDescription = fixture.contentDescription,
            children = childNodes
        )
    }

    /**
     * Creates a realistic Lyft Scheduled Rides UI structure based on fixture.
     * This simulates a RecyclerView with 3 ride cards.
     */
    private fun createScheduledRidesFixture(): Pair<AccessibilityNodeInfo, List<ExpectedRide>> {
        // Ride Card 1 - Standard ride with bonus
        val rideCard1Content = UINodeFixture(
            className = "android.widget.FrameLayout",
            isClickable = true,
            bounds = BoundsFixture(21, 200, 1059, 450), // width = 1038
            children = listOf(
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/lyft_text",
                    children = listOf(
                        UINodeFixture(text = "$25.50"),
                        UINodeFixture(text = "Incl. \$3.60 bonus")
                    )
                ),
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/ride_info",
                    children = listOf(
                        UINodeFixture(text = "Today · 6:05AM"),
                        UINodeFixture(text = "Maida Ter & Maida Way"),
                        UINodeFixture(text = "9 min • 3.6 mi"),
                        UINodeFixture(text = "East Rd & Leonardville Rd")
                    )
                ),
                UINodeFixture(
                    children = listOf(
                        UINodeFixture(text = "Kathleen"),
                        UINodeFixture(text = "Verified"),
                        UINodeFixture(text = "5.0")
                    )
                )
            )
        )

        // Ride Card 2 - High value ride without bonus
        val rideCard2Content = UINodeFixture(
            className = "android.widget.FrameLayout",
            isClickable = true,
            bounds = BoundsFixture(21, 460, 1059, 710), // width = 1038
            children = listOf(
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/lyft_text",
                    children = listOf(
                        UINodeFixture(text = "$85.00")
                    )
                ),
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/ride_info",
                    children = listOf(
                        UINodeFixture(text = "Tomorrow · 8:30AM"),
                        UINodeFixture(text = "Newark Airport Terminal B"),
                        UINodeFixture(text = "45 min • 28.5 mi"),
                        UINodeFixture(text = "Manhattan, Park Ave & 42nd St")
                    )
                ),
                UINodeFixture(
                    children = listOf(
                        UINodeFixture(text = "Michael"),
                        UINodeFixture(text = "4.9")
                    )
                )
            )
        )

        // Ride Card 3 - Budget ride with large bonus
        val rideCard3Content = UINodeFixture(
            className = "android.widget.FrameLayout",
            isClickable = true,
            bounds = BoundsFixture(21, 720, 1059, 970), // width = 1038
            children = listOf(
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/lyft_text",
                    children = listOf(
                        UINodeFixture(text = "$12.75"),
                        UINodeFixture(text = "Incl. \$5.00 bonus")
                    )
                ),
                UINodeFixture(
                    resourceId = "com.lyft.android.driver:id/ride_info",
                    children = listOf(
                        UINodeFixture(text = "Today · 2:15PM"),
                        UINodeFixture(text = "Main St & Oak Ave"),
                        UINodeFixture(text = "5 min • 1.2 mi"),
                        UINodeFixture(text = "Downtown Mall, Entrance C")
                    )
                ),
                UINodeFixture(
                    children = listOf(
                        UINodeFixture(text = "Sarah"),
                        UINodeFixture(text = "Verified"),
                        UINodeFixture(text = "4.7")
                    )
                )
            )
        )

        // Build the complete tree
        val recyclerView = UINodeFixture(
            resourceId = "com.lyft.android.driver:id/scheduled_rides_list",
            children = listOf(rideCard1Content, rideCard2Content, rideCard3Content)
        )

        val rootFixture = UINodeFixture(
            resourceId = "android:id/content",
            children = listOf(recyclerView)
        )

        val expectedRides = listOf(
            ExpectedRide(
                price = 25.50,
                bonus = 3.60,
                pickupTime = "Today · 6:05AM",
                pickupLocation = "Maida Ter & Maida Way",
                dropoffLocation = "East Rd & Leonardville Rd",
                duration = "9 min",
                distance = "3.6 mi",
                riderName = "Kathleen",
                riderRating = 5.0,
                isVerified = true
            ),
            ExpectedRide(
                price = 85.00,
                bonus = null,
                pickupTime = "Tomorrow · 8:30AM",
                pickupLocation = "Newark Airport Terminal B",
                dropoffLocation = "Manhattan, Park Ave & 42nd St",
                duration = "45 min",
                distance = "28.5 mi",
                riderName = "Michael",
                riderRating = 4.9,
                isVerified = false
            ),
            ExpectedRide(
                price = 12.75,
                bonus = 5.00,
                pickupTime = "Today · 2:15PM",
                pickupLocation = "Main St & Oak Ave",
                dropoffLocation = "Downtown Mall, Entrance C",
                duration = "5 min",
                distance = "1.2 mi",
                riderName = "Sarah",
                riderRating = 4.7,
                isVerified = true
            )
        )

        return Pair(buildMockNodeFromFixture(rootFixture), expectedRides)
    }

    @Test
    fun `integration - findRideCards discovers all cards in realistic UI structure`() {
        val (root, expectedRides) = createScheduledRidesFixture()

        val rideCards = RideParser.findRideCards(root)

        assertEquals(
            "Should find all ${expectedRides.size} ride cards",
            expectedRides.size,
            rideCards.size
        )
    }

    @Test
    fun `integration - parseRideCard extracts correct data from first ride card`() {
        val (root, expectedRides) = createScheduledRidesFixture()
        val rideCards = RideParser.findRideCards(root)
        val expected = expectedRides[0]

        val parsedRide = RideParser.parseRideCard(rideCards[0])

        assertNotNull("Should successfully parse first ride card", parsedRide)
        assertEquals(expected.price, parsedRide!!.price, 0.001)
        assertEquals(expected.bonus!!, parsedRide.bonus!!, 0.001)
        assertEquals(expected.pickupTime, parsedRide.pickupTime)
        assertEquals(expected.pickupLocation, parsedRide.pickupLocation)
        assertEquals(expected.dropoffLocation, parsedRide.dropoffLocation)
        assertEquals(expected.duration, parsedRide.duration)
        assertEquals(expected.distance, parsedRide.distance)
        assertEquals(expected.riderName, parsedRide.riderName)
        assertEquals(expected.riderRating, parsedRide.riderRating, 0.001)
        assertEquals(expected.isVerified, parsedRide.isVerified)
    }

    @Test
    fun `integration - parseRideCard handles high value ride without bonus`() {
        val (root, expectedRides) = createScheduledRidesFixture()
        val rideCards = RideParser.findRideCards(root)
        val expected = expectedRides[1] // Second ride - no bonus

        val parsedRide = RideParser.parseRideCard(rideCards[1])

        assertNotNull("Should successfully parse second ride card", parsedRide)
        assertEquals(expected.price, parsedRide!!.price, 0.001)
        assertNull("Second ride should have no bonus", parsedRide.bonus)
        assertEquals(expected.pickupTime, parsedRide.pickupTime)
        assertEquals(expected.riderName, parsedRide.riderName)
        assertFalse("Second rider should not be verified", parsedRide.isVerified)
    }

    @Test
    fun `integration - parseRideCard handles budget ride with large bonus`() {
        val (root, expectedRides) = createScheduledRidesFixture()
        val rideCards = RideParser.findRideCards(root)
        val expected = expectedRides[2] // Third ride

        val parsedRide = RideParser.parseRideCard(rideCards[2])

        assertNotNull("Should successfully parse third ride card", parsedRide)
        assertEquals(expected.price, parsedRide!!.price, 0.001)
        assertEquals(expected.bonus!!, parsedRide.bonus!!, 0.001)
        assertEquals(expected.pickupTime, parsedRide.pickupTime)
        assertTrue("Third rider should be verified", parsedRide.isVerified)
    }

    @Test
    fun `integration - full parsing pipeline processes all rides correctly`() {
        val (root, expectedRides) = createScheduledRidesFixture()

        val rideCards = RideParser.findRideCards(root)
        val parsedRides = rideCards.mapNotNull { RideParser.parseRideCard(it) }

        assertEquals(
            "All rides should be successfully parsed",
            expectedRides.size,
            parsedRides.size
        )

        // Verify total value
        val expectedTotalPrice = expectedRides.sumOf { it.price }
        val actualTotalPrice = parsedRides.sumOf { it.price }
        assertEquals(expectedTotalPrice, actualTotalPrice, 0.001)

        // Verify bonus rides count
        val expectedBonusCount = expectedRides.count { it.bonus != null }
        val actualBonusCount = parsedRides.count { it.bonus != null }
        assertEquals(expectedBonusCount, actualBonusCount)

        // Verify verified riders count
        val expectedVerifiedCount = expectedRides.count { it.isVerified }
        val actualVerifiedCount = parsedRides.count { it.isVerified }
        assertEquals(expectedVerifiedCount, actualVerifiedCount)
    }

    @Test
    fun `integration - parsed rides have unique IDs`() {
        val (root, _) = createScheduledRidesFixture()

        val rideCards = RideParser.findRideCards(root)
        val parsedRides = rideCards.mapNotNull { RideParser.parseRideCard(it) }

        val uniqueIds = parsedRides.map { it.id }.toSet()

        assertEquals(
            "All parsed rides should have unique IDs",
            parsedRides.size,
            uniqueIds.size
        )
    }

    @Test
    fun `integration - rides are parseable in any order`() {
        val (root, expectedRides) = createScheduledRidesFixture()

        val rideCards = RideParser.findRideCards(root)

        // Parse in reverse order
        val reverseParsed = rideCards.reversed().mapNotNull { RideParser.parseRideCard(it) }

        assertEquals(expectedRides.size, reverseParsed.size)

        // Last card (first in reversed) should match third expected ride
        assertEquals(expectedRides[2].price, reverseParsed[0].price, 0.001)
    }
}
