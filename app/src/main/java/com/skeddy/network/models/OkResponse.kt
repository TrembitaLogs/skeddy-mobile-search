package com.skeddy.network.models

import kotlinx.serialization.Serializable

@Serializable
data class OkResponse(
    val ok: Boolean
)
