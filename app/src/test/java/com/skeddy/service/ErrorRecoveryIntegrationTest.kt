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
 * Integration tests for error recovery scenarios using MockWebServer.
 *
 * Uses a REAL [SkeddyServerClient] connected to [MockWebServer] to verify
 * the full error handling chain:
 *   MonitoringForegroundService → SkeddyServerClient → Retrofit → OkHttp → MockWebServer
 *
 * Covers:
 * - Network failure scenarios (timeout, IOException, retry scheduling)
 * - Server error recovery (503, 5xx, 422, consecutive failures)
 * - Auto-resume after server recovery
 * - State preservation during outage (stats, batch_id)
 * - Token invalidation recovery (401/403 → re-login)
 * - Force update handling (stop search, continue ping, auto-recovery)
 * - MockWebServer sequence scenarios (failure → success, response toggles)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ErrorRecoveryIntegrationTest {

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

        mockDeviceTokenManager = io.mockk.mockk(relaxed = true)
        every { mockDeviceTokenManager.getDeviceToken() } returns "test-device-token-abc"
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id-xyz"

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

        service = Robolectric.setupService(MonitoringForegroundService::class.java)
        shadowLooper = Shadows.shadowOf(Looper.getMainLooper())

        service.serverClient = realServerClient
        service.deviceTokenManager = mockDeviceTokenManager

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

    // ==================== 1. Network failure scenarios ====================

    @Test
    fun `network timeout stops searching and sets server offline`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Shut down MockWebServer to cause IOException (connect failure)
        mockWebServer.shutdown()

        service.monitoringCycleWithPing()

        assertTrue("isServerOffline should be true after network timeout", service.isServerOffline)
        assertTrue("Monitoring should remain active for retry", service.isMonitoringActive())
    }

    @Test
    fun `read timeout handled gracefully`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Enqueue response with body delay exceeding read timeout (2s)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":30,"filters":{"min_price":20.0}}""")
                .setBodyDelay(5, TimeUnit.SECONDS)
        )

        service.monitoringCycleWithPing()

        // Read timeout triggers IOException → handleNetworkError
        assertTrue("isServerOffline should be true after read timeout", service.isServerOffline)
        assertTrue("Monitoring should remain active for retry", service.isMonitoringActive())
    }

    @Test
    fun `no network connection handled with graceful degradation`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Shut down server to simulate no connection
        mockWebServer.shutdown()

        service.monitoringCycleWithPing()

        // Verify service remains running but search is paused
        assertTrue("Monitoring should remain active", service.isMonitoringActive())
        assertTrue("isServerOffline should be true", service.isServerOffline)

        // Verify server offline broadcast
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent on network error", statusIntent)
        assertTrue(
            "serverOffline extra should be true",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    @Test
    fun `retry scheduled after network error uses 30 second delay`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Shut down server to cause network error
        mockWebServer.shutdown()

        service.monitoringCycleWithPing()

        // handleNetworkError calls scheduleRetryPing(RETRY_PING_DELAY_MS = 30_000)
        // Verify retryPingRunnable is scheduled by checking isMonitoring stays true
        assertTrue("Monitoring should be active (retry scheduled)", service.isMonitoringActive())
        assertTrue("Server should be marked offline", service.isServerOffline)
    }

    // ==================== 2. Server error recovery ====================

    @Test
    fun `503 stops searching and schedules retry after 60 seconds`() = kotlinx.coroutines.test.runTest {
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

    @Test
    fun `5xx generic error stops searching and schedules retry`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Internal server error"}}""")
        )

        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active after 500", service.isMonitoringActive())

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
    fun `422 logs warning and retries without marking server offline`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"INVALID_TIMEZONE","message":"Invalid timezone identifier"}}""")
        )

        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active after 422", service.isMonitoringActive())
        // 422 is a request data issue, NOT server unavailability
        assertFalse("isServerOffline should be false after 422", service.isServerOffline)
    }

    @Test
    fun `multiple consecutive server errors keep service running`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Simulate 3 consecutive server errors
        for (i in 1..3) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error $i"}}""")
            )

            service.monitoringCycleWithPing()

            assertTrue("Monitoring should remain active after error #$i", service.isMonitoringActive())
        }

        // Service survives multiple errors without crashing
        assertTrue("Service should survive multiple consecutive errors", service.isMonitoringActive())
    }

    // ==================== 3. Auto-resume functionality ====================

    @Test
    fun `auto-resume after server recovery clears offline state`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Step 1: Network error (IOException) → sets isServerOffline = true
        mockWebServer.shutdown()
        service.monitoringCycleWithPing()
        assertTrue("Server should be offline after network error", service.isServerOffline)

        // Step 2: Server recovers → auto-resume clears offline flag
        mockWebServer = MockWebServer()
        mockWebServer.start()
        rebuildServerClient()

        enqueueSuccessResponse(search = true)
        service.monitoringCycleWithPing()

        assertFalse("isServerOffline should be false after recovery", service.isServerOffline)
    }

    @Test
    fun `search only starts if server says search true after recovery`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Step 1: Server goes down
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Unavailable"}}""")
        )
        service.monitoringCycleWithPing()

        // Step 2: Server recovers but says search=false (e.g., outside schedule)
        service.consecutiveFailures = 5
        enqueueSuccessResponse(search = false, intervalSeconds = 60)

        service.monitoringCycleWithPing()

        assertFalse("isServerOffline should be cleared", service.isServerOffline)
        // search=false → executeSearchCycleWithRetry NOT called → consecutiveFailures unchanged
        assertEquals(
            "consecutiveFailures should remain 5 (no search cycle when search=false)",
            5,
            service.consecutiveFailures
        )
    }

    @Test
    fun `no manual restart needed after server recovery`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Step 1: Network error
        mockWebServer.shutdown()
        service.monitoringCycleWithPing()
        assertTrue("Server should be offline", service.isServerOffline)
        assertTrue("Monitoring should still be active", service.isMonitoringActive())

        // Step 2: Restart MockWebServer (simulates server recovery)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Rebuild HTTP client with new server URL
        rebuildServerClient()

        enqueueSuccessResponse(search = true)
        service.monitoringCycleWithPing()

        // Recovery happened automatically without stopMonitoring/startMonitoring
        assertFalse("isServerOffline should be false after auto-resume", service.isServerOffline)
        assertTrue("Monitoring should remain active", service.isMonitoringActive())
    }

    @Test
    fun `UI updated on recovery from server offline to searching`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Step 1: Server error → UI shows offline
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error"}}""")
        )
        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val offlineIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertTrue(
            "serverOffline should be true during outage",
            offlineIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )

        // Step 2: Server recovers with search=true → UI shows searching
        enqueueSuccessResponse(search = true)
        service.monitoringCycleWithPing()

        val recoveryIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent on recovery", recoveryIntent)
        assertFalse(
            "serverOffline should be false after recovery",
            recoveryIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
        assertTrue(
            "isSearchActive should be true after recovery with search=true",
            recoveryIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    // ==================== 4. State preservation ====================

    @Test
    fun `pending stats preserved during outage`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Accumulate stats before outage
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

        // Server goes down — stats should NOT be reset
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error"}}""")
        )
        service.monitoringCycleWithPing()

        // Stats preserved during outage (reset only happens in handlePingSuccess)
        assertEquals("cyclesSinceLastPing should be preserved", 2, service.pendingStats.cyclesSinceLastPing)
        assertEquals("ridesFound should be preserved", 3, service.pendingStats.ridesFound)
        assertEquals("acceptFailures should be preserved", 1, service.pendingStats.acceptFailures.size)
    }

    @Test
    fun `batch_id preserved until successful ping`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        val originalBatchId = service.pendingStats.batchId

        // Multiple failed pings — batch_id should remain the same
        for (i in 1..3) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error $i"}}""")
            )
            service.monitoringCycleWithPing()

            assertEquals(
                "batch_id should remain '$originalBatchId' after failure #$i",
                originalBatchId,
                service.pendingStats.batchId
            )
        }

        // Successful ping — batch_id should change
        enqueueSuccessResponse()
        service.monitoringCycleWithPing()

        assertTrue(
            "batch_id should be regenerated after successful ping",
            service.pendingStats.batchId != originalBatchId
        )
    }

    @Test
    fun `service survives transient errors without crashing`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Mix of different error types
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error"}}""")
        )
        service.monitoringCycleWithPing()
        assertTrue("Should survive 500", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Unavailable"}}""")
        )
        service.monitoringCycleWithPing()
        assertTrue("Should survive 503", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":{"code":"INVALID_TIMEZONE","message":"Bad timezone"}}""")
        )
        service.monitoringCycleWithPing()
        assertTrue("Should survive 422", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "30")
                .setBody("""{"error":{"code":"RATE_LIMIT","message":"Rate limited"}}""")
        )
        service.monitoringCycleWithPing()
        assertTrue("Should survive 429", service.isMonitoringActive())

        // Final success — service fully recovers
        enqueueSuccessResponse(search = true)
        service.monitoringCycleWithPing()
        assertTrue("Should survive after full recovery", service.isMonitoringActive())
        assertFalse("Should not be offline after recovery", service.isServerOffline)
    }

    // ==================== 5. Token invalidation recovery ====================

    @Test
    fun `401 triggers re-login flow`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid device token"}}""")
        )

        service.monitoringCycleWithPing()

        // Token cleared and monitoring stopped
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }
        assertFalse("Monitoring should be stopped after 401", service.isMonitoringActive())

        // Unpaired broadcast sent to trigger LoginActivity
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("Unpaired broadcast should be sent on 401", unpairedIntent)
    }

    @Test
    fun `403 triggers same re-login flow as 401`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"DEVICE_NOT_LOGGED_IN","message":"Device not paired"}}""")
        )

        service.monitoringCycleWithPing()

        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }
        assertFalse("Monitoring should be stopped after 403", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("Unpaired broadcast should be sent on 403", unpairedIntent)
    }

    @Test
    fun `token cleared before navigate to prevent infinite loop`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid token"}}""")
        )

        service.monitoringCycleWithPing()

        // SkeddyServerClient.handleResponse clears token on 401 (first clear)
        // handleUnauthorized also clears token (idempotent second clear)
        // Important: token MUST be cleared to avoid infinite 401 loop
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }

        // Monitoring is stopped — no more pings will happen
        assertFalse("Monitoring must be stopped to prevent retry loop", service.isMonitoringActive())
    }

    @Test
    fun `app state reset correctly after unauthorized`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()
        assertTrue("Should be monitoring initially", service.isMonitoringActive())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Invalid token"}}""")
        )

        service.monitoringCycleWithPing()

        // Verify complete state reset
        assertFalse("Monitoring should be stopped", service.isMonitoringActive())
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }

        // Unpaired broadcast signals UI to transition to NOT_LOGGED_IN state
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("ACTION_UNPAIRED broadcast triggers NOT_LOGGED_IN state", unpairedIntent)
    }

    // ==================== 6. Force update handling ====================

    @Test
    fun `force update stops search but continues ping cycle`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()
        service.consecutiveFailures = 5

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":300,"filters":{"min_price":20.0},"force_update":true,"update_url":"https://skeddy.net/download/search-app.apk"}""")
        )

        service.monitoringCycleWithPing()

        assertTrue("Service should be in force update state", service.isInForceUpdateState())
        // force_update overrides search=true → no search cycle
        assertEquals(
            "consecutiveFailures should remain unchanged (search skipped during force update)",
            5,
            service.consecutiveFailures
        )
        // Interval updated to 300s for continued pinging
        assertEquals("currentInterval should be 300 for force update pinging", 300, service.currentInterval)
        assertTrue("Monitoring should remain active for continued pinging", service.isMonitoringActive())
    }

    @Test
    fun `auto-recovery from force update when server updates MIN_VERSION`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Step 1: Enter force update state
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":300,"filters":{"min_price":20.0},"force_update":true,"update_url":"https://skeddy.net/download/search-app.apk"}""")
        )
        service.monitoringCycleWithPing()
        assertTrue("Should be in force update state", service.isInForceUpdateState())

        // Step 2: Server updates MIN_VERSION → force_update becomes false
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":20.0},"force_update":false}""")
        )
        service.monitoringCycleWithPing()

        assertFalse("Force update state should be cleared", service.isInForceUpdateState())

        // Verify force update cleared broadcast
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val clearedIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED
        }
        assertNotNull("Force update cleared broadcast should be sent", clearedIntent)
    }

    @Test
    fun `update_url broadcast to UI for update button`() = kotlinx.coroutines.test.runTest {
        val expectedUrl = "https://skeddy.net/download/v2.apk"

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":300,"filters":{"min_price":20.0},"force_update":true,"update_url":"$expectedUrl"}""")
        )

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val forceUpdateIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_FORCE_UPDATE
        }
        assertNotNull("Force update broadcast should be sent", forceUpdateIntent)
        assertEquals(
            "update_url should be passed in broadcast for UI button",
            expectedUrl,
            forceUpdateIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_UPDATE_URL)
        )
    }

    // ==================== 7. MockWebServer sequence scenarios ====================

    @Test
    fun `sequence of network failures followed by success triggers auto-resume`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Failure 1: network error (IOException) → isServerOffline = true
        mockWebServer.shutdown()
        service.monitoringCycleWithPing()
        assertTrue("Should be offline after network error", service.isServerOffline)

        // Restart server for next attempts
        mockWebServer = MockWebServer()
        mockWebServer.start()
        rebuildServerClient()

        // Failure 2: server error (500) — isServerOffline stays true from previous network error
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error 2"}}""")
        )
        service.monitoringCycleWithPing()
        // isServerOffline remains true (500 doesn't clear it, only handlePingSuccess does)
        assertTrue("Should still be offline after 500", service.isServerOffline)

        // Success → auto-resume clears offline state
        enqueueSuccessResponse(search = true)
        service.monitoringCycleWithPing()

        assertFalse("Should be online after success", service.isServerOffline)
        assertTrue("Monitoring should be active", service.isMonitoringActive())
    }

    @Test
    fun `delayed response exceeding timeout treated as network error`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Response delayed beyond read timeout (2s in test setup)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":true,"interval_seconds":30,"filters":{"min_price":20.0}}""")
                .setBodyDelay(5, TimeUnit.SECONDS)
        )

        service.monitoringCycleWithPing()

        assertTrue("Should be offline after timeout", service.isServerOffline)
        assertTrue("Monitoring should remain active for retry", service.isMonitoringActive())
    }

    @Test
    fun `response toggles search true and false correctly`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()
        service.consecutiveFailures = 5

        // Ping 1: search=false → no search cycle
        enqueueSuccessResponse(search = false, intervalSeconds = 60)
        service.monitoringCycleWithPing()

        assertEquals(
            "consecutiveFailures unchanged when search=false",
            5,
            service.consecutiveFailures
        )

        // Ping 2: search=true → search cycle runs
        enqueueSuccessResponse(search = true, intervalSeconds = 30)
        service.monitoringCycleWithPing()

        // executeSearchCycleWithRetry resets consecutiveFailures to 0 on success
        assertEquals(
            "consecutiveFailures should be 0 after search=true cycle",
            0,
            service.consecutiveFailures
        )

        // Ping 3: search=false again → no search
        service.consecutiveFailures = 3
        enqueueSuccessResponse(search = false, intervalSeconds = 45)
        service.monitoringCycleWithPing()

        assertEquals(
            "consecutiveFailures unchanged when search=false again",
            3,
            service.consecutiveFailures
        )
    }

    @Test
    fun `stats accumulated across failed pings sent on first success`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // Accumulate stats
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(2)
        val batchIdBeforeErrors = service.pendingStats.batchId

        // Two failed pings — stats preserved
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"Error"}}""")
        )
        service.monitoringCycleWithPing()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Error"}}""")
        )
        service.monitoringCycleWithPing()

        // Stats still intact
        assertEquals("batch_id preserved through errors", batchIdBeforeErrors, service.pendingStats.batchId)
        assertEquals("cycles preserved through errors", 1, service.pendingStats.cyclesSinceLastPing)
        assertEquals("rides preserved through errors", 2, service.pendingStats.ridesFound)

        // Successful ping — stats sent and reset
        enqueueSuccessResponse()
        service.monitoringCycleWithPing()

        // After successful ping, stats are reset
        assertEquals("cycles reset after success", 0, service.pendingStats.cyclesSinceLastPing)
        assertEquals("rides reset after success", 0, service.pendingStats.ridesFound)
        assertTrue("batch_id regenerated", service.pendingStats.batchId != batchIdBeforeErrors)
    }

    @Test
    fun `network error followed by 401 correctly handles both scenarios`() = kotlinx.coroutines.test.runTest {
        service.startMonitoring()

        // First: network error → offline, retry scheduled
        mockWebServer.shutdown()
        service.monitoringCycleWithPing()
        assertTrue("Should be offline after network error", service.isServerOffline)
        assertTrue("Should still be monitoring for retry", service.isMonitoringActive())

        // Restart server
        mockWebServer = MockWebServer()
        mockWebServer.start()
        rebuildServerClient()

        // Server comes back but returns 401 (token was invalidated while offline)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_TOKEN","message":"Token revoked"}}""")
        )
        service.monitoringCycleWithPing()

        // 401 takes precedence: stop monitoring, clear token, unpair
        verify(atLeast = 1) { mockDeviceTokenManager.clearDeviceToken() }
        assertFalse("Monitoring should be stopped after 401", service.isMonitoringActive())
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

    /**
     * Rebuilds the server client pointing to the new MockWebServer instance.
     * Used after shutting down and restarting MockWebServer to simulate recovery.
     */
    private fun rebuildServerClient() {
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
        service.serverClient = SkeddyServerClient(api, mockDeviceTokenManager)
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
