package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RideReportRequest(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("ride_hash") val rideHash: String,
    val timezone: String,
    @SerialName("ride_data") val rideData: RideData
)

@Serializable
data class RideData(
    val price: Double,
    @SerialName("pickup_time") val pickupTime: String,
    @SerialName("pickup_location") val pickupLocation: String,
    @SerialName("dropoff_location") val dropoffLocation: String,
    val duration: String,
    val distance: String,
    @SerialName("rider_name") val riderName: String
)
