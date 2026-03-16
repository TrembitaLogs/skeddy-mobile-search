package com.skeddy.network

/**
 * Sealed class representing all possible outcomes of an API request.
 *
 * Uses covariant type parameter (out T) so that error subtypes with Nothing
 * are subtypes of any ApiResult<T>.
 *
 * LoginError is used ONLY for searchLogin() endpoint.
 * Other endpoints never return LoginError — they fall through to the generic
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

    data class LoginError(val reason: LoginErrorReason) : ApiResult<Nothing>()
}

/**
 * Login-specific error reasons mapped from API error codes.
 *
 * HTTP 401 from the search-login endpoint indicates invalid credentials
 * (wrong email or password).
 */
enum class LoginErrorReason {
    /** HTTP 401 — invalid email or password */
    INVALID_CREDENTIALS
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
    is ApiResult.LoginError -> onFailure(this)
}
