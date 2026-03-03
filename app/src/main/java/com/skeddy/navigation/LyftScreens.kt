package com.skeddy.navigation

import android.graphics.Point
import android.graphics.PointF

/**
 * Possible Lyft Driver app screens.
 * Used for determining current UI state and navigation.
 */
enum class LyftScreen {
    /** Unknown or undetermined screen state */
    UNKNOWN,

    /** Main screen with map and hamburger menu */
    MAIN_SCREEN,

    /** Side menu after pressing hamburger button */
    SIDE_MENU,

    /** Scheduled rides list screen */
    SCHEDULED_RIDES,

    /** Detailed information about a specific ride */
    RIDE_DETAILS,

    /** Other screens (settings, profile, earnings, etc.) */
    OTHER
}

/**
 * UI element constants for Lyft Driver app.
 *
 * Contains:
 * - Relative coordinates (0.0–1.0) for fallback clicks that auto-scale to any screen size
 * - Resource IDs for element search via AccessibilityService
 *
 * Relative coordinates were calibrated on Samsung SM-A156U (1080x2340)
 * and automatically scale to other screen resolutions.
 */
object LyftUIElements {

    // ==================== Relative coordinates (0.0–1.0) ====================
    // Calibrated on 1080x2340 (Samsung SM-A156U full resolution with nav bar).

    /** Center of hamburger menu button on main screen — bounds [11,103][146,238] */
    val MENU_BUTTON_CENTER = PointF(0.073f, 0.073f)

    /** Center of "Scheduled Rides" item in side menu — parent bounds [0,1042][1080,1222] */
    val SCHEDULED_RIDES_CENTER = PointF(0.5f, 0.484f)

    /** Center of Back button (back arrow) — bounds [12,92][147,227] */
    val BACK_BUTTON_CENTER = PointF(0.074f, 0.068f)

    /** Center of Accept/Claim button on ride details screen — parent bounds [46,1979][1034,2159] */
    val ACCEPT_BUTTON_CENTER = PointF(0.5f, 0.884f)

    /** Center of confirmation Reserve button — parent bounds [135,1867][945,2002] */
    val CONFIRM_BUTTON_CENTER = PointF(0.5f, 0.827f)

    // ==================== Resource IDs ====================

    /** Resource ID for hamburger menu button (legacy, may not work) */
    const val RES_MENU_BUTTON = "side_menu_btn"

    /** Resource ID for Scheduled Rides menu item */
    const val RES_SCHEDULED_RIDES = "schedule"

    /** Resource ID for primary action button (Reserve/Accept on ride details) */
    const val RES_PRIMARY_BUTTON = "primary_button"

    // ==================== Content Descriptions ====================

    /** Content description for menu button (most reliable lookup method) */
    const val CONTENT_DESC_OPEN_MENU = "Open menu"

    /** Content description for destination filter button */
    const val CONTENT_DESC_DESTINATION_FILTER = "Button to launch destination filter"

    /** Resource ID for ride card in list */
    const val RES_RIDE_CARD = "ride_card"

    /** Resource ID for Lyft text element (for main screen detection) */
    const val RES_LYFT_TEXT = "lyft_text"

    // ==================== Timeout Constants ====================

    /** Timeout for menu open/close animation (ms) */
    const val MENU_ANIMATION_TIMEOUT = 2000L

    /** Timeout for screen loading (ms) */
    const val SCREEN_LOAD_TIMEOUT = 5000L

    /** Timeout for accept confirmation (ms) */
    const val ACCEPT_CONFIRMATION_TIMEOUT = 5000L

    /** Timeout for RIDE_DETAILS to appear after clicking a card (ms) */
    const val RIDE_CARD_CLICK_TIMEOUT = 3000L

    /** Interval between polling checks (ms) */
    const val POLLING_INTERVAL = 250L

    // ==================== Retry Constants ====================

    /** Maximum number of retry attempts */
    const val RETRY_MAX_ATTEMPTS = 3

    /** Initial delay between attempts (ms) */
    const val RETRY_INITIAL_DELAY = 500L

    /** Maximum delay between attempts (ms) */
    const val RETRY_MAX_DELAY = 2000L

    /** Multiplier for exponential backoff */
    const val RETRY_BACKOFF_FACTOR = 2.0

    /** Delay before click to stabilize UI (ms) */
    const val PRE_CLICK_DELAY = 2000L

    // ==================== Package Name ====================

    /** Lyft Driver app package name */
    const val LYFT_DRIVER_PACKAGE = "com.lyft.android.driver"

    // ==================== Your Rides Tab Constants ====================

    /** Your Rides tab text (may contain count in parentheses) */
    const val TAB_YOUR_RIDES_TEXT = "Your rides"

    /** Regex for parsing ride count from 'Your rides (N)' tab */
    val YOUR_RIDES_COUNT_REGEX = Regex("""Your rides\s*\((\d+)\)""")

    // ==================== Layers Button Constants (Optimized Flow) ====================

    /** Content description for Layers button (bottom-left of main map screen) */
    const val CONTENT_DESC_LAYERS_BUTTON = "Opens alternative map options."

    /** Relative coordinates for Layers button center — bounds [11,1000][146,1135] */
    val LAYERS_BUTTON_CENTER = PointF(0.073f, 0.456f)

    /** Relative coordinates for "Scheduled rides" in the Maximize Your Earnings sheet — bounds row ~y=1181 */
    val SCHEDULED_RIDES_SHEET_CENTER = PointF(0.5f, 0.505f)

    // ==================== Pinch-to-Zoom Constants ====================

    /** Center point for pinch gesture on the visible map area (Scheduled Rides screen)
     *  Map visible area: Y=373..1050 on 2340h → center ≈ Y=700 → 0.299 */
    val PINCH_CENTER = PointF(0.5f, 0.299f)

    /** Relative initial finger distance from center for zoom-out — start far apart
     *  Must stay within visible map (max ~300px from center on 2340h → 0.128) */
    const val PINCH_ZOOM_OUT_START_DISTANCE = 0.128f

    /** Relative final finger distance from center for zoom-out — end close together */
    const val PINCH_ZOOM_OUT_END_DISTANCE = 0.009f

    /** Duration of a single pinch gesture (ms) */
    const val PINCH_DURATION = 500L

    /** Number of pinch-to-zoom repetitions to reach max zoom-out */
    const val PINCH_ZOOM_OUT_REPETITIONS = 10

    /** Delay between pinch repetitions (ms) */
    const val PINCH_INTER_GESTURE_DELAY = 1000L

    // ==================== Scroll Constants ====================

    /** Maximum number of scroll attempts when searching for a ride */
    const val MAX_SCROLL_ATTEMPTS = 10

    /** Relative coordinates for scroll down in ride list (fallback) */
    val SCROLL_DOWN_START = PointF(0.5f, 0.641f)
    val SCROLL_DOWN_END = PointF(0.5f, 0.342f)

    /** Relative coordinates for scroll down on ride details screen */
    val DETAILS_SCROLL_DOWN_START = PointF(0.5f, 0.769f)
    val DETAILS_SCROLL_DOWN_END = PointF(0.5f, 0.256f)

    /** Swipe gesture duration (ms) */
    const val SWIPE_DURATION = 300L

    // ==================== Coordinate Conversion ====================

    /**
     * Converts relative coordinates (0.0–1.0) to absolute pixel coordinates
     * for the given screen dimensions.
     *
     * @param relative point with x and y in range 0.0–1.0
     * @param screenWidth screen width in pixels
     * @param screenHeight screen height in pixels
     * @return absolute pixel coordinates
     */
    fun toAbsolute(relative: PointF, screenWidth: Int, screenHeight: Int): Point {
        return Point(
            (relative.x * screenWidth).toInt(),
            (relative.y * screenHeight).toInt()
        )
    }

    /**
     * Returns full resource ID with package prefix.
     * @param shortId short ID (e.g. "side_menu_btn")
     * @return full ID (e.g. "com.lyft.android.driver:id/side_menu_btn")
     */
    fun fullResourceId(shortId: String): String {
        return "$LYFT_DRIVER_PACKAGE:id/$shortId"
    }
}
