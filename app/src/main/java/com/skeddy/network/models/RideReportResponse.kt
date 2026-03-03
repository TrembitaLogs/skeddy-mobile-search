package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RideReportResponse(
    val ok: Boolean,
    @SerialName("ride_id") val rideId: String? = null
)
