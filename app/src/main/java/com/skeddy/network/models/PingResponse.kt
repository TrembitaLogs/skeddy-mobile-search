package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PingResponse(
    val search: Boolean,
    @SerialName("interval_seconds") val intervalSeconds: Int,
    val filters: Filters,
    @SerialName("force_update") val forceUpdate: Boolean = false,
    @SerialName("update_url") val updateUrl: String? = null,
    /** Server override for search flow type: "CLASSIC" or "OPTIMIZED". Null = use local preference. */
    @SerialName("search_flow") val searchFlow: String? = null,
    /** Reason for stopping search (only when search=false). E.g. "NO_CREDITS". */
    val reason: String? = null,
    /** Ride hashes that the Search App should verify presence for in the Lyft Driver UI. */
    @SerialName("verify_rides") val verifyRides: List<VerifyRide> = emptyList()
)

@Serializable
data class VerifyRide(
    @SerialName("ride_hash") val rideHash: String
)

@Serializable
data class Filters(
    @SerialName("min_price") val minPrice: Double
)
