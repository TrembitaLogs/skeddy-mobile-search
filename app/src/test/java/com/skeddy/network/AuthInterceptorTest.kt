package com.skeddy.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        deviceTokenManager = mockk()
        every { deviceTokenManager.getDeviceId() } returns "test-device-id"
        every { deviceTokenManager.getDeviceToken() } returns "test-token-abc"

        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(deviceTokenManager))
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // ==================== Headers Added to Regular Requests ====================

    @Test
    fun `adds X-Device-Token header to POST ping`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/ping"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-token-abc", recorded.getHeader("X-Device-Token"))
    }

    @Test
    fun `adds X-Device-ID header to POST ping`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/ping"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `adds auth headers to POST rides`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/rides"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-token-abc", recorded.getHeader("X-Device-Token"))
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `adds auth headers to POST device-override`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/search/device-override"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-token-abc", recorded.getHeader("X-Device-Token"))
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `adds auth headers to GET requests`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/some-endpoint"))
            .get()
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-token-abc", recorded.getHeader("X-Device-Token"))
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    // ==================== Search Login Exclusion ====================

    @Test
    fun `does not add auth headers to POST search login`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/auth/search-login"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(recorded.getHeader("X-Device-Token"))
        assertNull(recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `exclusion requires POST method — GET to search login gets headers`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/auth/search-login"))
            .get()
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-token-abc", recorded.getHeader("X-Device-Token"))
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `exclusion matches path suffix — works with any base path`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/auth/search-login"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(recorded.getHeader("X-Device-Token"))
        assertNull(recorded.getHeader("X-Device-ID"))
    }

    // ==================== Null Token Behavior ====================

    @Test
    fun `sends empty X-Device-Token when token is null`() {
        every { deviceTokenManager.getDeviceToken() } returns null
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/ping"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("", recorded.getHeader("X-Device-Token"))
    }

    @Test
    fun `sends X-Device-ID even when token is null`() {
        every { deviceTokenManager.getDeviceToken() } returns null
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/ping"))
            .post("{}".toRequestBody(null))
            .build()
        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-device-id", recorded.getHeader("X-Device-ID"))
    }

    @Test
    fun `request proceeds normally when token is null`() {
        every { deviceTokenManager.getDeviceToken() } returns null
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/ping"))
            .post("{}".toRequestBody(null))
            .build()
        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
    }
}
