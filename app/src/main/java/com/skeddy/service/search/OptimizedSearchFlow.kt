package com.skeddy.service.search

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.logging.SkeddyLogger
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftScreen
import com.skeddy.navigation.LyftUIElements
import com.skeddy.network.models.AcceptFailure
import com.skeddy.parser.RideParser
import com.skeddy.recovery.AutoRecoveryManager
import com.skeddy.service.AutoAcceptManager
import com.skeddy.service.AutoAcceptResult
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Optimized search flow strategy.
 *
 * Key differences from Classic:
 * - Navigates via Layers icon instead of side menu
 * - Stays on Scheduled Rides screen across cycles (no navigate back)
 * - Uses "Your Rides" tab toggle to force Lyft to refresh available rides
 * - Performs pinch-to-zoom out after each navigation to Scheduled Rides
 *
 * Cycle:
 *   Step 0: Guard — ensure on SCHEDULED_RIDES screen
 *   Step 1: Pinch-to-zoom out (only after navigation, not every cycle)
 *   Step 2: Tap "Your Rides" (toggle to show caught rides)
 *   Step 3: Tap "Your Rides" again (forces Lyft to refresh available rides)
 *   Step 4: Parse rides from list
 *   Step 5: Filter + Auto-accept
 */
class OptimizedSearchFlow : SearchFlowStrategy {

    override val type = SearchFlowType.OPTIMIZED
    override val name = "OptimizedSearchFlow"

    /** Flag: zoom out is needed after navigating to Scheduled Rides screen (currently disabled) */
    private var needsZoomOut = false

    companion object {
        private const val TAG = "OptimizedSearchFlow"

        /** Delay after tapping "Your Rides" to wait for view switch */
        private const val YOUR_RIDES_TAP_DELAY = 1500L

        /** Delay after second tap to wait for ride list refresh */
        private const val REFRESH_WAIT_DELAY = 3000L
    }

    override suspend fun onActivated(context: SearchFlowContext) {
        needsZoomOut = false
        Log.i(TAG, "Strategy activated — zoom-out disabled")
    }

    override suspend fun execute(context: SearchFlowContext): SearchFlowResult {
        val cycleStartTime = System.currentTimeMillis()
        Log.i(TAG, "========== STARTING OPTIMIZED CYCLE ==========")
        SkeddyLogger.i(TAG, "========== STARTING OPTIMIZED SEARCH CYCLE ==========")

        val accessibilityService = context.accessibilityService
        val navigator = context.navigator

        // Step 0: Guard — ensure we're on SCHEDULED_RIDES screen
        val onScheduledRides = ensureOnScheduledRidesScreen(context)
        if (!onScheduledRides) {
            Log.e(TAG, "Step 0 FAILED — cannot reach Scheduled Rides screen")
            SkeddyLogger.e(TAG, "Step 0 FAILED — cannot reach Scheduled Rides screen, suggesting fallback")
            return SearchFlowResult.Failure(
                reason = "Failed to navigate to Scheduled Rides via Layers",
                shouldFallback = true
            )
        }

        // Step 1: Pinch-to-zoom out (only after navigation)
        if (needsZoomOut) {
            Log.d(TAG, "Step 1 — Performing pinch-to-zoom out...")
            SkeddyLogger.d(TAG, "Step 1 — Pinch-to-zoom out after navigation")
            val zoomResult = performZoomOut(accessibilityService)
            if (zoomResult) {
                needsZoomOut = false
                Log.i(TAG, "Step 1 — Zoom out completed")
            } else {
                // Non-fatal — continue without zoom
                Log.w(TAG, "Step 1 — Zoom out failed (non-fatal, continuing)")
                SkeddyLogger.w(TAG, "Pinch-to-zoom failed (non-fatal)")
                needsZoomOut = false
            }
        } else {
            Log.d(TAG, "Step 1 — Skipping zoom (already on screen, map preserved)")
        }

        // Step 2–3: Toggle "Your Rides" to force Lyft to refresh available rides.
        // The goal: tap Your Rides ON → wait → tap Your Rides OFF → Lyft refreshes.
        // But if Your Rides is already active (e.g. left over from previous cycle),
        // we need to deselect first, then do the full toggle.
        val yourRidesAlreadyActive = navigator.isYourRidesTabActive()
        Log.d(TAG, "Step 2 — Your Rides tab already active: $yourRidesAlreadyActive")
        SkeddyLogger.d(TAG, "Step 2 — Your Rides already active: $yourRidesAlreadyActive")

        if (yourRidesAlreadyActive) {
            // Deselect first to get back to available rides
            Log.d(TAG, "Step 2 — Deselecting Your Rides first (reset state)...")
            if (!navigator.navigateToYourRidesTab()) {
                val navigatedBack = navigator.navigateToAvailableRidesTab()
                if (!navigatedBack) {
                    Log.e(TAG, "Step 2 FAILED — Could not deselect Your Rides")
                    SkeddyLogger.e(TAG, "Could not deselect Your Rides tab")
                    return SearchFlowResult.Failure(
                        reason = "Could not deselect Your Rides tab",
                        shouldFallback = true
                    )
                }
            }
            delay(YOUR_RIDES_TAP_DELAY)
        }

        // Now Your Rides should be inactive — tap to activate
        Log.d(TAG, "Step 2 — Tapping 'Your Rides' to toggle ON...")
        SkeddyLogger.d(TAG, "Step 2 — Tapping Your Rides ON")
        if (!navigator.navigateToYourRidesTab()) {
            Log.e(TAG, "Step 2 FAILED — Your Rides tab not found")
            SkeddyLogger.e(TAG, "Your Rides tab not found, suggesting fallback")
            return SearchFlowResult.Failure(
                reason = "Your Rides tab not found",
                shouldFallback = true
            )
        }
        delay(YOUR_RIDES_TAP_DELAY)

        // Step 3: Tap "Your Rides" again to deselect — forces Lyft to refresh
        Log.d(TAG, "Step 3 — Tapping 'Your Rides' to toggle OFF (trigger refresh)...")
        SkeddyLogger.d(TAG, "Step 3 — Tapping Your Rides OFF (refresh trigger)")
        if (!navigator.navigateToYourRidesTab()) {
            Log.w(TAG, "Step 3 — Second Your Rides tap failed, trying alternatives...")
            val navigatedBack = navigator.navigateToAvailableRidesTab()
            if (!navigatedBack) {
                Log.e(TAG, "Step 3 FAILED — Could not toggle back to available rides")
                SkeddyLogger.e(TAG, "Could not toggle back to available rides")
                return SearchFlowResult.Failure(
                    reason = "Could not toggle back to available rides",
                    shouldFallback = true
                )
            }
        }

        // Wait for Lyft to refresh the ride list
        delay(REFRESH_WAIT_DELAY)
        Log.d(TAG, "Step 3 — Waited ${REFRESH_WAIT_DELAY}ms for refresh")

        // Step 4: Parse rides with scrolling
        Log.d(TAG, "Step 4 — Parsing rides with scrolling...")
        SkeddyLogger.d(TAG, "Step 4 — Parsing rides with scrolling")
        val parsedRides = parseRidesWithScrolling(accessibilityService)
        Log.d(TAG, "Parsed ${parsedRides.size} rides total")
        SkeddyLogger.i(TAG, "Parsed ${parsedRides.size} rides total")

        // Ride verification: check parsed rides against server-requested hashes
        val hashesToVerify = context.pendingVerification.getPendingHashes()
        if (hashesToVerify.isNotEmpty()) {
            val parsedHashes = parsedRides.map { it.id }.toSet()
            context.pendingVerification.reportVerificationResults(parsedHashes)
            Log.d(TAG, "Verification: ${hashesToVerify.size} requested, checked against ${parsedRides.size} parsed rides")
        }

        // Step 5: Filter + Auto-accept
        Log.d(TAG, "Step 5 — Filtering rides (cascade)...")
        val filteredRides = context.rideFilter.filterRides(parsedRides, context.currentMinPrice)
        Log.d(TAG, "${filteredRides.size} rides passed filter (serverMinPrice=${context.currentMinPrice})")
        SkeddyLogger.i(TAG, "${filteredRides.size} rides passed filter (serverMinPrice=${context.currentMinPrice})")

        if (filteredRides.isNotEmpty()) {
            context.pendingStats.addRidesFound(filteredRides.size)
        }

        // Debug toast
        context.handler.post {
            android.widget.Toast.makeText(
                accessibilityService,
                "[OPT] Rides: ${parsedRides.size} total, ${filteredRides.size} passed filter (>=${"$"}${"%.0f".format(context.currentMinPrice)})",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Auto-accept
        var acceptedRidesCount = 0
        if (filteredRides.isNotEmpty()) {
            acceptedRidesCount = autoAcceptRides(context, filteredRides, accessibilityService, navigator)
        }

        val cycleDuration = System.currentTimeMillis() - cycleStartTime
        Log.i(TAG, "========== OPTIMIZED CYCLE COMPLETED in ${cycleDuration}ms ==========")
        SkeddyLogger.i(TAG, "========== CYCLE COMPLETED in ${cycleDuration}ms (parsed: ${parsedRides.size}, filtered: ${filteredRides.size}, accepted: $acceptedRidesCount) ==========")

        return SearchFlowResult.Success(
            parsedRidesCount = parsedRides.size,
            filteredRidesCount = filteredRides.size,
            acceptedRidesCount = acceptedRidesCount
        )
    }

    // ==================== Navigation Helpers ====================

    /**
     * Ensures we're on the SCHEDULED_RIDES screen.
     * If already there, returns true immediately.
     * If not, navigates via Layers path and sets needsZoomOut = true.
     */
    private suspend fun ensureOnScheduledRidesScreen(context: SearchFlowContext): Boolean {
        val currentScreen = context.navigator.detectCurrentScreen()
        Log.d(TAG, "ensureOnScheduledRidesScreen: currentScreen=$currentScreen")

        if (currentScreen == LyftScreen.SCHEDULED_RIDES) {
            Log.i(TAG, "ensureOnScheduledRidesScreen: Already on Scheduled Rides")
            return true
        }

        // Need to navigate — ensure Lyft is active first
        val recoveryManager = AutoRecoveryManager.create(context.appContext)
        if (recoveryManager != null) {
            val prepareResult = recoveryManager.prepareForMonitoring()
            if (prepareResult.isFailure) {
                Log.e(TAG, "ensureOnScheduledRidesScreen: Auto-recovery failed")
                SkeddyLogger.e(TAG, "Auto-recovery failed during optimized flow guard")
                return false
            }
        }

        // Navigate via Layers path
        Log.d(TAG, "ensureOnScheduledRidesScreen: Navigating via Layers...")
        val result = context.navigator.navigateToScheduledRidesViaLayers()
        if (result) {
            // Wait for UI to stabilize after navigation — tabs may not be in
            // the accessibility tree immediately after screen transition
            delay(1000)
            Log.i(TAG, "ensureOnScheduledRidesScreen: Navigation succeeded (zoom-out disabled)")
        }
        return result
    }

    // ==================== Zoom ====================

    /**
     * Performs multiple pinch-to-zoom out gestures on the map.
     * Symmetric from center to avoid shifting the map.
     */
    private suspend fun performZoomOut(accessibilityService: SkeddyAccessibilityService): Boolean {
        val screen = accessibilityService.getScreenSize()
        val center = LyftUIElements.toAbsolute(LyftUIElements.PINCH_CENTER, screen.x, screen.y)
        val startDist = (LyftUIElements.PINCH_ZOOM_OUT_START_DISTANCE * screen.y).toInt()
        val endDist = (LyftUIElements.PINCH_ZOOM_OUT_END_DISTANCE * screen.y).toInt()
        val total = LyftUIElements.PINCH_ZOOM_OUT_REPETITIONS
        var anySuccess = false
        Log.i(TAG, "performZoomOut: center=$center, dist=$startDist→$endDist, $total repetitions")
        repeat(total) { i ->
            val attemptNum = i + 1
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    accessibilityService,
                    "Zoom-out $attemptNum/$total",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            val result = accessibilityService.performPinchToZoomAwait(
                centerX = center.x,
                centerY = center.y,
                startDistance = startDist,
                endDistance = endDist,
                durationMs = LyftUIElements.PINCH_DURATION
            )
            if (result) anySuccess = true
            Log.d(TAG, "performZoomOut: repetition $attemptNum/$total completed=$result")
            // Small pause between gestures for the map to settle
            delay(LyftUIElements.PINCH_INTER_GESTURE_DELAY)
        }
        Log.i(TAG, "performZoomOut: finished, anySuccess=$anySuccess")
        return anySuccess
    }

    // ==================== Parsing ====================

    /**
     * Parses rides from UI with scrolling to capture all visible rides.
     * Same logic as ClassicSearchFlow but reused here.
     */
    private suspend fun parseRidesWithScrolling(
        accessibilityService: SkeddyAccessibilityService
    ): List<ScheduledRide> {
        val allRides = mutableMapOf<String, ScheduledRide>()
        val maxScrolls = 5
        var scrollCount = 0
        var previousRideCount = -1

        val screen = accessibilityService.getScreenSize()

        while (scrollCount <= maxScrolls) {
            val rootNode = accessibilityService.captureLyftUIHierarchy()
            if (rootNode == null) {
                Log.w(TAG, "parseRidesWithScrolling: Failed to capture UI, stopping")
                break
            }

            val rideCards = RideParser.findRideCards(rootNode)
            Log.d(TAG, "parseRidesWithScrolling: Scroll $scrollCount - Found ${rideCards.size} ride cards")

            for (card in rideCards) {
                val ride = RideParser.parseRideCard(card)
                if (ride != null && !allRides.containsKey(ride.id)) {
                    allRides[ride.id] = ride
                    Log.d(TAG, "parseRidesWithScrolling: New ride: ${ride.pickupTime} - \$${ride.price}")
                }
            }

            Log.d(TAG, "parseRidesWithScrolling: Total unique rides so far: ${allRides.size}")

            if (allRides.size == previousRideCount) {
                Log.d(TAG, "parseRidesWithScrolling: No new rides found, stopping scroll")
                break
            }
            previousRideCount = allRides.size

            if (scrollCount < maxScrolls && rideCards.isNotEmpty()) {
                Log.d(TAG, "parseRidesWithScrolling: Scrolling down...")
                accessibilityService.performScrollDown(screen.x, screen.y)
                delay(800)
                scrollCount++
            } else {
                break
            }
        }

        Log.i(TAG, "parseRidesWithScrolling: Completed with ${allRides.size} total rides after $scrollCount scrolls")
        return allRides.values.toList()
    }

    // ==================== Auto-Accept ====================

    /**
     * Auto-accepts filtered rides.
     * Returns the count of successfully accepted rides.
     */
    private suspend fun autoAcceptRides(
        context: SearchFlowContext,
        filteredRides: List<ScheduledRide>,
        accessibilityService: SkeddyAccessibilityService,
        navigator: com.skeddy.navigation.LyftNavigator
    ): Int {
        var acceptedCount = 0

        Log.i(TAG, "Auto-accepting ${filteredRides.size} rides...")
        SkeddyLogger.i(TAG, "Auto-accept: attempting ${filteredRides.size} rides")

        val autoAcceptManager = AutoAcceptManager.create(
            context.serverClient, context.blacklistRepository, context.pendingRideQueue
        )
        if (autoAcceptManager == null) {
            Log.w(TAG, "AutoAcceptManager not available")
            SkeddyLogger.w(TAG, "Auto-accept: AutoAcceptManager not available")
            return 0
        }

        for (ride in filteredRides) {
            val rootNode = accessibilityService.captureLyftUIHierarchy()
            if (rootNode == null) {
                Log.w(TAG, "Failed to capture UI for auto-accept")
                break
            }

            val rideCards = RideParser.findRideCards(rootNode)
            val targetCard = findRideCardByRide(rideCards, ride)

            if (targetCard != null) {
                val result = autoAcceptManager.autoAcceptRide(targetCard, ride)
                when (result) {
                    is AutoAcceptResult.Success -> {
                        Log.i(TAG, "Auto-accept SUCCESS: ${ride.pickupTime} \$${ride.price}")
                        SkeddyLogger.i(TAG, "Auto-accept SUCCESS: ${ride.id}")
                        acceptedCount++
                        context.preferences.incrementRidesFoundToday()
                    }
                    is AutoAcceptResult.RideNotFound -> {
                        Log.w(TAG, "Auto-accept: Ride not found - ${result.reason}")
                        SkeddyLogger.w(TAG, "Auto-accept RideNotFound: ${result.reason}")
                    }
                    is AutoAcceptResult.AcceptButtonNotFound -> {
                        Log.w(TAG, "Auto-accept: Accept button not found - ${result.reason}")
                        SkeddyLogger.w(TAG, "Auto-accept AcceptButtonNotFound: ${result.reason}")
                    }
                    is AutoAcceptResult.ClickFailed -> {
                        Log.w(TAG, "Auto-accept: Click failed - ${result.reason}")
                        SkeddyLogger.w(TAG, "Auto-accept ClickFailed: ${result.reason}")
                    }
                    is AutoAcceptResult.ConfirmationTimeout -> {
                        Log.w(TAG, "Auto-accept: Confirmation timeout - ${result.reason}")
                        SkeddyLogger.w(TAG, "Auto-accept ConfirmationTimeout: ${result.reason}")
                    }
                }

                if (result !is AutoAcceptResult.Success) {
                    val reason = result::class.simpleName ?: "Unknown"
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    context.pendingStats.addAcceptFailure(
                        AcceptFailure(
                            reason = reason,
                            ridePrice = ride.price,
                            pickupTime = ride.pickupTime,
                            timestamp = sdf.format(Date())
                        )
                    )
                }

                navigator.waitForScreen(LyftScreen.SCHEDULED_RIDES, 3000)
                delay(500)
            } else {
                Log.w(TAG, "Could not find card for ride ${ride.id}")
                SkeddyLogger.w(TAG, "Auto-accept: Card not found for ride ${ride.id}")
            }
        }

        Log.i(TAG, "Auto-accepted $acceptedCount rides")
        SkeddyLogger.i(TAG, "Auto-accepted $acceptedCount rides")
        return acceptedCount
    }

    /**
     * Finds the ride card matching a target ride by comparing parsed IDs.
     */
    private fun findRideCardByRide(
        rideCards: List<AccessibilityNodeInfo>,
        targetRide: ScheduledRide
    ): AccessibilityNodeInfo? {
        for (card in rideCards) {
            val parsedRide = RideParser.parseRideCard(card)
            if (parsedRide != null && parsedRide.id == targetRide.id) {
                Log.d(TAG, "findRideCardByRide: Found matching card for ride ${targetRide.id}")
                return card
            }
        }
        Log.d(TAG, "findRideCardByRide: No matching card found for ride ${targetRide.id}")
        return null
    }
}
