package com.skeddy.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import com.skeddy.data.generateRideKey
import com.skeddy.filter.RideFilter
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftNavigator
import com.skeddy.navigation.LyftScreen
import com.skeddy.network.AuthInterceptor
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.AcceptFailure
import com.skeddy.network.models.RideData
import com.skeddy.network.models.RideReportRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the auto-accept flow.
 *
 * Tests the complete chain: cascading filter -> auto-accept -> server report -> blacklist.
 * Uses MockWebServer for real HTTP chain verification and Robolectric for Android components.
 *
 * Sections:
 * 1. Cascading filter integration (RideFilter + BlacklistRepository)
 * 2. AutoAcceptManager + Server integration (MockWebServer for POST /rides)
 * 3. Blacklist integration (SHA-256 key, exists, cleanup, TTL)
 * 4. PendingRideQueue integration (enqueue, persistence, drain via MockWebServer)
 * 5. Accept failures tracking (stats accumulation, reporting in ping request)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoAcceptFlowIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var serverClient: SkeddyServerClient
    private lateinit var rideFilter: RideFilter
    private lateinit var pendingRideQueue: PendingRideQueue
    private lateinit var autoAcceptManager: AutoAcceptManager

    // Mocked components (UI automation unavailable in unit tests)
    private lateinit var mockAccessibilityService: SkeddyAccessibilityService
    private lateinit var mockNavigator: LyftNavigator
    private lateinit var mockBlacklistRepository: BlacklistRepository
    private lateinit var mockDeviceTokenManager: DeviceTokenManager
    private lateinit var mockRideCard: AccessibilityNodeInfo

    private val json = Json { ignoreUnknownKeys = true }

    private val testRide = ScheduledRide(
        id = "ride_integration_test",
        price = 25.50,
        bonus = 5.0,
        pickupTime = "Tomorrow \u00b7 6:05AM",
        pickupLocation = "123 Main St",
        dropoffLocation = "456 Oak Ave",
        duration = "25 min",
        distance = "12 mi",
        riderName = "John D.",
        riderRating = 4.8,
        isVerified = true
    )

    @Before
    fun setUp() {
        // Real MockWebServer for HTTP verification
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Mocked DeviceTokenManager for auth headers
        mockDeviceTokenManager = mockk(relaxed = true)
        every { mockDeviceTokenManager.getDeviceToken() } returns "test-token"
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id"

        // Real HTTP stack connected to MockWebServer
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
        serverClient = SkeddyServerClient(api, mockDeviceTokenManager)

        // Mocked UI components (cannot run UI automation in unit tests)
        mockAccessibilityService = mockk(relaxed = true)
        mockNavigator = mockk(relaxed = true)
        mockBlacklistRepository = mockk(relaxed = true)
        mockRideCard = mockk(relaxed = true)

        // Default: blacklist does not contain any rides
        coEvery { mockBlacklistRepository.exists(any()) } returns false

        // Real PendingRideQueue with plain SharedPreferences (Robolectric)
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_pending_rides", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        pendingRideQueue = PendingRideQueue(prefs)

        // Real RideFilter with mocked BlacklistRepository
        rideFilter = RideFilter(mockBlacklistRepository)

        // AutoAcceptManager with mocked UI + real network + real queue
        autoAcceptManager = AutoAcceptManager(
            mockAccessibilityService,
            mockNavigator,
            serverClient,
            mockBlacklistRepository,
            pendingRideQueue
        )
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {
            // Server may already be shut down
        }
        unmockkAll()
    }

    // ==================== 1. Cascading filter integration ====================

    @Test
    fun `ride under 10 dollars is skipped by hardcoded filter`() = runTest {
        val cheapRide = testRide.copy(id = "cheap", price = 9.99)

        val result = rideFilter.filterRides(listOf(cheapRide), serverMinPrice = 20.0)

        assertTrue("Ride under $10 should be filtered out", result.isEmpty())
    }

    @Test
    fun `ride at exactly 10 dollars passes hardcoded filter`() = runTest {
        val exactRide = testRide.copy(id = "exact10", price = 10.0)

        val result = rideFilter.filterRides(listOf(exactRide), serverMinPrice = 10.0)

        assertEquals("Ride at exactly $10 should pass", 1, result.size)
    }

    @Test
    fun `ride between hardcoded and server min price is skipped`() = runTest {
        val midRide = testRide.copy(id = "mid", price = 15.0)

        val result = rideFilter.filterRides(listOf(midRide), serverMinPrice = 20.0)

        assertTrue("Ride at $15 with serverMinPrice=$20 should be filtered", result.isEmpty())
    }

    @Test
    fun `blacklisted ride is skipped even if price passes`() = runTest {
        coEvery { mockBlacklistRepository.exists(any()) } returns true

        val result = rideFilter.filterRides(listOf(testRide), serverMinPrice = 20.0)

        assertTrue("Blacklisted ride should be filtered out", result.isEmpty())
    }

    @Test
    fun `ride passing all filters proceeds`() = runTest {
        val result = rideFilter.filterRides(listOf(testRide), serverMinPrice = 20.0)

        assertEquals("Ride passing all filters should proceed", 1, result.size)
        assertEquals(testRide, result[0])
    }

    @Test
    fun `cascading filter applies in correct order across multiple rides`() = runTest {
        val rides = listOf(
            testRide.copy(id = "cheap", price = 5.0),
            testRide.copy(id = "mid", price = 15.0),
            testRide.copy(id = "blacklisted", price = 25.0),
            testRide.copy(id = "good", price = 30.0)
        )

        // Only the "blacklisted" ride is in the blacklist
        val blacklistedKey = generateRideKey(testRide.copy(id = "blacklisted", price = 25.0))
        coEvery { mockBlacklistRepository.exists(any()) } returns false
        coEvery { mockBlacklistRepository.exists(blacklistedKey) } returns true

        val result = rideFilter.filterRides(rides, serverMinPrice = 20.0)

        assertEquals("Only 'good' ride should pass all filters", 1, result.size)
        assertEquals("good", result[0].id)
    }

    // ==================== 2. AutoAcceptManager + Server (MockWebServer) ====================

    @Test
    fun `successful accept sends POST rides to server`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/rides", request.path)
    }

    @Test
    fun `ride data in report matches ride fields per API contract`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = parseRequestBody(request!!)

        assertEquals("ACCEPTED", body["event_type"]?.jsonPrimitive?.content)

        val rideData = body["ride_data"]?.jsonObject
        assertNotNull("ride_data should be present", rideData)
        assertEquals(testRide.price, rideData!!["price"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals(testRide.pickupTime, rideData["pickup_time"]?.jsonPrimitive?.content)
        assertEquals(testRide.pickupLocation, rideData["pickup_location"]?.jsonPrimitive?.content)
        assertEquals(testRide.dropoffLocation, rideData["dropoff_location"]?.jsonPrimitive?.content)
        assertEquals(testRide.duration, rideData["duration"]?.jsonPrimitive?.content)
        assertEquals(testRide.distance, rideData["distance"]?.jsonPrimitive?.content)
        assertEquals(testRide.riderName, rideData["rider_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `idempotency key is valid UUID v4 format`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = parseRequestBody(request!!)

        val idempotencyKey = body["idempotency_key"]?.jsonPrimitive?.content
        assertNotNull("idempotency_key should be present", idempotencyKey)
        assertTrue(
            "idempotency_key should match UUID format, got: $idempotencyKey",
            idempotencyKey!!.matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            )
        )
    }

    @Test
    fun `each accept generates unique idempotency key`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        val request1 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        val request2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request1)
        assertNotNull(request2)

        val key1 = parseRequestBody(request1!!)["idempotency_key"]?.jsonPrimitive?.content
        val key2 = parseRequestBody(request2!!)["idempotency_key"]?.jsonPrimitive?.content

        assertNotEquals("Each accept should have a unique idempotency key", key1, key2)
    }

    @Test
    fun `POST rides includes auth headers`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("test-token", request!!.getHeader("X-Device-Token"))
        assertEquals("test-device-id", request.getHeader("X-Device-ID"))
    }

    // ==================== 3. Blacklist integration ====================

    @Test
    fun `ride key is SHA-256 hash of composite key`() {
        val rideKey = generateRideKey(testRide)

        assertEquals("SHA-256 produces 64 hex characters", 64, rideKey.length)
        assertTrue("Key should be lowercase hex", rideKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `same ride data produces same ride key`() {
        val key1 = generateRideKey(testRide)
        val key2 = generateRideKey(testRide)

        assertEquals("Same inputs should produce same key", key1, key2)
    }

    @Test
    fun `blacklist insert called on successful accept`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        assertTrue(result is AutoAcceptResult.Success)
        coVerify(exactly = 1) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `blacklist insert called regardless of server report failure`() = runTest {
        setupSuccessfulUiFlow()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"fail"}}""")
        )

        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        assertTrue(result is AutoAcceptResult.Success)
        coVerify(exactly = 1) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `blacklist prevents re-accepting same ride via filter`() = runTest {
        // First: ride is not blacklisted -> passes filter
        val firstResult = rideFilter.filterRides(listOf(testRide), serverMinPrice = 20.0)
        assertEquals(1, firstResult.size)

        // Simulate blacklist entry (as if addToBlacklist was called after accept)
        coEvery { mockBlacklistRepository.exists(any()) } returns true

        // Second: same ride is now blacklisted -> filtered out
        val secondResult = rideFilter.filterRides(listOf(testRide), serverMinPrice = 20.0)
        assertTrue("Blacklisted ride should not pass filter", secondResult.isEmpty())
    }

    @Test
    fun `blacklist fallback TTL is 10 days`() {
        // BlacklistRepository uses 10 days (not 48h from PRD) because scheduled rides
        // can be up to a week in the future; a shorter TTL would cause duplicate accepts
        val tenDaysMs = 10L * 24 * 60 * 60 * 1000
        assertEquals(
            "Fallback TTL should be 10 days",
            tenDaysMs,
            BlacklistRepository.FALLBACK_TTL_MS
        )
    }

    // ==================== 4. PendingRideQueue integration ====================

    @Test
    fun `failed POST enqueues request in pending queue`() = runTest {
        setupSuccessfulUiFlow()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"fail"}}""")
        )

        assertTrue("Queue should be empty before accept", pendingRideQueue.isEmpty())

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        assertFalse("Queue should have entry after failed POST", pendingRideQueue.isEmpty())
        val entries = pendingRideQueue.dequeueAll()
        assertEquals(1, entries.size)
        assertEquals("ACCEPTED", entries[0].eventType)
    }

    @Test
    fun `successful POST removes request from pending queue`() = runTest {
        setupSuccessfulUiFlow()
        enqueueRideReportSuccess()

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        assertTrue("Queue should be empty after successful POST", pendingRideQueue.isEmpty())
    }

    @Test
    fun `pending queue persists across instances`() = runTest {
        setupSuccessfulUiFlow()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"code":"INTERNAL_ERROR","message":"fail"}}""")
        )

        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Create new PendingRideQueue instance with same SharedPreferences
        val samePrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_pending_rides", Context.MODE_PRIVATE)
        val newQueue = PendingRideQueue(samePrefs)

        assertFalse("New instance should see persisted entries", newQueue.isEmpty())
        val entries = newQueue.dequeueAll()
        assertEquals(1, entries.size)
        assertEquals("ACCEPTED", entries[0].eventType)
    }

    @Test
    fun `pending reports drained via processPendingRideQueue`() = runTest {
        // Pre-enqueue a ride report
        val rideReport = RideReportRequest(
            idempotencyKey = "drain-test-key",
            eventType = "ACCEPTED",
            rideHash = "draintesthash",
            timezone = "America/New_York",
            rideData = RideData(
                price = 25.50,
                pickupTime = "Tomorrow \u00b7 6:05AM",
                pickupLocation = "123 Main St",
                dropoffLocation = "456 Oak Ave",
                duration = "25 min",
                distance = "12 mi",
                riderName = "John D."
            )
        )
        pendingRideQueue.enqueue(rideReport)
        assertFalse("Queue should not be empty", pendingRideQueue.isEmpty())

        // Enqueue successful response for the ride report POST
        enqueueRideReportSuccess()

        // Create service and inject real dependencies
        enableAccessibilityService()
        val service = Robolectric.setupService(MonitoringForegroundService::class.java)
        service.serverClient = serverClient
        service.pendingRideQueue = pendingRideQueue

        // Drain the queue
        service.processPendingRideQueue()

        // Verify HTTP request was sent to the correct endpoint
        val httpRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Pending ride report should be sent", httpRequest)
        assertEquals("/api/v1/rides", httpRequest!!.path)

        val body = parseRequestBody(httpRequest)
        assertEquals("drain-test-key", body["idempotency_key"]?.jsonPrimitive?.content)

        // Queue should be empty after successful drain
        assertTrue("Queue should be empty after drain", pendingRideQueue.isEmpty())

        disableAccessibilityService()
    }

    @Test
    fun `expired entries removed before sending`() {
        // Create a queue with a custom timeProvider to simulate time passage
        var currentTime = System.currentTimeMillis()
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_expiry_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val timedQueue = PendingRideQueue(prefs) { currentTime }

        val rideReport = RideReportRequest(
            idempotencyKey = "expiry-test",
            eventType = "ACCEPTED",
            rideHash = "expirytesthash",
            timezone = "America/New_York",
            rideData = RideData(
                price = 25.50,
                pickupTime = "unparseable format",
                pickupLocation = "123 Main St",
                dropoffLocation = "456 Oak Ave",
                duration = "25 min",
                distance = "12 mi",
                riderName = "John D."
            )
        )
        timedQueue.enqueue(rideReport)
        assertFalse("Queue should not be empty", timedQueue.isEmpty())

        // Advance time past fallback TTL (48 hours)
        currentTime += PendingRideQueue.FALLBACK_TTL_MILLIS + 1

        timedQueue.cleanupExpired()

        assertTrue("Expired entry should be removed", timedQueue.isEmpty())
    }

    @Test
    fun `PendingRideQueue fallback TTL is 48 hours`() {
        assertEquals(
            "Fallback TTL should be 48 hours (172800000 ms)",
            172_800_000L,
            PendingRideQueue.FALLBACK_TTL_MILLIS
        )
    }

    // ==================== 5. Accept failures tracking ====================

    @Test
    fun `accept failure added to stats accumulator`() {
        val pendingStats = PendingStatsAccumulator()
        val failure = AcceptFailure(
            reason = "AcceptButtonNotFound",
            ridePrice = 25.50,
            pickupTime = "Tomorrow \u00b7 6:05AM",
            timestamp = "2026-02-15T10:30:00Z"
        )

        pendingStats.addAcceptFailure(failure)

        assertEquals(1, pendingStats.acceptFailures.size)
        assertEquals("AcceptButtonNotFound", pendingStats.acceptFailures[0].reason)
        assertEquals(25.50, pendingStats.acceptFailures[0].ridePrice, 0.001)
    }

    @Test
    fun `accept failures included in ping stats snapshot`() {
        val pendingStats = PendingStatsAccumulator()
        pendingStats.incrementCycles()
        pendingStats.addRidesFound(3)
        pendingStats.addAcceptFailure(
            AcceptFailure("ClickFailed", 20.0, "Tomorrow \u00b7 7:00AM", "2026-02-15T11:00:00Z")
        )
        pendingStats.addAcceptFailure(
            AcceptFailure("RideNotFound", 30.0, "Tomorrow \u00b7 8:00AM", "2026-02-15T11:05:00Z")
        )

        val snapshot = pendingStats.toPingStats()

        assertEquals(1, snapshot.cyclesSinceLastPing)
        assertEquals(3, snapshot.ridesFound)
        assertEquals(2, snapshot.acceptFailures.size)
        assertEquals("ClickFailed", snapshot.acceptFailures[0].reason)
        assertEquals("RideNotFound", snapshot.acceptFailures[1].reason)
    }

    @Test
    fun `accept failures reported in ping request body via MockWebServer`() = runTest {
        enableAccessibilityService()
        val service = Robolectric.setupService(MonitoringForegroundService::class.java)
        service.serverClient = serverClient
        service.deviceTokenManager = mockDeviceTokenManager

        val mockQueue = mockk<PendingRideQueue>(relaxed = true)
        every { mockQueue.dequeueAll() } returns emptyList()
        service.pendingRideQueue = mockQueue

        // Add accept failure to stats before ping
        service.pendingStats.addAcceptFailure(
            AcceptFailure(
                reason = "AcceptButtonNotFound",
                ridePrice = 25.50,
                pickupTime = "Tomorrow \u00b7 6:05AM",
                timestamp = "2026-02-15T10:30:00Z"
            )
        )

        // Enqueue ping success response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":30,"filters":{"min_price":20.0}}""")
        )

        service.monitoringCycleWithPing()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Ping request should be sent", request)
        assertEquals("/api/v1/ping", request!!.path)

        val body = parseRequestBody(request)
        val stats = body["stats"]?.jsonObject
        assertNotNull("stats should be present", stats)

        val failures = stats!!["accept_failures"]?.jsonArray
        assertNotNull("accept_failures should be present in stats", failures)
        assertEquals(1, failures!!.size)

        val failure = failures[0].jsonObject
        assertEquals("AcceptButtonNotFound", failure["reason"]?.jsonPrimitive?.content)
        assertEquals(25.50, failure["ride_price"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals("Tomorrow \u00b7 6:05AM", failure["pickup_time"]?.jsonPrimitive?.content)
        assertEquals("2026-02-15T10:30:00Z", failure["timestamp"]?.jsonPrimitive?.content)

        disableAccessibilityService()
    }

    @Test
    fun `stats reset after successful ping`() = runTest {
        enableAccessibilityService()
        val service = Robolectric.setupService(MonitoringForegroundService::class.java)
        service.serverClient = serverClient
        service.deviceTokenManager = mockDeviceTokenManager

        val mockQueue = mockk<PendingRideQueue>(relaxed = true)
        every { mockQueue.dequeueAll() } returns emptyList()
        service.pendingRideQueue = mockQueue

        // Accumulate stats
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(5)
        service.pendingStats.addAcceptFailure(
            AcceptFailure("TestReason", 20.0, "Tomorrow \u00b7 7:00AM", "2026-02-15T12:00:00Z")
        )
        val oldBatchId = service.pendingStats.batchId

        // Successful ping
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"search":false,"interval_seconds":30,"filters":{"min_price":20.0}}""")
        )

        service.monitoringCycleWithPing()

        // Stats should be reset
        assertEquals(0, service.pendingStats.cyclesSinceLastPing)
        assertEquals(0, service.pendingStats.ridesFound)
        assertTrue(service.pendingStats.acceptFailures.isEmpty())
        assertNotEquals("batchId should be regenerated", oldBatchId, service.pendingStats.batchId)

        disableAccessibilityService()
    }

    // ==================== Helper Methods ====================

    private fun setupSuccessfulUiFlow() {
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns true
        // No confirmation dialog, verification returns null
        every { mockAccessibilityService.findLyftNodeByText(any(), exactMatch = any()) } returns null
    }

    private fun enqueueRideReportSuccess() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"ride_id":"server-ride-id-123"}""")
        )
    }

    private fun parseRequestBody(request: RecordedRequest): JsonObject {
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
