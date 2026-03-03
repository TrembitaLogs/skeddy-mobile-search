package com.skeddy.data

import com.skeddy.model.ScheduledRide
import com.skeddy.util.PickupTimeParser

/**
 * Generates a unique key for a ride using [ScheduledRide.generateId].
 *
 * Delegates to the single source of truth for ride identity hashing.
 *
 * @return 64-character lowercase hex string (SHA-256 hash)
 */
fun generateRideKey(ride: ScheduledRide): String {
    return ScheduledRide.generateId(
        pickupTime = ride.pickupTime,
        price = ride.price,
        riderName = ride.riderName,
        pickupLocation = ride.pickupLocation,
        dropoffLocation = ride.dropoffLocation,
        duration = ride.duration,
        distance = ride.distance
    )
}

/**
 * Converts a [ScheduledRide] into a [BlacklistedRide] for local deduplication.
 *
 * Uses [ScheduledRide.generateId] for the composite SHA-256 key and [PickupTimeParser]
 * for the TTL-related pickup timestamp. When parsing fails the timestamp is null
 * and the 48-hour fallback TTL applies during cleanup.
 */
fun ScheduledRide.toBlacklistedRide(): BlacklistedRide {
    val rideKey = generateRideKey(this)
    val parsedTimestamp = PickupTimeParser.parsePickupTimestamp(pickupTime)
    return BlacklistedRide(
        rideKey = rideKey,
        price = price,
        pickupTime = pickupTime,
        pickupLocation = pickupLocation,
        riderName = riderName,
        dropoffLocation = dropoffLocation,
        addedAt = System.currentTimeMillis(),
        parsedPickupTimestamp = parsedTimestamp
    )
}
