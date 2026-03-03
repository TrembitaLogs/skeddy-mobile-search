package com.skeddy.filter

import com.skeddy.data.BlacklistRepository
import com.skeddy.data.generateRideKey
import com.skeddy.model.ScheduledRide

/**
 * Filters scheduled rides using a three-level cascading filter:
 * 1. Hardcoded minimum price ($10) — rejects obviously low-value rides
 * 2. Server-configured minimum price — applies driver's dynamic threshold
 * 3. Blacklist deduplication — skips rides already seen/accepted
 *
 * Each level narrows the list starting from the cheapest check (in-memory price
 * comparison) to the most expensive (database lookup for blacklist).
 *
 * @param blacklistRepository Repository for blacklist ride lookups
 */
class RideFilter(private val blacklistRepository: BlacklistRepository) {

    companion object {
        const val HARDCODED_MIN_PRICE = 10.0
    }

    /**
     * Filters rides through a three-level cascade.
     *
     * @param rides List of scheduled rides to filter
     * @param serverMinPrice Server-configured minimum price threshold
     * @return Filtered list of rides that passed all three filter levels, preserving original order
     */
    suspend fun filterRides(
        rides: List<ScheduledRide>,
        serverMinPrice: Double
    ): List<ScheduledRide> {
        // Step 1: Hardcoded minimum price filter (in-memory)
        // Step 2: Server-configured minimum price filter (in-memory)
        val priceFiltered = rides
            .filter { it.price >= HARDCODED_MIN_PRICE }
            .filter { it.price >= serverMinPrice }

        // Step 3: Blacklist deduplication filter (DB lookup per ride)
        val result = mutableListOf<ScheduledRide>()
        for (ride in priceFiltered) {
            if (!blacklistRepository.exists(generateRideKey(ride))) {
                result.add(ride)
            }
        }
        return result
    }
}
