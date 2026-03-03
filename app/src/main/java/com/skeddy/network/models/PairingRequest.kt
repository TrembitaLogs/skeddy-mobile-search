package com.skeddy.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingRequest(
    val code: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_model") val deviceModel: String? = null,
    val timezone: String
)
