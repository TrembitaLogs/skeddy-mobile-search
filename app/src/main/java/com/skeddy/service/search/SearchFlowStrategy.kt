package com.skeddy.service.search

import android.content.Context
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import com.skeddy.data.SkeddyPreferences
import com.skeddy.filter.RideFilter
import com.skeddy.navigation.LyftNavigator
import com.skeddy.network.SkeddyServerClient
import com.skeddy.service.PendingRideQueue
import com.skeddy.service.PendingStatsAccumulator
import com.skeddy.service.PendingVerificationAccumulator

/**
 * Available search flow strategy types.
 */
enum class SearchFlowType {
    /** Original flow: Main Screen -> Side Menu -> Scheduled Rides -> Parse -> Back */
    CLASSIC,

    /** Optimized flow: Stay on Scheduled Rides, use Your Rides toggle to refresh */
    OPTIMIZED
}

/**
 * Result of a single search cycle execution.
 */
sealed class SearchFlowResult {
    /**
     * Cycle completed successfully.
     *
     * @param parsedRidesCount total rides found in the UI
     * @param filteredRidesCount rides that passed all filters
     * @param acceptedRidesCount rides successfully auto-accepted
     */
    data class Success(
        val parsedRidesCount: Int,
        val filteredRidesCount: Int,
        val acceptedRidesCount: Int
    ) : SearchFlowResult()

    /**
     * Cycle failed.
     *
     * @param reason human-readable description of the failure
     * @param shouldFallback true if the service should try a different strategy
     */
    data class Failure(
        val reason: String,
        val shouldFallback: Boolean = false
    ) : SearchFlowResult()
}

/**
 * Bundles all dependencies needed by a search flow strategy.
 *
 * Passed to [SearchFlowStrategy.execute] on each cycle. Avoids coupling
 * strategy implementations to [MonitoringForegroundService] internals.
 */
data class SearchFlowContext(
    /** Application context for creating managers that need it (e.g. AutoRecoveryManager) */
    val appContext: Context,
    val accessibilityService: SkeddyAccessibilityService,
    val navigator: LyftNavigator,
    val rideFilter: RideFilter,
    val serverClient: SkeddyServerClient,
    val blacklistRepository: BlacklistRepository,
    val pendingRideQueue: PendingRideQueue,
    val pendingStats: PendingStatsAccumulator,
    val pendingVerification: PendingVerificationAccumulator,
    val preferences: SkeddyPreferences,
    val currentMinPrice: Double,
    /** Main-thread handler for posting Toast messages */
    val handler: android.os.Handler
)

/**
 * Contract for search flow strategies.
 *
 * A strategy encapsulates the full navigation + parse + filter + accept cycle.
 * Strategies are interchangeable at runtime — the service picks one based on
 * user preference and server override, and falls back to [SearchFlowType.CLASSIC]
 * when an optimized strategy signals [SearchFlowResult.Failure.shouldFallback].
 */
interface SearchFlowStrategy {
    /** Identifies this strategy. */
    val type: SearchFlowType

    /** Human-readable name for logging. */
    val name: String

    /**
     * Executes a single search cycle.
     *
     * Implementations own the full lifecycle: navigation, ride parsing,
     * filtering, auto-accept, and stats tracking. The caller is responsible
     * only for scheduling and error handling at the service level.
     *
     * @param context all dependencies needed for the cycle
     * @return [SearchFlowResult.Success] or [SearchFlowResult.Failure]
     */
    suspend fun execute(context: SearchFlowContext): SearchFlowResult

    /**
     * One-time initialization when this strategy is first activated.
     * Called before the first [execute] after a strategy switch.
     * Default: no-op.
     */
    suspend fun onActivated(context: SearchFlowContext) {}

    /**
     * Cleanup when switching away from this strategy.
     * Default: no-op.
     */
    suspend fun onDeactivated(context: SearchFlowContext) {}
}
