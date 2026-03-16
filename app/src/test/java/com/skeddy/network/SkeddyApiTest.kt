package com.skeddy.network

import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.DeviceHealth
import com.skeddy.network.models.SearchLoginRequest
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.PingStats
import com.skeddy.network.models.RideData
import com.skeddy.network.models.RideReportRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.TimeUnit

/**
 * Unit tests for SkeddyApi Retrofit interface.
 * Verifies correct request formation, response parsing, HTTP methods, and URL paths
 * for all 4 endpoints using MockWebServer.
 */
class SkeddyApiTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var api: SkeddyApi
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/v1/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()

        api = retrofit.create(SkeddyApi::class.java)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // region ping()

    @Test
    fun `ping sends POST to api v1 ping`() = runBlocking {
        enqueuePingResponse()

        api.ping(createPingRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/ping", recorded.path)
    }

    @Test
    fun `ping request body contains correct JSON with snake_case fields`() = runBlocking {
        enqueuePingResponse()

        api.ping(createPingRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("America/New_York", body["timezone"]!!.jsonPrimitive.content)
        assertEquals("1.2.0", body["app_version"]!!.jsonPrimitive.content)

        val deviceHealth = body["device_health"]!!.jsonObject
        assertTrue(deviceHealth["accessibility_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(deviceHealth["lyft_running"]!!.jsonPrimitive.boolean)
        assertTrue(deviceHealth["screen_on"]!!.jsonPrimitive.boolean)

        val stats = body["stats"]!!.jsonObject
        assertEquals("test-batch-id", stats["batch_id"]!!.jsonPrimitive.content)
        assertEquals(1, stats["cycles_since_last_ping"]!!.jsonPrimitive.int)
        assertEquals(0, stats["rides_found"]!!.jsonPrimitive.int)
        assertTrue(stats["accept_failures"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `ping response parses correctly`() = runBlocking {
        enqueuePingResponse()

        val response = api.ping(createPingRequest())

        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertTrue(body.search)
        assertEquals(30, body.intervalSeconds)
        assertEquals(20.0, body.filters.minPrice, 0.001)
        assertFalse(body.forceUpdate)
        assertNull(body.updateUrl)
    }

    // endregion

    // region reportRide()

    @Test
    fun `reportRide sends POST to api v1 rides`() = runBlocking {
        enqueueRideReportResponse()

        api.reportRide(createRideReportRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/rides", recorded.path)
    }

    @Test
    fun `reportRide request body contains correct JSON with snake_case fields`() = runBlocking {
        enqueueRideReportResponse()

        api.reportRide(createRideReportRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("test-idempotency-key", body["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals("ACCEPTED", body["event_type"]!!.jsonPrimitive.content)

        val rideData = body["ride_data"]!!.jsonObject
        assertEquals(25.5, rideData["price"]!!.jsonPrimitive.double, 0.001)
        assertEquals("Tomorrow \u00b7 6:05AM", rideData["pickup_time"]!!.jsonPrimitive.content)
        assertEquals("Maida Ter & Maida Way", rideData["pickup_location"]!!.jsonPrimitive.content)
        assertEquals("East Rd & Leonardville Rd", rideData["dropoff_location"]!!.jsonPrimitive.content)
        assertEquals("9 min", rideData["duration"]!!.jsonPrimitive.content)
        assertEquals("3.6 mi", rideData["distance"]!!.jsonPrimitive.content)
        assertEquals("Kathleen", rideData["rider_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reportRide response parses correctly`() = runBlocking {
        enqueueRideReportResponse()

        val response = api.reportRide(createRideReportRequest())

        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertTrue(body.ok)
        assertEquals("ride-123", body.rideId)
    }

    // endregion

    // region searchLogin()

    @Test
    fun `searchLogin sends POST to api v1 auth search-login`() = runBlocking {
        enqueueLoginResponse()

        api.searchLogin(createSearchLoginRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/search-login", recorded.path)
    }

    @Test
    fun `searchLogin request body contains correct JSON with snake_case fields`() = runBlocking {
        enqueueLoginResponse()

        api.searchLogin(createSearchLoginRequest())

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("user@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("password123", body["password"]!!.jsonPrimitive.content)
        assertEquals("test-device-id", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("America/New_York", body["timezone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `searchLogin response parses correctly`() = runBlocking {
        enqueueLoginResponse()

        val response = api.searchLogin(createSearchLoginRequest())

        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals("token-abc", body.deviceToken)
        assertEquals("user-456", body.userId)
    }

    // endregion

    // region deviceOverride()

    @Test
    fun `deviceOverride sends POST to api v1 search device-override`() = runBlocking {
        enqueueOkResponse()

        api.deviceOverride(DeviceOverrideRequest(active = true))

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/search/device-override", recorded.path)
    }

    @Test
    fun `deviceOverride request body contains correct JSON`() = runBlocking {
        enqueueOkResponse()

        api.deviceOverride(DeviceOverrideRequest(active = true))

        val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertTrue(body["active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `deviceOverride response parses correctly`() = runBlocking {
        enqueueOkResponse()

        val response = api.deviceOverride(DeviceOverrideRequest(active = true))

        assertTrue(response.isSuccessful)
        assertTrue(response.body()!!.ok)
    }

    // endregion

    // region Error handling — HTTP status codes

    @Test
    fun `ping returns 401 Unauthorized response`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid device token"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(401, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `ping returns 403 Forbidden response`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"DEVICE_NOT_PAIRED","message":"Device is not paired"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(403, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `ping returns 422 with error details in response body`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"VALIDATION_ERROR","message":"Invalid timezone format"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(422, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())

        val errorBody = response.errorBody()!!.string()
        val errorJson = json.parseToJsonElement(errorBody).jsonObject
        val error = errorJson["error"]!!.jsonObject
        assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
        assertEquals("Invalid timezone format", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ping returns 429 with Retry-After header`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "60")
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(429, response.code())
        assertFalse(response.isSuccessful)
        assertEquals("60", response.headers()["Retry-After"])
    }

    @Test
    fun `ping returns 503 Service Unavailable`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Service temporarily unavailable"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(503, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `ping returns 500 Internal Server Error`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Internal server error"}}""")
        )

        val response = api.ping(createPingRequest())

        assertEquals(500, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())
    }

    // endregion

    // region Helper functions

    private fun enqueuePingResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":20.0},"force_update":false}""")
        )
    }

    private fun enqueueRideReportResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"ok":true,"ride_id":"ride-123"}""")
        )
    }

    private fun enqueueLoginResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token-abc","user_id":"user-456"}""")
        )
    }

    private fun enqueueOkResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )
    }

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

    private fun createSearchLoginRequest() = SearchLoginRequest(
        email = "user@example.com",
        password = "password123",
        deviceId = "test-device-id",
        timezone = "America/New_York"
    )

    // endregion
}
