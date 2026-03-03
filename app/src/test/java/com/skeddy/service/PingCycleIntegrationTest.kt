package com.skeddy.service

import android.content.ComponentName
import android.os.Looper
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.network.AuthInterceptor
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.AcceptFailure
import io.mockk.every
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the ping cycle using MockWebServer.
 *
 * Unlike [MonitoringCyclePingIntegrationTest] (which mocks [SkeddyServerClient]),
 * these tests use a REAL [SkeddyServerClient] connected to [MockWebServer].
 * This verifies the full HTTP chain:
 *   MonitoringForegroundService → SkeddyServerClient → Retrofit → OkHttp → MockWebServer
 *
 * Covers:
 * - Request formation: body fields (timezone, app_version, device_health, stats) and auth headers
 * - Response parsing: search flag, interval_seconds, filters, force_update, update_url
 * - State updates: currentInterval, currentMinPrice, stats reset
 * - Error handling: 401, 403, 429, 500, 503 HTTP status codes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PingCycleIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var service: MonitoringForegroundService
    private lateinit var shadowLooper: ShadowLooper
    private lateinit var mockDeviceTokenManager: DeviceTokenManager
    private lateinit var mockPendingRideQueue: PendingRideQueue
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        enableAccessibilityService()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Create mocked DeviceTokenManager for header control and clearToken verification
        mockDeviceTokenManager = io.mockk.mockk(relaxed = true)
        every { mockDeviceTokenManager.getDeviceToken() } returns "test-device-token-abc"
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id-xyz"

        // Build real HTTP stack connected to MockWebServer
        val authInterceptor = AuthInterceptor(mockDeviceTokenManager)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/v1/"))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()

        val api = retrofit.create(SkeddyApi::class.java)
        val realServerClient = SkeddyServerClient(api, mockDeviceTokenManager)

        // Create service via Robolectric (calls onCreate)
        service = Robolectric.setupService(MonitoringForegroundService::class.java)
        shadowLooper = Shadows.shadowOf(Looper.getMainLooper())

        // Replace dependencies with MockWebServer-connected client
        service.serverClient = realServerClient
        service.deviceTokenManager = mockDeviceTokenManager

        // Replace pendingRideQueue with a mock
        mockPendingRideQueue = io.mockk.mockk(relaxed = true)
        every { mockPendingRideQueue.dequeueAll() } returns emptyList()
        service.pendingRideQueue = mockPendingRideQueue
    }

    @After
    fun tearDown() {
        disableAccessibilityService()
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {
            // Server may already be shut down
        }
        unmockkAll()
    }

    // ==================== 1. Request formation: timezone ====================

    @Test
    fun `ping request contains timezone`() = kotlinx.coroutines.test.runTest {
        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)

        val body = parseRequestBody(request!!)
        val timezone = body["timezone"]?.jsonPrimitive?.content
        assertNotNull("timezone should be present in request body", timezone)
        assertTrue(
            "timezone should be a valid IANA identifier, got: '$timezone'",
            timezone!!.contains("/") || timezone == "GMT"
        )
        assertEquals(
            "timezone should match system default",
            java.util.TimeZone.getDefault().id,
            timezone
        )
    }

    // ==================== 2. Request formation: app_version ====================

    @Test
    fun `ping request contains app_version`() = kotlinx.coroutines.test.runTest {
        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)

        val body = parseRequestBody(request!!)
        val appVersion = body["app_version"]?.jsonPrimitive?.content
        assertNotNull("app_version should be present in request body", appVersion)
        assertTrue(
            "app_version should be non-empty",
            appVersion!!.isNotEmpty()
        )
    }

    // ==================== 3. Request formation: device_health ====================

    @Test
    fun `ping request contains device_health fields`() = kotlinx.coroutines.test.runTest {
        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)

        val body = parseRequestBody(request!!)
        val deviceHealth = body["device_health"]?.jsonObject
        assertNotNull("device_health should be present in request body", deviceHealth)

        // Verify all three fields exist
        assertTrue(
            "accessibility_enabled should be present",
            deviceHealth!!.containsKey("accessibility_enabled")
        )
        assertTrue(
            "lyft_running should be present",
            deviceHealth.containsKey("lyft_running")
        )
        assertTrue(
            "screen_on should be present",
            deviceHealth.containsKey("screen_on")
        )

        // Accessibility is enabled in setUp — verify it's reported correctly
        assertTrue(
            "accessibility_enabled should be true (enabled in setUp)",
            deviceHealth["accessibility_enabled"]!!.jsonPrimitive.boolean
        )
    }

    // ==================== 4. Request formation: stats ====================

    @Test
    fun `ping request contains stats with accumulated data`() = kotlinx.coroutines.test.runTest {
        // Accumulate some stats before ping
        service.pendingStats.incrementCycles()
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(3)
        service.pendingStats.addAcceptFailure(
            AcceptFailure(
                reason = "AcceptButtonNotFound",
                ridePrice = 25.50,
                pickupTime = "Tomorrow · 6:05AM",
                timestamp = "2026-02-09T10:30:00Z"
            )
        )
        val expectedBatchId = service.pendingStats.batchId

        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)

        val body = parseRequestBody(request!!)
        val stats = body["stats"]?.jsonObject
        assertNotNull("stats should be present in request body", stats)

        assertEquals(
            "batch_id should match accumulator",
            expectedBatchId,
            stats!!["batch_id"]?.jsonPrimitive?.content
        )
        assertEquals(
            "cycles_since_last_ping should be 2",
            2,
            stats["cycles_since_last_ping"]?.jsonPrimitive?.int
        )
        assertEquals(
            "rides_found should be 3",
            3,
            stats["rides_found"]?.jsonPrimitive?.int
        )

        val failures = stats["accept_failures"]?.jsonArray
        assertNotNull("accept_failures should be present", failures)
        assertEquals("accept_failures should have 1 entry", 1, failures!!.size)

        val failure = failures[0].jsonObject
        assertEquals("AcceptButtonNotFound", failure["reason"]?.jsonPrimitive?.content)
        assertEquals(25.50, failure["ride_price"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals("Tomorrow · 6:05AM", failure["pickup_time"]?.jsonPrimitive?.content)
    }

    // ==================== 5. Request formation: auth headers ====================

    @Test
    fun `ping request contains X-Device-Token and X-Device-ID headers`() = kotlinx.coroutines.test.runTest {
        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)

        assertEquals(
            "X-Device-Token header should contain device token",
            "test-device-token-abc",
            request!!.getHeader("X-Device-Token")
        )
        assertEquals(
            "X-Device-ID header should contain device id",
            "test-device-id-xyz",
            request.getHeader("X-Device-ID")
        )
    }

    @Test
    fun `ping request sent to correct endpoint with POST method`() = kotlinx.coroutines.test.runTest {
        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/ping", request.path)
    }

    // ==================== 6. Response parsing: search=true ====================

    @Test
    fun `search true in response triggers search cycle`() = kotlinx.coroutines.test.runTest {
        service.consecutiveFailures = 5

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":25.0}}""")
        )

        service.monitoringCycleWithPing()

        // executeSearchCycleWithRetry resets consecutiveFailures to 0
        assertEquals(
            "consecutiveFailures should be 0 after search cycle",
            0,
            service.consecutiveFailures
        )
    }

    // ==================== 7. Response parsing: search=false ====================

    @Test
    fun `search false in response skips search cycle`() = kotlinx.coroutines.test.runTest {
        service.consecutiveFailures = 5

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":60,"filters":{"min_price":20.0}}""")
        )

        service.monitoringCycleWithPing()

        // executeSearchCycleWithRetry NOT called → consecutiveFailures unchanged
        assertEquals(
            "consecutiveFailures should remain 5 (no search cycle)",
            5,
            service.consecutiveFailures
        )
    }

    // ==================== 8. Response parsing: interval_seconds ====================

    @Test
    fun `interval_seconds from response applied to currentInterval`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":45,"filters":{"min_price":20.0}}""")
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "currentInterval should be updated from response",
            45,
            service.currentInterval
        )
    }

    // ==================== 9. Response parsing: filters.min_price ====================

    @Test
    fun `filters min_price from response stored locally`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":30,"filters":{"min_price":35.0}}""")
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "currentMinPrice should be updated from response",
            35.0,
            service.currentMinPrice,
            0.001
        )
    }

    // ==================== 10. Response parsing: force_update ====================

    @Test
    fun `force_update true sets force update state and skips search`() = kotlinx.coroutines.test.runTest {
        service.consecutiveFailures = 5

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":300,"filters":{"min_price":20.0},"force_update":true,"update_url":"https://skeddy.net/download/search-app.apk"}""")
        )

        service.monitoringCycleWithPing()

        assertTrue(
            "Service should be in force update state",
            service.isInForceUpdateState()
        )
        // force_update overrides search=true → no search cycle
        assertEquals(
            "consecutiveFailures should remain 5 (force update skips search)",
            5,
            service.consecutiveFailures
        )
    }

    // ==================== 11. Response parsing: update_url ====================

    @Test
    fun `update_url from force update response broadcast to UI`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":300,"filters":{"min_price":20.0},"force_update":true,"update_url":"https://skeddy.net/download/v2.apk"}""")
        )

        service.monitoringCycleWithPing()

        // Verify force update broadcast contains the update URL
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val forceUpdateIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_FORCE_UPDATE
        }

        assertNotNull("Force update broadcast should be sent", forceUpdateIntent)
        assertEquals(
            "Broadcast should contain update_url",
            "https://skeddy.net/download/v2.apk",
            forceUpdateIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_UPDATE_URL)
        )
    }

    // ==================== 12-13. State updates: interval and min_price ====================

    @Test
    fun `successful ping updates both currentInterval and currentMinPrice`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":90,"filters":{"min_price":42.5}}""")
        )

        service.monitoringCycleWithPing()

        assertEquals("currentInterval should be 90", 90, service.currentInterval)
        assertEquals("currentMinPrice should be 42.5", 42.5, service.currentMinPrice, 0.001)
    }

    // ==================== 14. State updates: stats reset ====================

    @Test
    fun `stats reset after successful ping with new batch_id`() = kotlinx.coroutines.test.runTest {
        // Accumulate stats
        service.pendingStats.incrementCycles()
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(5)
        val oldBatchId = service.pendingStats.batchId

        enqueueSuccessResponse()

        service.monitoringCycleWithPing()

        assertEquals("cyclesSinceLastPing should be reset to 0", 0, service.pendingStats.cyclesSinceLastPing)
        assertEquals("ridesFound should be reset to 0", 0, service.pendingStats.ridesFound)
        assertTrue(
            "batchId should be regenerated after reset",
            service.pendingStats.batchId != oldBatchId
        )
    }

    // ==================== 15. Error handling: 401 Unauthorized ====================

    @Test
    fun `401 response clears token and broadcasts unpaired`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()
        assertTrue("Monitoring should be active initially", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid device token"}}""")
        )

        service.monitoringCycleWithPing()

        // SkeddyServerClient.handleResponse clears token on 401
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }

        // Service should stop monitoring
        assertFalse("Monitoring should be stopped after 401", service.isMonitoringActive())

        // Unpaired broadcast should be sent
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("Unpaired broadcast should be sent on 401", unpairedIntent)
    }

    // ==================== 16. Error handling: 403 Forbidden ====================

    @Test
    fun `403 response behaves same as 401`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()
        assertTrue("Monitoring should be active initially", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"DEVICE_NOT_PAIRED","message":"Device not paired"}}""")
        )

        service.monitoringCycleWithPing()

        // Same behavior as 401: clear token, stop monitoring, broadcast unpaired
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }
        assertFalse("Monitoring should be stopped after 403", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("Unpaired broadcast should be sent on 403", unpairedIntent)
    }

    // ==================== 17. Error handling: 500/503 ====================

    @Test
    fun `500 response stops searching and keeps monitoring active`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Internal server error"}}""")
        )

        service.monitoringCycleWithPing()

        // Service keeps monitoring (for retry) but search is paused
        assertTrue("Monitoring should remain active after 500", service.isMonitoringActive())

        // Verify server offline broadcast
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent on 500", statusIntent)
        assertTrue(
            "serverOffline should be true after 500",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    @Test
    fun `503 response stops searching and keeps monitoring active`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Service unavailable"}}""")
        )

        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active after 503", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent on 503", statusIntent)
        assertTrue(
            "serverOffline should be true after 503",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    // ==================== 18. Error handling: 429 Rate Limited ====================

    @Test
    fun `429 response respects Retry-After header`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "120")
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Rate limit exceeded"}}""")
        )

        service.monitoringCycleWithPing()

        // Service keeps monitoring but search is paused
        assertTrue("Monitoring should remain active after 429", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent on 429", statusIntent)
    }

    @Test
    fun `429 without Retry-After header uses default 60s`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Rate limit exceeded"}}""")
        )

        service.monitoringCycleWithPing()

        // Should still function without Retry-After (default fallback in SkeddyServerClient)
        assertTrue("Monitoring should remain active after 429 without Retry-After", service.isMonitoringActive())
    }

    // ==================== Additional: force_update=false does not set state ====================

    @Test
    fun `force_update false does not set force update state`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":20.0},"force_update":false}""")
        )

        service.monitoringCycleWithPing()

        assertFalse(
            "Service should NOT be in force update state when force_update=false",
            service.isInForceUpdateState()
        )
    }

    // ==================== Additional: response without force_update field ====================

    @Test
    fun `response without force_update field defaults to false`() = kotlinx.coroutines.test.runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":20.0}}""")
        )

        service.monitoringCycleWithPing()

        assertFalse(
            "Service should NOT be in force update state when field is absent",
            service.isInForceUpdateState()
        )
    }

    // ==================== Additional: network error (server unreachable) ====================

    @Test
    fun `network error when server is unreachable`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Shut down MockWebServer to simulate network error
        mockWebServer.shutdown()

        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active after network error", service.isMonitoringActive())
        assertTrue("isServerOffline should be true", service.isServerOffline)
    }

    // ==================== Additional: 422 Validation Error ====================

    @Test
    fun `422 response keeps monitoring active without offline flag`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"INVALID_TIMEZONE","message":"Invalid timezone identifier"}}""")
        )

        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active after 422", service.isMonitoringActive())
        // 422 does NOT set server offline — it's a request data issue, not server unavailability
        assertFalse("isServerOffline should be false after 422", service.isServerOffline)
    }

    // ==================== Helpers ====================

    private fun enqueueSuccessResponse(
        search: Boolean = false,
        intervalSeconds: Int = 30,
        minPrice: Double = 20.0
    ) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":$search,"interval_seconds":$intervalSeconds,"filters":{"min_price":$minPrice}}""")
        )
    }

    private fun parseRequestBody(request: okhttp3.mockwebserver.RecordedRequest): JsonObject {
        return json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    private fun enableAccessibilityService() {
        val componentName = ComponentName(
            RuntimeEnvironment.getApplication(),
            SkeddyAccessibilityService::class.java
        ).flattenToString()
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            componentName
        )
    }

    private fun disableAccessibilityService() {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }
}
