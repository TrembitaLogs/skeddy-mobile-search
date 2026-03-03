package com.skeddy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for blacklisted rides used for local deduplication.
 * Maps to the "blacklisted_rides" table.
 *
 * Rides are added to blacklist after being found/accepted to prevent
 * duplicate processing in subsequent search cycles.
 */
@Entity(tableName = "blacklisted_rides")
data class BlacklistedRide(
    @PrimaryKey val rideKey: String,
    val price: Double,
    val pickupTime: String,
    val pickupLocation: String,
    val riderName: String,
    val dropoffLocation: String,
    val addedAt: Long,
    val parsedPickupTimestamp: Long?
)
