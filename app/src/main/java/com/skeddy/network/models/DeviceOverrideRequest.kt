package com.skeddy.network.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceOverrideRequest(
    val active: Boolean
)
