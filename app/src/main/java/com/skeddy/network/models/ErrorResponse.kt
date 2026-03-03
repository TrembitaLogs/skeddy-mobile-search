package com.skeddy.network.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String
)

private val errorJson = Json { ignoreUnknownKeys = true }

fun parseErrorBody(errorBodyString: String?): ErrorResponse? {
    if (errorBodyString.isNullOrBlank()) return null
    return try {
        errorJson.decodeFromString(errorBodyString)
    } catch (e: Exception) {
        null
    }
}
