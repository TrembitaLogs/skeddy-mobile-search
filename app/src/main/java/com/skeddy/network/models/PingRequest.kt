package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PingRequest(
    val timezone: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("device_health") val deviceHealth: DeviceHealth,
    val stats: PingStats,
    @SerialName("last_cycle_duration_ms") val lastCycleDurationMs: Int? = null,
    /** Verification results for rides requested by the server in the previous verify_rides. */
    @SerialName("ride_statuses") val rideStatuses: List<RideStatusReport>? = null,
    val location: DeviceLocation? = null
)

@Serializable
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class RideStatusReport(
    @SerialName("ride_hash") val rideHash: String,
    val present: Boolean
)

@Serializable
data class DeviceHealth(
    @SerialName("accessibility_enabled") val accessibilityEnabled: Boolean,
    @SerialName("lyft_running") val lyftRunning: Boolean,
    @SerialName("screen_on") val screenOn: Boolean
)

@Serializable
data class PingStats(
    @SerialName("batch_id") val batchId: String,
    @SerialName("cycles_since_last_ping") val cyclesSinceLastPing: Int,
    @SerialName("rides_found") val ridesFound: Int,
    @SerialName("accept_failures") val acceptFailures: List<AcceptFailure>
)

@Serializable
data class AcceptFailure(
    val reason: String,
    @SerialName("ride_price") val ridePrice: Double,
    @SerialName("pickup_time") val pickupTime: String,
    val timestamp: String
)
