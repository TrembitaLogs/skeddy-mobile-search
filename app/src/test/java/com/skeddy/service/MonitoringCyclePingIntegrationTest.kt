package com.skeddy.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.network.ApiResult
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.Filters
import com.skeddy.network.models.PingResponse
import com.skeddy.network.models.RideData
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.RideReportResponse
import com.skeddy.notification.SkeddyNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Integration tests for ping cycle in MonitoringForegroundService.
 *
 * Test Strategy (from task 14.3):
 * 1. Ping 200 with search=true → verify executeSearchCycle() is called
 * 2. Ping 200 with search=false → verify scheduleNextPing is called without executeSearchCycle
 * 3. Verify Accessibility Service check happens first (before ping)
 *
 * Test Strategy (from task 14.4):
 * 1. handlePingSuccess with forceUpdate=true → verify processPendingRideQueue(), scheduleNextPing
 * 2. handlePingSuccess with search=false → processPendingRideQueue() and scheduleNextPing
 * 3. handlePingSuccess with search=true → processPendingRideQueue(), executeSearchCycle, scheduleNextPing
 * 4. Verify pendingStats.reset() is called before processPendingRideQueue()
 * 5. Test processPendingRideQueue with mock PendingRideQueue and ServerClient
 * 6. Verify processPendingRideQueue() is called on ANY successful ping
 * 7. Verify pendingRideQueue.cleanupExpired() is called before dequeueAll()
 * 8. Verify foreground notification is updated after each successful ping
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MonitoringCyclePingIntegrationTest {

    private lateinit var service: MonitoringForegroundService
    private lateinit var shadowLooper: ShadowLooper
    private lateinit var mockServerClient: SkeddyServerClient
    private lateinit var mockPendingRideQueue: PendingRideQueue

    @Before
    fun setUp() {
        // Enable Accessibility Service
        enableAccessibilityService()

        // Create service via Robolectric (calls onCreate)
        service = Robolectric.setupService(MonitoringForegroundService::class.java)
        shadowLooper = Shadows.shadowOf(Looper.getMainLooper())

        // Replace serverClient with a mock
        mockServerClient = mockk(relaxed = true)
        service.serverClient = mockServerClient

        // Replace pendingRideQueue with a mock
        mockPendingRideQueue = mockk(relaxed = true)
        every { mockPendingRideQueue.dequeueAll() } returns emptyList()
        service.pendingRideQueue = mockPendingRideQueue
    }

    @After
    fun tearDown() {
        disableAccessibilityService()
    }

    // ==================== 1. Ping 200 search=true → executeSearchCycle called ====================

    @Test
    fun `ping success with search true triggers search cycle`() = runTest {
        // Set consecutiveFailures to a known non-zero value.
        // executeSearchCycleWithRetry resets it to 0 on successful completion
        // (executeSearchCycle returns early in test — no exception).
        // If search cycle is NOT reached, consecutiveFailures remains unchanged.
        service.consecutiveFailures = 5

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 30,
                filters = Filters(minPrice = 25.0)
            )
        )

        service.monitoringCycleWithPing()

        // executeSearchCycleWithRetry was called and completed (no exception)
        // → consecutiveFailures reset to 0
        assertEquals(
            "consecutiveFailures should be 0 after search cycle ran",
            0,
            service.consecutiveFailures
        )
        // Verify ping was actually called
        coVerify(exactly = 1) { mockServerClient.ping(any()) }
    }

    // ==================== 2. Ping 200 search=false → no executeSearchCycle ====================

    @Test
    fun `ping success with search false skips search cycle`() = runTest {
        // Set consecutiveFailures to a known non-zero value.
        // If executeSearchCycleWithRetry is NOT called, it stays unchanged.
        service.consecutiveFailures = 5

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = false,
                intervalSeconds = 60,
                filters = Filters(minPrice = 20.0)
            )
        )

        service.monitoringCycleWithPing()

        // executeSearchCycleWithRetry was NOT called → consecutiveFailures unchanged
        assertEquals(
            "consecutiveFailures should remain 5 (search cycle not called)",
            5,
            service.consecutiveFailures
        )
        coVerify(exactly = 1) { mockServerClient.ping(any()) }
    }

    // ==================== 3. Accessibility check happens first ====================

    @Test
    fun `accessibility check stops service before ping`() = runTest {
        // Disable Accessibility Service
        disableAccessibilityService()

        // Start monitoring so we can verify it gets stopped.
        // Do NOT idle the looper — avoid triggering monitoringRunnable
        // which would also call monitoringCycleWithPing() in a coroutine.
        service.startMonitoring()
        assertTrue("Monitoring should be active initially", service.isMonitoringActive())

        service.monitoringCycleWithPing()

        // Service should have stopped monitoring
        assertFalse(
            "Monitoring should be stopped when accessibility is disabled",
            service.isMonitoringActive()
        )
        // Ping should NOT have been called — accessibility check happens first
        coVerify(exactly = 0) { mockServerClient.ping(any()) }
    }

    // ==================== State update verification ====================

    @Test
    fun `ping success updates currentInterval and currentMinPrice`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = false,
                intervalSeconds = 45,
                filters = Filters(minPrice = 30.0)
            )
        )

        service.monitoringCycleWithPing()

        assertEquals("currentInterval should be updated from ping response", 45, service.currentInterval)
        assertEquals("currentMinPrice should be updated from ping response", 30.0, service.currentMinPrice, 0.001)
    }

    @Test
    fun `ping success resets pendingStats`() = runTest {
        // Accumulate some stats
        service.pendingStats.incrementCycles()
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(3)
        val oldBatchId = service.pendingStats.batchId

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = false,
                intervalSeconds = 30,
                filters = Filters(minPrice = 20.0)
            )
        )

        service.monitoringCycleWithPing()

        assertEquals("cyclesSinceLastPing should be reset to 0", 0, service.pendingStats.cyclesSinceLastPing)
        assertEquals("ridesFound should be reset to 0", 0, service.pendingStats.ridesFound)
        assertTrue("batchId should change after reset", service.pendingStats.batchId != oldBatchId)
    }

    @Test
    fun `ping success with force update skips search cycle`() = runTest {
        service.consecutiveFailures = 5

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true, // search=true but force_update overrides
                intervalSeconds = 300,
                filters = Filters(minPrice = 20.0),
                forceUpdate = true,
                updateUrl = "https://example.com/update.apk"
            )
        )

        service.monitoringCycleWithPing()

        // force_update → executeSearchCycleWithRetry NOT called → consecutiveFailures unchanged
        assertEquals(
            "consecutiveFailures should remain 5 (force update skips search)",
            5,
            service.consecutiveFailures
        )
        assertTrue("Service should be in force update state", service.isInForceUpdateState())
    }

    // ==================== Error handler scheduling verification ====================

    @Test
    fun `ping network error schedules retry`() = runTest {
        // Start monitoring without idling the looper
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        service.monitoringCycleWithPing()

        // Service should still be monitoring (not stopped)
        assertTrue("Monitoring should remain active after network error", service.isMonitoringActive())
        coVerify(exactly = 1) { mockServerClient.ping(any()) }
    }

    @Test
    fun `ping unauthorized stops service and clears token`() = runTest {
        // Start monitoring without idling the looper
        service.startMonitoring()
        assertTrue("Monitoring should be active initially", service.isMonitoringActive())

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Unauthorized

        service.monitoringCycleWithPing()

        // Unauthorized → clearDeviceToken + stop service
        assertFalse("Monitoring should be stopped after unauthorized", service.isMonitoringActive())
    }

    // ==================== Task 14.4: processPendingRideQueue on every ping ====================

    @Test
    fun `handlePingSuccess with forceUpdate calls processPendingRideQueue`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 300,
                filters = Filters(minPrice = 20.0),
                forceUpdate = true,
                updateUrl = "https://example.com/update.apk"
            )
        )

        service.monitoringCycleWithPing()

        // processPendingRideQueue drains queue on any successful ping, including force_update
        verify(exactly = 1) { mockPendingRideQueue.cleanupExpired() }
        verify(exactly = 1) { mockPendingRideQueue.dequeueAll() }
    }

    @Test
    fun `handlePingSuccess with search false calls processPendingRideQueue`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = false,
                intervalSeconds = 60,
                filters = Filters(minPrice = 20.0)
            )
        )

        service.monitoringCycleWithPing()

        verify(exactly = 1) { mockPendingRideQueue.cleanupExpired() }
        verify(exactly = 1) { mockPendingRideQueue.dequeueAll() }
    }

    @Test
    fun `handlePingSuccess with search true calls processPendingRideQueue`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 30,
                filters = Filters(minPrice = 25.0)
            )
        )

        service.monitoringCycleWithPing()

        verify(exactly = 1) { mockPendingRideQueue.cleanupExpired() }
        verify(exactly = 1) { mockPendingRideQueue.dequeueAll() }
    }

    // ==================== Task 14.4: processPendingRideQueue sends pending rides ====================

    @Test
    fun `processPendingRideQueue sends each pending ride report`() = runTest {
        val request1 = createRideReportRequest("key-1")
        val request2 = createRideReportRequest("key-2")

        every { mockPendingRideQueue.dequeueAll() } returns listOf(request1, request2)
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "uuid")
        )

        service.processPendingRideQueue()

        coVerify(exactly = 1) { mockServerClient.reportRide(request1) }
        coVerify(exactly = 1) { mockServerClient.reportRide(request2) }
    }

    @Test
    fun `processPendingRideQueue removes successfully sent rides from queue`() = runTest {
        val request1 = createRideReportRequest("key-1")
        val request2 = createRideReportRequest("key-2")

        every { mockPendingRideQueue.dequeueAll() } returns listOf(request1, request2)
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "uuid")
        )

        service.processPendingRideQueue()

        verify(exactly = 1) { mockPendingRideQueue.remove(request1) }
        verify(exactly = 1) { mockPendingRideQueue.remove(request2) }
    }

    @Test
    fun `processPendingRideQueue keeps failed rides in queue for retry`() = runTest {
        val request1 = createRideReportRequest("key-1")
        val request2 = createRideReportRequest("key-2")

        every { mockPendingRideQueue.dequeueAll() } returns listOf(request1, request2)
        coEvery { mockServerClient.reportRide(request1) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "uuid")
        )
        coEvery { mockServerClient.reportRide(request2) } returns ApiResult.NetworkError

        service.processPendingRideQueue()

        // First ride succeeded → removed
        verify(exactly = 1) { mockPendingRideQueue.remove(request1) }
        // Second ride failed → NOT removed (stays in queue for next ping)
        verify(exactly = 0) { mockPendingRideQueue.remove(request2) }
    }

    @Test
    fun `processPendingRideQueue with empty queue does not call reportRide`() = runTest {
        every { mockPendingRideQueue.dequeueAll() } returns emptyList()

        service.processPendingRideQueue()

        coVerify(exactly = 0) { mockServerClient.reportRide(any()) }
    }

    @Test
    fun `processPendingRideQueue calls cleanupExpired before dequeueAll`() = runTest {
        every { mockPendingRideQueue.dequeueAll() } returns emptyList()

        service.processPendingRideQueue()

        verifyOrder {
            mockPendingRideQueue.cleanupExpired()
            mockPendingRideQueue.dequeueAll()
        }
    }

    // ==================== Task 14.4: pendingStats.reset() before processPendingRideQueue ====================

    @Test
    fun `handlePingSuccess resets stats before processing pending rides`() = runTest {
        // Accumulate stats to detect reset
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(5)
        val oldBatchId = service.pendingStats.batchId

        // Track state when dequeueAll is called to verify stats were already reset
        var statsResetBeforeQueue = false
        every { mockPendingRideQueue.dequeueAll() } answers {
            statsResetBeforeQueue = (service.pendingStats.cyclesSinceLastPing == 0
                    && service.pendingStats.ridesFound == 0
                    && service.pendingStats.batchId != oldBatchId)
            emptyList()
        }

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = false, intervalSeconds = 30, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        assertTrue(
            "pendingStats should be reset before processPendingRideQueue drains the queue",
            statsResetBeforeQueue
        )
    }

    // ==================== Task 14.4: Notification update after successful ping ====================

    @Test
    fun `handlePingSuccess with search true updates notification with searching state`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 45, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        val shadowNotificationManager = Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNotificationManager.getNotification(
            SkeddyNotificationManager.NOTIFICATION_MONITORING_ID
        )

        assertNotNull("Notification should be posted", notification)
        val extras = notification.extras
        val contentText = extras.getCharSequence("android.text")?.toString()
        assertTrue(
            "Notification should contain interval 45 when searching: '$contentText'",
            contentText != null && contentText.contains("45")
        )
    }

    @Test
    fun `handlePingSuccess with search false updates notification with stopped state`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = false, intervalSeconds = 60, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        val shadowNotificationManager = Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNotificationManager.getNotification(
            SkeddyNotificationManager.NOTIFICATION_MONITORING_ID
        )

        assertNotNull("Notification should be posted", notification)
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        assertEquals("Notification title should be 'Skeddy Search'", "Skeddy Search", title)
    }

    @Test
    fun `handlePingSuccess with forceUpdate updates notification with stopped state`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 300,
                filters = Filters(minPrice = 20.0),
                forceUpdate = true,
                updateUrl = "https://example.com/update.apk"
            )
        )

        service.monitoringCycleWithPing()

        val shadowNotificationManager = Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        val notification = shadowNotificationManager.getNotification(
            SkeddyNotificationManager.NOTIFICATION_MONITORING_ID
        )

        assertNotNull("Notification should be posted", notification)
    }

    @Test
    fun `createNotification with searching true includes interval`() {
        service.currentInterval = 30
        val notification = service.createNotification(searching = true)

        val contentText = notification.extras.getCharSequence("android.text")?.toString()
        assertTrue(
            "Notification text should include interval when searching: '$contentText'",
            contentText != null && contentText.contains("30")
        )
    }

    @Test
    fun `createNotification with searching false shows stopped text`() {
        val notification = service.createNotification(searching = false)

        val title = notification.extras.getCharSequence("android.title")?.toString()
        assertEquals("Skeddy Search", title)
    }

    // ==================== Task 16.2: Search state broadcast verification ====================

    @Test
    fun `handlePingSuccess with search true broadcasts isSearchActive true`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 25.0))
        )

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertTrue(
            "isSearchActive should be true when server says search=true",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    @Test
    fun `handlePingSuccess with search false broadcasts isSearchActive false`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = false, intervalSeconds = 60, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertFalse(
            "isSearchActive should be false when server says search=false",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    @Test
    fun `handlePingSuccess with forceUpdate broadcasts isSearchActive false`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 300,
                filters = Filters(minPrice = 20.0),
                forceUpdate = true,
                updateUrl = "https://example.com/update.apk"
            )
        )

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertFalse(
            "isSearchActive should be false when forceUpdate overrides search",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    @Test
    fun `isSearchActiveFromServer returns true after successful ping with search true`() = runTest {
        assertFalse("Initial search state should be false", service.isSearchActiveFromServer())

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 25.0))
        )

        service.monitoringCycleWithPing()

        assertTrue(
            "isSearchActiveFromServer should be true after ping with search=true",
            service.isSearchActiveFromServer()
        )
    }

    @Test
    fun `isSearchActiveFromServer returns false after successful ping with search false`() = runTest {
        // First make it true
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 25.0))
        )
        service.monitoringCycleWithPing()
        assertTrue("Should be true after search=true ping", service.isSearchActiveFromServer())

        // Then make it false
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = false, intervalSeconds = 60, filters = Filters(minPrice = 20.0))
        )
        service.monitoringCycleWithPing()

        assertFalse(
            "isSearchActiveFromServer should be false after ping with search=false",
            service.isSearchActiveFromServer()
        )
    }

    @Test
    fun `stopMonitoring resets isSearchActiveFromServer`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 25.0))
        )
        service.monitoringCycleWithPing()
        assertTrue("Should be true after ping", service.isSearchActiveFromServer())

        service.stopMonitoring()
        assertFalse("Should be false after stop", service.isSearchActiveFromServer())
    }

    @Test
    fun `startMonitoring resets isSearchActiveFromServer`() {
        // Directly set via a ping cycle
        service.startMonitoring()
        assertFalse("Should be false on fresh start", service.isSearchActiveFromServer())
    }

    // ==================== Task 23.3: Network failure → recovery integration tests ====================

    @Test
    fun `network error broadcasts server offline status`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent on network error", statusIntent)
        assertTrue(
            "serverOffline should be true after network error",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
        assertEquals(
            "searchState should be 'server_offline'",
            MonitoringForegroundService.SEARCH_STATE_OFFLINE,
            statusIntent.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `network error sets isServerOffline flag`() = runTest {
        assertFalse("isServerOffline should be false initially", service.isServerOffline)

        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        service.startMonitoring()
        service.monitoringCycleWithPing()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)
    }

    @Test
    fun `recovery after network error clears offline flag and broadcasts searching`() = runTest {
        service.startMonitoring()

        // Step 1: Simulate network failure
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.monitoringCycleWithPing()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)

        // Step 2: Simulate recovery — server responds with search=true
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 30,
                filters = Filters(minPrice = 25.0)
            )
        )

        service.monitoringCycleWithPing()

        // Verify offline flag is cleared
        assertFalse("isServerOffline should be false after recovery", service.isServerOffline)

        // Verify broadcast reflects recovery
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent after recovery", statusIntent)
        assertFalse(
            "serverOffline should be false after recovery",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, true)
        )
        assertEquals(
            "searchState should be 'searching' after recovery with search=true",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
        assertTrue(
            "isSearchActive should be true after recovery with search=true",
            statusIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    @Test
    fun `recovery with search false clears offline flag but does not start searching`() = runTest {
        service.startMonitoring()

        // Step 1: Simulate network failure
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.monitoringCycleWithPing()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)

        // Step 2: Server back online but search=false (e.g. outside schedule)
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = false,
                intervalSeconds = 60,
                filters = Filters(minPrice = 20.0)
            )
        )
        service.consecutiveFailures = 5

        service.monitoringCycleWithPing()

        // Offline flag should be cleared
        assertFalse("isServerOffline should be false after recovery", service.isServerOffline)

        // But search cycle should NOT have run (consecutiveFailures unchanged)
        assertEquals(
            "consecutiveFailures should remain 5 (search cycle not called when search=false)",
            5,
            service.consecutiveFailures
        )

        // Broadcast should indicate stopped, not searching
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertFalse(
            "isSearchActive should be false when server says search=false",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
        assertEquals(
            "searchState should be 'stopped' when server says search=false",
            MonitoringForegroundService.SEARCH_STATE_STOPPED,
            statusIntent.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `auto-resume requires no manual intervention after network recovery`() = runTest {
        service.startMonitoring()

        // Step 1: Network failure
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.monitoringCycleWithPing()

        assertTrue("Monitoring should remain active during offline", service.isMonitoringActive())
        assertTrue("isServerOffline should be true", service.isServerOffline)

        // Step 2: Recovery — call monitoringCycleWithPing directly (as retryPingRunnable does)
        // No manual start/restart needed
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 30,
                filters = Filters(minPrice = 25.0)
            )
        )

        service.monitoringCycleWithPing()

        // Verify full automatic recovery
        assertTrue("Monitoring should still be active", service.isMonitoringActive())
        assertFalse("isServerOffline should be cleared", service.isServerOffline)
        assertTrue(
            "isSearchActiveFromServer should be true after auto-resume",
            service.isSearchActiveFromServer()
        )

        // Verify two pings total (failure + recovery), no manual intervention
        coVerify(exactly = 2) { mockServerClient.ping(any()) }
    }

    @Test
    fun `multiple consecutive network errors maintain offline state`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        // Three consecutive network errors
        service.monitoringCycleWithPing()
        service.monitoringCycleWithPing()
        service.monitoringCycleWithPing()

        assertTrue("isServerOffline should remain true after multiple errors", service.isServerOffline)
        assertTrue("Monitoring should remain active (service keeps retrying)", service.isMonitoringActive())

        // All three attempts should have pinged the server
        coVerify(exactly = 3) { mockServerClient.ping(any()) }

        // Recovery after multiple failures
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 30,
                filters = Filters(minPrice = 25.0)
            )
        )

        service.monitoringCycleWithPing()

        assertFalse("isServerOffline should be cleared after recovery", service.isServerOffline)
    }

    @Test
    fun `network error broadcast includes isRunning true`() = runTest {
        service.startMonitoring()

        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        service.monitoringCycleWithPing()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Broadcast should be sent", statusIntent)
        assertTrue(
            "isMonitoring should be true in broadcast (service keeps running during offline)",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_MONITORING, false)
        )
    }

    // ==================== Task 26.2: Stats accumulation integration ====================

    @Test
    fun `search cycle increments cyclesSinceLastPing after reset`() = runTest {
        // After successful ping: reset() sets cyclesSinceLastPing=0,
        // then executeSearchCycleWithRetry() calls incrementCycles() → 1
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "cyclesSinceLastPing should be 1 after one search cycle (reset→0, increment→1)",
            1,
            service.pendingStats.cyclesSinceLastPing
        )
    }

    @Test
    fun `search false does not increment cyclesSinceLastPing`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = false, intervalSeconds = 60, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "cyclesSinceLastPing should be 0 when no search cycle ran",
            0,
            service.pendingStats.cyclesSinceLastPing
        )
    }

    @Test
    fun `multiple search cycles accumulate cyclesSinceLastPing between pings`() = runTest {
        // First ping → search=true → cycle runs → incrementCycles
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()
        assertEquals(1, service.pendingStats.cyclesSinceLastPing)

        // Simulate another cycle without a new ping (manually call executeSearchCycleWithRetry)
        // In real flow this doesn't happen (1 ping = 1 cycle), but verifies accumulation logic
        service.pendingStats.incrementCycles()
        assertEquals(2, service.pendingStats.cyclesSinceLastPing)

        // Next ping resets everything
        service.monitoringCycleWithPing()
        assertEquals(
            "cyclesSinceLastPing should be 1 after reset and new cycle",
            1,
            service.pendingStats.cyclesSinceLastPing
        )
    }

    @Test
    fun `ridesFound stays zero when no rides parsed in test environment`() = runTest {
        // In test environment, executeSearchCycle returns early (no AccessibilityService instance),
        // so no rides are parsed or filtered → addRidesFound is never called
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(search = true, intervalSeconds = 30, filters = Filters(minPrice = 20.0))
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "ridesFound should remain 0 when no rides are parsed",
            0,
            service.pendingStats.ridesFound
        )
    }

    @Test
    fun `force update does not increment cyclesSinceLastPing`() = runTest {
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(
            PingResponse(
                search = true,
                intervalSeconds = 300,
                filters = Filters(minPrice = 20.0),
                forceUpdate = true,
                updateUrl = "https://example.com/update.apk"
            )
        )

        service.monitoringCycleWithPing()

        assertEquals(
            "cyclesSinceLastPing should be 0 when force update skips search cycle",
            0,
            service.pendingStats.cyclesSinceLastPing
        )
    }

    // ==================== Helpers ====================

    private fun createRideReportRequest(idempotencyKey: String): RideReportRequest {
        return RideReportRequest(
            idempotencyKey = idempotencyKey,
            eventType = "ACCEPTED",
            rideHash = "testhash123",
            timezone = "America/New_York",
            rideData = RideData(
                price = 25.50,
                pickupTime = "Tomorrow · 6:05AM",
                pickupLocation = "Test Pickup",
                dropoffLocation = "Test Dropoff",
                duration = "9 min",
                distance = "3.6 mi",
                riderName = "TestRider"
            )
        )
    }

    private fun assertNotNull(message: String, obj: Any?) {
        assertTrue(message, obj != null)
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
