package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingResponse(
    @SerialName("device_token") val deviceToken: String,
    @SerialName("user_id") val userId: String
)
