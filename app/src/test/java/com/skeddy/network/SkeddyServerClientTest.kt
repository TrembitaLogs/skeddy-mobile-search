package com.skeddy.network

import com.skeddy.network.models.DeviceHealth
import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.PairingRequest
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.PingStats
import com.skeddy.network.models.RideData
import com.skeddy.network.models.RideReportRequest
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Unit tests for [SkeddyServerClient].
 * Covers success paths for all 4 endpoints, generic error handling
 * (401/403/422/429/503/5xx/IOException), and pairing-specific error mapping (404/409).
 */
class SkeddyServerClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var client: SkeddyServerClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        deviceTokenManager = mockk(relaxed = true)

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/v1/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()

        val api = retrofit.create(SkeddyApi::class.java)
        client = SkeddyServerClient(api, deviceTokenManager)
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {
            // Server may already be shut down by network failure tests
        }
    }

    // ==================== ping() — Success ====================

    @Test
    fun `ping returns Success with parsed response on 200`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":45,"filters":{"min_price":25.0},"force_update":false}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertTrue(data.search)
        assertEquals(45, data.intervalSeconds)
        assertEquals(25.0, data.filters.minPrice, 0.001)
        assertFalse(data.forceUpdate)
        assertNull(data.updateUrl)
    }

    // ==================== reportRide() — Success ====================

    @Test
    fun `reportRide returns Success with parsed response on 201`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"ok":true,"ride_id":"ride-abc-123"}""")
        )

        val result = client.reportRide(createRideReportRequest())

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertTrue(data.ok)
        assertEquals("ride-abc-123", data.rideId)
    }

    // ==================== confirmPairing() — Success ====================

    @Test
    fun `confirmPairing returns Success with parsed response on 200`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token-xyz","user_id":"user-789"}""")
        )

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals("token-xyz", data.deviceToken)
        assertEquals("user-789", data.userId)
    }

    // ==================== deviceOverride() — Success ====================

    @Test
    fun `deviceOverride returns Success with parsed response on 200`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        val result = client.deviceOverride(DeviceOverrideRequest(active = true))

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.ok)
    }

    // ==================== Generic Error Handling — 401 Unauthorized ====================

    @Test
    fun `returns Unauthorized and clears token on 401`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid device token"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.Unauthorized)
        verify(exactly = 1) { deviceTokenManager.clearDeviceToken() }
    }

    @Test
    fun `returns Unauthorized and clears token on 403`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"DEVICE_NOT_PAIRED","message":"Device is not paired"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.Unauthorized)
        verify(exactly = 1) { deviceTokenManager.clearDeviceToken() }
    }

    // ==================== Generic Error Handling — 422 Validation ====================

    @Test
    fun `returns ValidationError with parsed message on 422`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"INVALID_TIMEZONE","message":"Invalid timezone format"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.ValidationError)
        assertEquals("Invalid timezone format", (result as ApiResult.ValidationError).message)
    }

    @Test
    fun `returns ValidationError with default message when error body is empty`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.ValidationError)
        assertEquals("Validation error", (result as ApiResult.ValidationError).message)
    }

    // ==================== Generic Error Handling — 429 Rate Limiting ====================

    @Test
    fun `returns RateLimited with parsed Retry-After on 429`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "120")
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(120, (result as ApiResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `returns RateLimited with default 60s when Retry-After header is missing`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(60, (result as ApiResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `returns RateLimited with default 60s when Retry-After header is non-numeric`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "invalid")
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(60, (result as ApiResult.RateLimited).retryAfterSeconds)
    }

    // ==================== Generic Error Handling — 503, 500, Network ====================

    @Test
    fun `returns ServiceUnavailable on 503`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Service temporarily unavailable"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.ServiceUnavailable)
    }

    @Test
    fun `returns ServerError on 500`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Internal server error"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.ServerError)
    }

    @Test
    fun `returns ServerError on 502`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("""{"error":{"code":"BAD_GATEWAY","message":"Bad gateway"}}""")
        )

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.ServerError)
    }

    @Test
    fun `returns NetworkError on network failure`() = runBlocking {
        mockWebServer.shutdown()

        val result = client.ping(createPingRequest())

        assertTrue(result is ApiResult.NetworkError)
    }

    // ==================== Token not cleared for non-auth errors ====================

    @Test
    fun `does not clear token on non-auth errors`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Internal server error"}}""")
        )

        client.ping(createPingRequest())

        verify(exactly = 0) { deviceTokenManager.clearDeviceToken() }
    }

    // ==================== Pairing-Specific Error Handling ====================

    @Test
    fun `confirmPairing returns PairingError INVALID_OR_EXPIRED on 404`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"PAIRING_CODE_EXPIRED","message":"Invalid or expired pairing code"}}""")
        )

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.PairingError)
        assertEquals(
            PairingErrorReason.INVALID_OR_EXPIRED,
            (result as ApiResult.PairingError).reason
        )
    }

    @Test
    fun `confirmPairing returns PairingError ALREADY_USED on 409`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"PAIRING_CODE_USED","message":"Code already used"}}""")
        )

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.PairingError)
        assertEquals(
            PairingErrorReason.ALREADY_USED,
            (result as ApiResult.PairingError).reason
        )
    }

    @Test
    fun `confirmPairing falls through to ValidationError on 422`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"INVALID_TIMEZONE","message":"Invalid timezone identifier"}}""")
        )

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.ValidationError)
        assertEquals("Invalid timezone identifier", (result as ApiResult.ValidationError).message)
    }

    @Test
    fun `confirmPairing falls through to ServiceUnavailable on 503`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Redis unavailable"}}""")
        )

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.ServiceUnavailable)
    }

    @Test
    fun `confirmPairing returns NetworkError on network failure`() = runBlocking {
        mockWebServer.shutdown()

        val result = client.confirmPairing(createPairingRequest())

        assertTrue(result is ApiResult.NetworkError)
    }

    // ==================== Helper functions ====================

    private fun createPingRequest() = PingRequest(
        timezone = "America/New_York",
        appVersion = "1.2.0",
        deviceHealth = DeviceHealth(
            accessibilityEnabled = true,
            lyftRunning = true,
            screenOn = true
        ),
        stats = PingStats(
            batchId = "test-batch-id",
            cyclesSinceLastPing = 1,
            ridesFound = 0,
            acceptFailures = emptyList()
        )
    )

    private fun createRideReportRequest() = RideReportRequest(
        idempotencyKey = "test-idempotency-key",
        eventType = "ACCEPTED",
        rideHash = "abc123def456",
        timezone = "America/New_York",
        rideData = RideData(
            price = 25.5,
            pickupTime = "Tomorrow \u00b7 6:05AM",
            pickupLocation = "Maida Ter & Maida Way",
            dropoffLocation = "East Rd & Leonardville Rd",
            duration = "9 min",
            distance = "3.6 mi",
            riderName = "Kathleen"
        )
    )

    private fun createPairingRequest() = PairingRequest(
        code = "123456",
        deviceId = "test-device-id",
        timezone = "America/New_York"
    )
}
