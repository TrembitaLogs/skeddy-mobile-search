package com.skeddy.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor that adds X-Device-Token and X-Device-ID headers
 * to every request, except POST /auth/search-login which is a public endpoint
 * (device token does not exist at login time).
 *
 * If the device token is null (not logged in), the request proceeds with an
 * empty X-Device-Token header — the server will respond with 401.
 */
class AuthInterceptor(
    private val deviceTokenManager: DeviceTokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip auth headers for POST /auth/search-login — token doesn't exist yet
        val isSearchLogin = request.url.encodedPath.endsWith("/auth/search-login")
                && request.method == "POST"

        if (isSearchLogin) {
            return chain.proceed(request)
        }

        val newRequest = request.newBuilder()
            .addHeader(HEADER_DEVICE_TOKEN, deviceTokenManager.getDeviceToken() ?: "")
            .addHeader(HEADER_DEVICE_ID, deviceTokenManager.getDeviceId())
            .build()

        return chain.proceed(newRequest)
    }

    companion object {
        const val HEADER_DEVICE_TOKEN = "X-Device-Token"
        const val HEADER_DEVICE_ID = "X-Device-ID"
    }
}
