package com.skeddy.network.models

import com.skeddy.model.ScheduledRide

/**
 * Converts a [ScheduledRide] into [RideData] for server reporting via POST /rides.
 */
fun ScheduledRide.toRideData(): RideData = RideData(
    price = price,
    pickupTime = pickupTime,
    pickupLocation = pickupLocation,
    dropoffLocation = dropoffLocation,
    duration = duration,
    distance = distance,
    riderName = riderName
)
