package com.skeddy.network

import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.OkResponse
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.PingResponse
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.RideReportResponse
import com.skeddy.network.models.SearchLoginRequest
import com.skeddy.network.models.SearchLoginResponse
import com.skeddy.network.models.parseErrorBody
import retrofit2.Response
import java.io.IOException

/**
 * Wrapper around [SkeddyApi] that converts raw Retrofit [Response] objects
 * into [ApiResult] sealed class instances with centralized error handling.
 *
 * Generic endpoints (ping, reportRide, deviceOverride) use [handleResponse] which maps
 * HTTP status codes to appropriate error types. The searchLogin endpoint uses
 * [handleLoginResponse] which additionally maps 401 to login-specific errors.
 *
 * On 401/403, the device token is cleared via [DeviceTokenManager.clearDeviceToken]
 * to trigger automatic transition to the login screen.
 */
class SkeddyServerClient(
    private val api: SkeddyApi,
    private val deviceTokenManager: DeviceTokenManager
) {

    suspend fun ping(request: PingRequest): ApiResult<PingResponse> =
        safeApiCall { api.ping(request) }

    suspend fun reportRide(request: RideReportRequest): ApiResult<RideReportResponse> =
        safeApiCall { api.reportRide(request) }

    suspend fun deviceOverride(request: DeviceOverrideRequest): ApiResult<OkResponse> =
        safeApiCall { api.deviceOverride(request) }

    /**
     * Authenticates the search device with email and password.
     * Uses [handleLoginResponse] instead of the generic [handleResponse]
     * to map 401 to [LoginErrorReason.INVALID_CREDENTIALS].
     */
    suspend fun searchLogin(request: SearchLoginRequest): ApiResult<SearchLoginResponse> {
        return try {
            val response = api.searchLogin(request)
            handleLoginResponse(response)
        } catch (e: IOException) {
            ApiResult.NetworkError
        }
    }

    /**
     * Wraps a Retrofit API call with IOException handling.
     * Catches network errors (timeout, no connection) and returns [ApiResult.NetworkError].
     */
    private suspend fun <T> safeApiCall(
        call: suspend () -> Response<T>
    ): ApiResult<T> {
        return try {
            val response = call()
            handleResponse(response)
        } catch (e: IOException) {
            ApiResult.NetworkError
        }
    }

    /**
     * Maps HTTP response codes to [ApiResult] types.
     *
     * - 2xx: [ApiResult.Success]
     * - 401/403: clears device token, returns [ApiResult.Unauthorized]
     * - 422: [ApiResult.ValidationError] with parsed message
     * - 429: [ApiResult.RateLimited] with Retry-After header (fallback 60s)
     * - 503: [ApiResult.ServiceUnavailable]
     * - Other 5xx: [ApiResult.ServerError]
     */
    private fun <T> handleResponse(response: Response<T>): ApiResult<T> {
        if (response.isSuccessful) {
            return ApiResult.Success(response.body()!!)
        }

        return when (response.code()) {
            401, 403 -> {
                deviceTokenManager.clearDeviceToken()
                ApiResult.Unauthorized
            }
            422 -> {
                val errorBody = response.errorBody()?.string()
                val parsed = parseErrorBody(errorBody)
                ApiResult.ValidationError(parsed?.error?.message ?: "Validation error")
            }
            429 -> {
                val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
                    ?: DEFAULT_RETRY_AFTER_SECONDS
                ApiResult.RateLimited(retryAfter)
            }
            503 -> ApiResult.ServiceUnavailable
            in 500..599 -> ApiResult.ServerError
            else -> ApiResult.ServerError
        }
    }

    /**
     * Login-specific response handler that maps 401 to [ApiResult.LoginError]
     * before falling back to the generic [handleResponse] for other codes.
     *
     * - 401: [LoginErrorReason.INVALID_CREDENTIALS] (wrong email or password)
     * - 422: [ApiResult.ValidationError] (validation error)
     * - Other codes: delegated to [handleResponse]
     */
    private fun handleLoginResponse(
        response: Response<SearchLoginResponse>
    ): ApiResult<SearchLoginResponse> {
        if (response.isSuccessful) {
            return ApiResult.Success(response.body()!!)
        }

        return when (response.code()) {
            401 -> ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS)
            422 -> {
                val errorBody = response.errorBody()?.string()
                val parsed = parseErrorBody(errorBody)
                ApiResult.ValidationError(parsed?.error?.message ?: "Validation error")
            }
            else -> handleResponse(response)
        }
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60
    }
}
