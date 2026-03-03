package com.skeddy.service.search

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.logging.SkeddyLogger
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftScreen
import com.skeddy.network.models.AcceptFailure
import com.skeddy.parser.RideParser
import com.skeddy.recovery.AutoRecoveryManager
import com.skeddy.service.AutoAcceptManager
import com.skeddy.service.AutoAcceptResult
import com.skeddy.util.RetryResult
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Classic search flow strategy.
 *
 * Navigation: Main Screen -> Side Menu (hamburger) -> Scheduled Rides ->
 *             Parse -> Filter -> Auto-accept -> Navigate Back
 *
 * This is the original flow extracted from MonitoringForegroundService.executeSearchCycle(),
 * preserved as-is for fallback reliability.
 */
class ClassicSearchFlow : SearchFlowStrategy {

    override val type = SearchFlowType.CLASSIC
    override val name = "ClassicSearchFlow"

    companion object {
        private const val TAG = "ClassicSearchFlow"
    }

    override suspend fun execute(context: SearchFlowContext): SearchFlowResult {
        val cycleStartTime = System.currentTimeMillis()
        Log.i(TAG, "========== STARTING CLASSIC CYCLE ==========")
        SkeddyLogger.i(TAG, "========== STARTING CLASSIC SEARCH CYCLE ==========")

        val accessibilityService = context.accessibilityService
        val navigator = context.navigator

        // Step 1: Auto-recovery - ensure Lyft is active and on main screen
        val recoveryManager = AutoRecoveryManager.create(context.appContext)
        if (recoveryManager == null) {
            Log.e(TAG, "AutoRecoveryManager not available (AccessibilityService inactive)")
            SkeddyLogger.e(TAG, "AutoRecoveryManager not available - AccessibilityService inactive")
            return SearchFlowResult.Failure("AutoRecoveryManager not available")
        }

        Log.d(TAG, "Step 1 - Running auto-recovery...")
        SkeddyLogger.d(TAG, "Step 1 - Running auto-recovery (prepareForMonitoring)")

        val prepareResult = recoveryManager.prepareForMonitoring()
        if (prepareResult.isFailure) {
            val failure = prepareResult as RetryResult.Failure
            Log.e(TAG, "Auto-recovery failed: ${failure.error.message}")
            SkeddyLogger.e(TAG, "Auto-recovery failed: ${failure.error.code} - ${failure.error.message}")
            return SearchFlowResult.Failure("Auto-recovery failed: ${failure.error.message}")
        }

        Log.d(TAG, "Step 1 completed - Ready for monitoring")
        SkeddyLogger.i(TAG, "Step 1 completed - Lyft active and on main screen")

        // Step 2: Navigate to side menu
        Log.d(TAG, "Step 2 - Opening side menu...")
        SkeddyLogger.d(TAG, "Step 2 - Opening side menu")
        if (!navigator.safeNavigateToMenu()) {
            Log.e(TAG, "Failed to open menu, aborting cycle")
            SkeddyLogger.e(TAG, "Failed to open menu, aborting cycle")
            return SearchFlowResult.Failure("Failed to open menu")
        }

        // Step 3: Wait for side menu and navigate to Scheduled Rides
        if (!navigator.waitForScreen(LyftScreen.SIDE_MENU, timeout = 3000)) {
            Log.e(TAG, "Side menu didn't appear, aborting cycle")
            SkeddyLogger.e(TAG, "Side menu didn't appear, aborting cycle")
            navigator.safeNavigateBack()
            return SearchFlowResult.Failure("Side menu didn't appear")
        }
        Log.d(TAG, "Step 3 - Side menu opened")
        SkeddyLogger.d(TAG, "Step 3 - Side menu opened")

        Log.d(TAG, "Step 4 - Navigating to Scheduled Rides...")
        SkeddyLogger.d(TAG, "Step 4 - Navigating to Scheduled Rides")
        if (!navigator.safeNavigateToScheduledRides()) {
            Log.e(TAG, "Failed to navigate to Scheduled Rides, aborting cycle")
            SkeddyLogger.e(TAG, "Failed to navigate to Scheduled Rides, aborting cycle")
            navigator.safeNavigateBack()
            return SearchFlowResult.Failure("Failed to navigate to Scheduled Rides")
        }

        // Step 5: Wait for Scheduled Rides screen
        if (!navigator.waitForScreen(LyftScreen.SCHEDULED_RIDES, timeout = 5000)) {
            Log.e(TAG, "Scheduled Rides screen didn't load, aborting cycle")
            SkeddyLogger.e(TAG, "Scheduled Rides screen didn't load, aborting cycle")
            navigator.safeNavigateBack()
            return SearchFlowResult.Failure("Scheduled Rides screen didn't load")
        }
        Log.d(TAG, "Step 5 - On Scheduled Rides screen")
        SkeddyLogger.d(TAG, "Step 5 - On Scheduled Rides screen")

        // Wait for UI to fully load before parsing
        delay(5000)
        Log.d(TAG, "Waited 5s for UI to stabilize")

        // Step 6: Parse rides with scrolling
        Log.d(TAG, "Step 6 - Parsing rides with scrolling...")
        SkeddyLogger.d(TAG, "Step 6 - Parsing rides with scrolling")
        val parsedRides = parseRidesWithScrolling(accessibilityService)
        Log.d(TAG, "Successfully parsed ${parsedRides.size} rides total")
        SkeddyLogger.i(TAG, "Parsed ${parsedRides.size} rides total")

        // Ride verification: check parsed rides against server-requested hashes
        val hashesToVerify = context.pendingVerification.getPendingHashes()
        if (hashesToVerify.isNotEmpty()) {
            val parsedHashes = parsedRides.map { it.id }.toSet()
            context.pendingVerification.reportVerificationResults(parsedHashes)
            Log.d(TAG, "Verification: ${hashesToVerify.size} requested, checked against ${parsedRides.size} parsed rides")
        }

        // Step 7: Cascading filter ($10 hardcoded -> server min_price -> blacklist)
        Log.d(TAG, "Step 7 - Filtering rides (cascade)...")
        val filteredRides = context.rideFilter.filterRides(parsedRides, context.currentMinPrice)
        Log.d(TAG, "${filteredRides.size} rides passed filter (serverMinPrice=${context.currentMinPrice})")
        SkeddyLogger.i(TAG, "${filteredRides.size} rides passed filter (serverMinPrice=${context.currentMinPrice})")

        // Track rides that passed all filters for ping stats
        if (filteredRides.isNotEmpty()) {
            context.pendingStats.addRidesFound(filteredRides.size)
        }

        // Show temporary debug toast with ride stats
        context.handler.post {
            android.widget.Toast.makeText(
                accessibilityService,
                "Rides: ${parsedRides.size} total, ${filteredRides.size} passed filter (>=${"$"}${"%.0f".format(context.currentMinPrice)})",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Step 7.5: Auto-accept high-value rides
        var acceptedRidesCount = 0

        if (filteredRides.isNotEmpty()) {
            val ridesToAccept = filteredRides

            if (ridesToAccept.isEmpty()) {
                Log.i(TAG, "Step 7.5 - No rides to auto-accept")
                SkeddyLogger.i(TAG, "Auto-accept: No rides found among ${filteredRides.size} filtered rides")
            } else {
                Log.i(TAG, "Step 7.5 - Auto-accepting ${ridesToAccept.size} rides...")
                SkeddyLogger.i(TAG, "Auto-accept: attempting ${ridesToAccept.size} rides")
            }

            val autoAcceptManager = AutoAcceptManager.create(
                context.serverClient, context.blacklistRepository, context.pendingRideQueue
            )
            if (autoAcceptManager != null && ridesToAccept.isNotEmpty()) {
                for (ride in ridesToAccept) {
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
                                acceptedRidesCount++
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

                        // Record accept failure stats for the next ping
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

                        // Wait for return to SCHEDULED_RIDES before processing next ride
                        navigator.waitForScreen(LyftScreen.SCHEDULED_RIDES, 3000)
                        delay(500)
                    } else {
                        Log.w(TAG, "Could not find card for ride ${ride.id}")
                        SkeddyLogger.w(TAG, "Auto-accept: Card not found for ride ${ride.id}")
                    }
                }
            } else if (autoAcceptManager == null) {
                Log.w(TAG, "AutoAcceptManager not available")
                SkeddyLogger.w(TAG, "Auto-accept: AutoAcceptManager not available")
            }

            Log.i(TAG, "Auto-accepted $acceptedRidesCount rides")
            SkeddyLogger.i(TAG, "Auto-accepted $acceptedRidesCount rides")
        }

        // Step 9: Navigate back to main screen
        Log.d(TAG, "Step 9 - Navigating back...")
        SkeddyLogger.d(TAG, "Step 9 - Navigating back to main screen")
        navigator.safeNavigateBack()

        val cycleDuration = System.currentTimeMillis() - cycleStartTime
        Log.i(TAG, "========== CLASSIC CYCLE COMPLETED in ${cycleDuration}ms ==========")
        SkeddyLogger.i(TAG, "========== CYCLE COMPLETED in ${cycleDuration}ms (parsed: ${parsedRides.size}, filtered: ${filteredRides.size}, accepted: $acceptedRidesCount) ==========")

        return SearchFlowResult.Success(
            parsedRidesCount = parsedRides.size,
            filteredRidesCount = filteredRides.size,
            acceptedRidesCount = acceptedRidesCount
        )
    }

    // ==================== Helper Methods ====================

    /**
     * Parses rides from UI with scrolling to capture all visible rides.
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
