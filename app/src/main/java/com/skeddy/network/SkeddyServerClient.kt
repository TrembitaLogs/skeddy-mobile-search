package com.skeddy.network

import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.OkResponse
import com.skeddy.network.models.PairingRequest
import com.skeddy.network.models.PairingResponse
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.PingResponse
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.RideReportResponse
import com.skeddy.network.models.parseErrorBody
import retrofit2.Response
import java.io.IOException

/**
 * Wrapper around [SkeddyApi] that converts raw Retrofit [Response] objects
 * into [ApiResult] sealed class instances with centralized error handling.
 *
 * Generic endpoints (ping, reportRide, deviceOverride) use [handleResponse] which maps
 * HTTP status codes to appropriate error types. The confirmPairing endpoint uses
 * [handlePairingResponse] which additionally maps 404/409 to pairing-specific errors.
 *
 * On 401/403, the device token is cleared via [DeviceTokenManager.clearDeviceToken]
 * to trigger automatic transition to the pairing screen.
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
     * Confirms pairing with a 6-digit code.
     * Uses [handlePairingResponse] instead of the generic [handleResponse]
     * to map 404 to [PairingErrorReason.INVALID_OR_EXPIRED] and
     * 409 to [PairingErrorReason.ALREADY_USED].
     */
    suspend fun confirmPairing(request: PairingRequest): ApiResult<PairingResponse> {
        return try {
            val response = api.confirmPairing(request)
            handlePairingResponse(response)
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
     * Pairing-specific response handler that maps 404/409 to [ApiResult.PairingError]
     * before falling back to the generic [handleResponse] for other codes.
     *
     * - 404: [PairingErrorReason.INVALID_OR_EXPIRED] (code not found or expired)
     * - 409: [PairingErrorReason.ALREADY_USED] (code already consumed)
     * - Other codes: delegated to [handleResponse]
     */
    private fun handlePairingResponse(
        response: Response<PairingResponse>
    ): ApiResult<PairingResponse> {
        if (response.isSuccessful) {
            return ApiResult.Success(response.body()!!)
        }

        return when (response.code()) {
            404 -> ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)
            409 -> ApiResult.PairingError(PairingErrorReason.ALREADY_USED)
            else -> handleResponse(response)
        }
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60
    }
}
