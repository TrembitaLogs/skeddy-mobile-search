package com.skeddy.parser

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.model.ScheduledRide

/**
 * Parser for extracting ride data from Lyft UI.
 *
 * Parses AccessibilityNodeInfo from Lyft app to extract price, time, distance,
 * and other ride information from UI elements.
 */
object RideParser {

    private const val TAG = "RideParser"

    // Expected width of ride cards in Lyft UI (with tolerance)
    private const val RIDE_CARD_WIDTH = 1038
    private const val WIDTH_TOLERANCE = 50

    // Resource IDs that indicate ride card content
    private val RIDE_CARD_RESOURCE_IDS = setOf("lyft_text", "ride_info")

    // Resource ID patterns for finding specific elements
    private const val RES_LYFT_TEXT = "lyft_text"
    private const val RES_RIDE_INFO = "ride_info"

    // Text patterns for parsing
    private const val BONUS_PATTERN = "Incl."
    private const val VERIFIED_TEXT = "Verified"
    private const val DURATION_DISTANCE_SEPARATOR = "•"

    /**
     * Finds all clickable ride cards in the UI hierarchy.
     *
     * @param root Root node of the accessibility tree
     * @return List of AccessibilityNodeInfo representing ride cards
     */
    fun findRideCards(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) {
            Log.w(TAG, "findRideCards: root is null")
            return emptyList()
        }

        Log.d(TAG, "findRideCards: Starting search from root package=${root.packageName}, class=${root.className}")

        val rideCards = mutableListOf<AccessibilityNodeInfo>()
        val visited = mutableSetOf<Int>()
        var totalNodes = 0
        var clickableNodes = 0

        traverseNodes(root) { node ->
            totalNodes++
            val nodeHash = System.identityHashCode(node)
            if (nodeHash in visited) return@traverseNodes

            if (node.isClickable) {
                clickableNodes++
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                Log.v(TAG, "findRideCards: Clickable node width=${bounds.width()}, class=${node.className}")
            }

            if (isRideCard(node)) {
                visited.add(nodeHash)
                rideCards.add(node)
                Log.i(TAG, "findRideCards: Found ride card!")
            }
        }

        Log.d(TAG, "findRideCards: Scanned $totalNodes nodes, $clickableNodes clickable, found ${rideCards.size} ride cards")
        return rideCards
    }

    /**
     * Recursively traverses all nodes in the accessibility tree.
     *
     * @param node Current node to process
     * @param action Action to perform on each node
     */
    private fun traverseNodes(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodes(child, action)
            }
        }
    }

    /**
     * Determines if a node represents a ride card.
     *
     * A node is considered a ride card if it is clickable AND:
     * 1. Has expected width (~1038px ±50px), OR
     * 2. Contains child nodes with known ride-related resource IDs
     */
    private fun isRideCard(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false

        // Scrollable containers are not ride cards
        if (node.isScrollable) return false

        // Check by bounds width
        if (hasExpectedWidth(node)) {
            return true
        }

        // Check by resource ID in children
        if (hasRideCardChildren(node)) {
            return true
        }

        return false
    }

    /**
     * Checks if node has the expected width for a ride card.
     */
    private fun hasExpectedWidth(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val width = bounds.width()

        return width in (RIDE_CARD_WIDTH - WIDTH_TOLERANCE)..(RIDE_CARD_WIDTH + WIDTH_TOLERANCE)
    }

    /**
     * Checks if node has children with ride-related resource IDs.
     * Searches up to maxDepth levels deep.
     */
    private fun hasRideCardChildren(node: AccessibilityNodeInfo, maxDepth: Int = 3): Boolean {
        if (maxDepth <= 0) return false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val resourceId = child.viewIdResourceName

            if (resourceId != null) {
                // Check if resource ID contains any of the known patterns
                for (pattern in RIDE_CARD_RESOURCE_IDS) {
                    if (resourceId.contains(pattern, ignoreCase = true)) {
                        return true
                    }
                }
            }

            // Recursively check deeper levels
            if (hasRideCardChildren(child, maxDepth - 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Parses a ride card AccessibilityNodeInfo into a ScheduledRide object.
     *
     * @param card The root node of a ride card (should be clickable)
     * @return ScheduledRide if all required fields are present, null otherwise
     */
    fun parseRideCard(card: AccessibilityNodeInfo): ScheduledRide? {
        // Collect all text nodes from the card
        val textNodes = mutableListOf<String>()
        collectTextNodes(card, textNodes)

        if (textNodes.isEmpty()) return null

        // Extract price (first text starting with $)
        val priceText = textNodes.find { it.startsWith("$") && !it.contains(BONUS_PATTERN) }
            ?: return null

        // Extract bonus (text containing "Incl.")
        val bonusText = textNodes.find { it.contains(BONUS_PATTERN) }

        // Extract pickup time (typically contains "Today" or time format like "6:05AM")
        val pickupTime = textNodes.find {
            it.contains("Today") || it.contains("AM") || it.contains("PM") ||
            it.contains("Tomorrow") || it.matches(Regex(".*\\d{1,2}:\\d{2}.*"))
        } ?: return null

        // Extract duration/distance (contains bullet separator •)
        val durationDistanceText = textNodes.find { it.contains(DURATION_DISTANCE_SEPARATOR) }
        val (duration, distance) = parseDurationDistance(durationDistanceText)

        // Extract locations - typically after time, look for address-like patterns
        val locations = extractLocations(textNodes, pickupTime, durationDistanceText)
        val pickupLocation = locations.first ?: return null
        val dropoffLocation = locations.second ?: return null

        // Extract rider info
        val isVerified = textNodes.any { it.equals(VERIFIED_TEXT, ignoreCase = true) }
        val riderRating = extractRiderRating(textNodes)
        val riderName = extractRiderName(textNodes, isVerified, riderRating)
            ?: return null

        // Parse numeric values
        val price = parsePrice(priceText)
        if (price <= 0) return null

        val bonus = bonusText?.let { parseBonus(it) }

        // Generate unique ID
        val id = ScheduledRide.generateId(
            pickupTime = pickupTime,
            price = price,
            riderName = riderName,
            pickupLocation = pickupLocation,
            dropoffLocation = dropoffLocation,
            duration = duration ?: "",
            distance = distance ?: ""
        )

        return ScheduledRide(
            id = id,
            price = price,
            bonus = bonus,
            pickupTime = pickupTime,
            pickupLocation = pickupLocation,
            dropoffLocation = dropoffLocation,
            duration = duration ?: "",
            distance = distance ?: "",
            riderName = riderName,
            riderRating = riderRating ?: 0.0,
            isVerified = isVerified
        )
    }

    /**
     * Collects all text content from the node tree.
     */
    private fun collectTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            texts.add(text)
        }

        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty() && contentDesc != text) {
            texts.add(contentDesc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectTextNodes(child, texts)
            }
        }
    }

    /**
     * Extracts text content from a node with the specified resource ID.
     *
     * @param root Root node to search from
     * @param resourceIdPattern Pattern to match in resource ID (partial match)
     * @return Text content of the found node, or null if not found
     */
    fun extractTextFromNode(root: AccessibilityNodeInfo, resourceIdPattern: String): String? {
        val node = findChildByResourceId(root, resourceIdPattern)
        return node?.text?.toString()
    }

    /**
     * Finds a child node by resource ID pattern (searches recursively).
     *
     * @param root Root node to search from
     * @param resourceIdPattern Pattern to match in resource ID (partial match, case-insensitive)
     * @return First matching node, or null if not found
     */
    fun findChildByResourceId(root: AccessibilityNodeInfo, resourceIdPattern: String): AccessibilityNodeInfo? {
        val resourceId = root.viewIdResourceName
        if (resourceId != null && resourceId.contains(resourceIdPattern, ignoreCase = true)) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findChildByResourceId(child, resourceIdPattern)
            if (found != null) {
                return found
            }
        }

        return null
    }

    /**
     * Parses price text like "$25.50" to Double.
     */
    fun parsePrice(priceText: String): Double {
        return priceText
            .replace("$", "")
            .replace(",", "")
            .trim()
            .toDoubleOrNull() ?: 0.0
    }

    /**
     * Parses bonus text like "Incl. $3.60 bonus" to Double.
     */
    fun parseBonus(bonusText: String): Double? {
        val regex = Regex("\\$([\\d,.]+)")
        val match = regex.find(bonusText) ?: return null
        return match.groupValues[1]
            .replace(",", "")
            .toDoubleOrNull()
    }

    /**
     * Parses duration/distance text like "9 min • 3.6 mi" into separate values.
     */
    private fun parseDurationDistance(text: String?): Pair<String?, String?> {
        if (text == null) return Pair(null, null)

        val parts = text.split(DURATION_DISTANCE_SEPARATOR).map { it.trim() }
        return when {
            parts.size >= 2 -> Pair(parts[0], parts[1])
            parts.size == 1 -> Pair(parts[0], null)
            else -> Pair(null, null)
        }
    }

    /**
     * Extracts pickup and dropoff locations from text nodes.
     * Locations are typically address-like strings that are not time, rating, or other known patterns.
     */
    private fun extractLocations(
        textNodes: List<String>,
        pickupTime: String?,
        durationDistance: String?
    ): Pair<String?, String?> {
        // Filter out known non-location texts
        val locationCandidates = textNodes.filter { text ->
            text != pickupTime &&
            text != durationDistance &&
            !text.startsWith("$") &&
            !text.contains(BONUS_PATTERN) &&
            !text.equals(VERIFIED_TEXT, ignoreCase = true) &&
            !text.matches(Regex("^\\d+\\.\\d+$")) && // Not a rating like "5.0"
            text.length > 3 && // Locations are typically longer
            (text.contains("&") || text.contains(" ") || text.contains(",")) // Address patterns
        }

        return when {
            locationCandidates.size >= 2 -> Pair(locationCandidates[0], locationCandidates[1])
            locationCandidates.size == 1 -> Pair(locationCandidates[0], null)
            else -> Pair(null, null)
        }
    }

    /**
     * Extracts rider rating from text nodes (looks for pattern like "5.0").
     */
    private fun extractRiderRating(textNodes: List<String>): Double? {
        val ratingText = textNodes.find { text ->
            text.matches(Regex("^\\d+\\.\\d+$")) &&
            text.toDoubleOrNull()?.let { it in 1.0..5.0 } == true
        }
        return ratingText?.toDoubleOrNull()
    }

    /**
     * Extracts rider name from text nodes.
     * Name is typically a short text that is not price, time, location, rating, or verified status.
     */
    private fun extractRiderName(
        textNodes: List<String>,
        isVerified: Boolean,
        rating: Double?
    ): String? {
        val nameCandidates = textNodes.filter { text ->
            !text.startsWith("$") &&
            !text.contains(BONUS_PATTERN) &&
            !text.contains("Today") &&
            !text.contains("Tomorrow") &&
            !text.contains("AM") &&
            !text.contains("PM") &&
            !text.contains(DURATION_DISTANCE_SEPARATOR) &&
            !text.equals(VERIFIED_TEXT, ignoreCase = true) &&
            text != rating?.toString() &&
            !text.matches(Regex("^\\d+\\.\\d+$")) &&
            !text.contains("&") && // Not a location
            !text.contains(",") && // Not a location
            text.length in 2..20 && // Reasonable name length
            text.first().isUpperCase() // Names typically start with uppercase
        }

        return nameCandidates.firstOrNull()
    }

    fun parseRideFromNode(node: AccessibilityNodeInfo?): RideInfo? {
        // TODO: Parse ride info from accessibility node
        return null
    }

    fun parseTime(timeText: String): Int {
        // TODO: Parse time string like "5 min" to Int
        return 0
    }

    fun parseDistance(distanceText: String): Double {
        // TODO: Parse distance string like "2.5 mi" to Double
        return 0.0
    }
}
