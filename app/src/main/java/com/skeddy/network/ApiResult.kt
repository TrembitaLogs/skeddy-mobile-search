package com.skeddy.network

/**
 * Sealed class representing all possible outcomes of an API request.
 *
 * Uses covariant type parameter (out T) so that error subtypes with Nothing
 * are subtypes of any ApiResult<T>.
 *
 * PairingError is used ONLY for confirmPairing() endpoint.
 * Other endpoints never return PairingError — they fall through to the generic
 * error types (ValidationError, ServiceUnavailable, etc).
 */
sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data object NetworkError : ApiResult<Nothing>()

    data object Unauthorized : ApiResult<Nothing>()

    data class ValidationError(val message: String) : ApiResult<Nothing>()

    data class RateLimited(val retryAfterSeconds: Int) : ApiResult<Nothing>()

    data object ServiceUnavailable : ApiResult<Nothing>()

    data object ServerError : ApiResult<Nothing>()

    data class PairingError(val reason: PairingErrorReason) : ApiResult<Nothing>()
}

/**
 * Pairing-specific error reasons mapped from API error codes.
 *
 * API contract combines 'invalid code' and 'expired code' under a single
 * HTTP 404 (error code PAIRING_CODE_EXPIRED), so the enum has
 * INVALID_OR_EXPIRED instead of two separate values.
 */
enum class PairingErrorReason {
    /** HTTP 404 — code not found or expired (error code: PAIRING_CODE_EXPIRED) */
    INVALID_OR_EXPIRED,

    /** HTTP 409 — code already used (error code: PAIRING_CODE_USED) */
    ALREADY_USED
}

/** Returns true if this result is [ApiResult.Success]. */
fun <T> ApiResult<T>.isSuccess(): Boolean = this is ApiResult.Success

/** Returns the data if this is [ApiResult.Success], or null otherwise. */
fun <T> ApiResult<T>.getOrNull(): T? = when (this) {
    is ApiResult.Success -> data
    else -> null
}

/**
 * Folds over the result, applying [onSuccess] for [ApiResult.Success]
 * or [onFailure] for any error type.
 */
inline fun <T, R> ApiResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (ApiResult<Nothing>) -> R
): R = when (this) {
    is ApiResult.Success -> onSuccess(data)
    is ApiResult.NetworkError -> onFailure(this)
    is ApiResult.Unauthorized -> onFailure(this)
    is ApiResult.ValidationError -> onFailure(this)
    is ApiResult.RateLimited -> onFailure(this)
    is ApiResult.ServiceUnavailable -> onFailure(this)
    is ApiResult.ServerError -> onFailure(this)
    is ApiResult.PairingError -> onFailure(this)
}
