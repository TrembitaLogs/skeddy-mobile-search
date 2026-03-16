package com.skeddy.network

import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.OkResponse
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.PingResponse
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.RideReportResponse
import com.skeddy.network.models.SearchLoginRequest
import com.skeddy.network.models.SearchLoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for Skeddy Server API.
 * Handles communication between Search App and the server:
 * ping cycles, ride reporting, login, and device override.
 */
interface SkeddyApi {

    @POST("ping")
    suspend fun ping(@Body request: PingRequest): Response<PingResponse>

    @POST("rides")
    suspend fun reportRide(@Body request: RideReportRequest): Response<RideReportResponse>

    @POST("auth/search-login")
    suspend fun searchLogin(@Body request: SearchLoginRequest): Response<SearchLoginResponse>

    @POST("search/device-override")
    suspend fun deviceOverride(@Body request: DeviceOverrideRequest): Response<OkResponse>
}
