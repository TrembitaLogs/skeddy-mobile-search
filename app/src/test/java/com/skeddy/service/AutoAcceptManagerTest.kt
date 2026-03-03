package com.skeddy.service

import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftNavigator
import com.skeddy.navigation.LyftScreen
import com.skeddy.navigation.LyftUIElements
import com.skeddy.network.ApiResult
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.RideReportRequest
import com.skeddy.network.models.RideReportResponse
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-тести для AutoAcceptManager.
 *
 * Тестова стратегія для задачі 13.3:
 * 1. Тестувати кожен failure case (ClickFailed, RideNotFound, AcceptButtonNotFound)
 * 2. Тестувати success flow з двоетапною логікою Reserve
 * 3. Перевірити що navigateBack викликається при помилках
 * 4. Тестувати handleConfirmationDialog()
 * 5. Тестувати verifyReservationSuccess()
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoAcceptManagerTest {

    private lateinit var mockAccessibilityService: SkeddyAccessibilityService
    private lateinit var mockNavigator: LyftNavigator
    private lateinit var mockServerClient: SkeddyServerClient
    private lateinit var mockBlacklistRepository: BlacklistRepository
    private lateinit var mockPendingQueue: PendingRideQueue
    private lateinit var autoAcceptManager: AutoAcceptManager
    private lateinit var mockRideCard: AccessibilityNodeInfo
    private lateinit var testRide: ScheduledRide

    @Before
    fun setUp() {
        mockAccessibilityService = mockk(relaxed = true)
        mockNavigator = mockk(relaxed = true)
        mockServerClient = mockk(relaxed = true)
        mockBlacklistRepository = mockk(relaxed = true)
        mockPendingQueue = mockk(relaxed = true)

        // Default: server reporting succeeds (Task 15.3)
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "test-ride-id")
        )

        autoAcceptManager = AutoAcceptManager(
            mockAccessibilityService, mockNavigator,
            mockServerClient, mockBlacklistRepository, mockPendingQueue
        )

        mockRideCard = mockk(relaxed = true)
        testRide = ScheduledRide(
            id = "test_ride_123",
            price = 25.50,
            bonus = 5.0,
            pickupTime = "10:30 AM",
            pickupLocation = "123 Main St",
            dropoffLocation = "456 Oak Ave",
            duration = "25 min",
            distance = "12 mi",
            riderName = "John D.",
            riderRating = 4.8,
            isVerified = true
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== autoAcceptRide() Success Tests ====================

    @Test
    fun `autoAcceptRide returns Success when all steps succeed`() = runTest {
        // Given: all steps succeed
        setupSuccessfulFlow()

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success", result is AutoAcceptResult.Success)
        assertEquals(testRide, (result as AutoAcceptResult.Success).ride)
        coVerify { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide calls clickOnRideCard with correct parameter`() = runTest {
        // Given
        setupSuccessfulFlow()

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        verify { mockNavigator.clickOnRideCard(mockRideCard) }
    }

    @Test
    fun `autoAcceptRide waits for RIDE_DETAILS screen after clicking card`() = runTest {
        // Given
        setupSuccessfulFlow()

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        coVerify {
            mockNavigator.waitForScreen(
                LyftScreen.RIDE_DETAILS,
                LyftUIElements.SCREEN_LOAD_TIMEOUT
            )
        }
    }

    @Test
    fun `autoAcceptRide clicks Accept button after detecting it`() = runTest {
        // Given
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        setupSuccessfulFlow(acceptButton = mockAcceptButton)

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        verify { mockNavigator.detectAcceptButton() }
        verify { mockAccessibilityService.performClickOnNode(mockAcceptButton) }
    }

    // ==================== autoAcceptRide() Failure Tests ====================

    @Test
    fun `autoAcceptRide returns ClickFailed when card click fails`() = runTest {
        // Given: card click fails
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be ClickFailed", result is AutoAcceptResult.ClickFailed)
        assertEquals("Failed to click on ride card", (result as AutoAcceptResult.ClickFailed).reason)
        // Should NOT navigate back (we're still on SCHEDULED_RIDES)
        coVerify(exactly = 0) { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide returns RideNotFound when RIDE_DETAILS screen doesn't appear`() = runTest {
        // Given: card click succeeds but screen doesn't appear
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery {
            mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any())
        } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be RideNotFound", result is AutoAcceptResult.RideNotFound)
        assertEquals("Ride details screen didn't appear", (result as AutoAcceptResult.RideNotFound).reason)
        coVerify { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide returns AcceptButtonNotFound when button not visible`() = runTest {
        // Given: screen appears but no accept button
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns null

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be AcceptButtonNotFound", result is AutoAcceptResult.AcceptButtonNotFound)
        assertTrue((result as AutoAcceptResult.AcceptButtonNotFound).reason.contains("not visible"))
        coVerify { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide returns ClickFailed when Accept button click fails`() = runTest {
        // Given: accept button found but click fails
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be ClickFailed", result is AutoAcceptResult.ClickFailed)
        assertEquals("Failed to click Accept/Reserve button", (result as AutoAcceptResult.ClickFailed).reason)
        coVerify { mockNavigator.safeNavigateBack() }
    }

    // ==================== Confirmation Dialog Tests ====================

    @Test
    fun `autoAcceptRide handles confirmation dialog with Reserve button`() = runTest {
        // Given: two-step Reserve flow
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        val mockConfirmButton = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isClickable } returns true
        }

        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns true

        // After first click, confirmation dialog appears with another Reserve button
        every {
            mockAccessibilityService.findLyftNodeByText("Reserve", exactMatch = true)
        } returns mockConfirmButton
        every { mockAccessibilityService.performClickOnNode(mockConfirmButton) } returns true

        // Verification succeeds
        every {
            mockAccessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false)
        } returns mockk(relaxed = true)

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success", result is AutoAcceptResult.Success)
    }

    @Test
    fun `autoAcceptRide handles confirmation dialog with Confirm button`() = runTest {
        // Given
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        val mockConfirmButton = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isClickable } returns true
        }

        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns true

        // Reserve not found, Confirm found
        every {
            mockAccessibilityService.findLyftNodeByText("Reserve", exactMatch = true)
        } returns null
        every {
            mockAccessibilityService.findLyftNodeByText("Confirm", exactMatch = false)
        } returns mockConfirmButton
        every { mockAccessibilityService.performClickOnNode(mockConfirmButton) } returns true

        // Verification succeeds
        every {
            mockAccessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false)
        } returns mockk(relaxed = true)

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success", result is AutoAcceptResult.Success)
    }

    @Test
    fun `autoAcceptRide succeeds even without confirmation dialog (single-step accept)`() = runTest {
        // Given: single-step accept (no confirmation dialog)
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns true

        // No confirmation buttons found
        every { mockAccessibilityService.findLyftNodeByText(any(), exactMatch = any()) } returns null

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success even without confirmation dialog", result is AutoAcceptResult.Success)
    }

    // ==================== Verification Tests ====================

    @Test
    fun `autoAcceptRide verifies success via Cancel ride button`() = runTest {
        // Given
        setupSuccessfulFlow()
        every {
            mockAccessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false)
        } returns mockk(relaxed = true)

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success", result is AutoAcceptResult.Success)
        verify { mockAccessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false) }
    }

    @Test
    fun `autoAcceptRide verifies success via Cancel scheduled ride button`() = runTest {
        // Given
        setupSuccessfulFlow()
        every {
            mockAccessibilityService.findLyftNodeByText("Cancel ride", exactMatch = false)
        } returns null
        every {
            mockAccessibilityService.findLyftNodeByText("Cancel scheduled ride", exactMatch = false)
        } returns mockk(relaxed = true)

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue("Result should be Success", result is AutoAcceptResult.Success)
    }

    @Test
    fun `autoAcceptRide succeeds even when verification fails`() = runTest {
        // Given: verification indicators not found (but overall flow succeeded)
        setupSuccessfulFlow()
        // All verification text searches return null
        every { mockAccessibilityService.findLyftNodeByText(any(), exactMatch = any()) } returns null

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        // Should still return Success because all clicks succeeded
        assertTrue("Result should be Success even without verification", result is AutoAcceptResult.Success)
    }

    // ==================== Sealed Class Tests ====================

    @Test
    fun `AutoAcceptResult Success contains ride data`() {
        val result = AutoAcceptResult.Success(testRide)
        assertEquals(testRide, result.ride)
        assertEquals("test_ride_123", result.ride.id)
        assertEquals(25.50, result.ride.price, 0.01)
    }

    @Test
    fun `AutoAcceptResult RideNotFound contains reason`() {
        val result = AutoAcceptResult.RideNotFound("Screen timeout")
        assertEquals("Screen timeout", result.reason)
    }

    @Test
    fun `AutoAcceptResult AcceptButtonNotFound contains reason`() {
        val result = AutoAcceptResult.AcceptButtonNotFound("Button not visible")
        assertEquals("Button not visible", result.reason)
    }

    @Test
    fun `AutoAcceptResult ClickFailed contains reason`() {
        val result = AutoAcceptResult.ClickFailed("Node not clickable")
        assertEquals("Node not clickable", result.reason)
    }

    @Test
    fun `AutoAcceptResult ConfirmationTimeout contains reason`() {
        val result = AutoAcceptResult.ConfirmationTimeout("Dialog didn't appear")
        assertEquals("Dialog didn't appear", result.reason)
    }

    // ==================== Factory Method Tests ====================

    @Test
    fun `create returns null when AccessibilityService not available`() {
        // Given: AccessibilityService instance is null
        mockkObject(SkeddyAccessibilityService.Companion)
        every { SkeddyAccessibilityService.getInstance() } returns null

        // When
        val result = AutoAcceptManager.create(
            mockServerClient, mockBlacklistRepository, mockPendingQueue
        )

        // Then
        assertNull("create() should return null when service unavailable", result)

        unmockkObject(SkeddyAccessibilityService.Companion)
    }

    @Test
    fun `create returns AutoAcceptManager when service available`() {
        // Given: AccessibilityService is available
        mockkObject(SkeddyAccessibilityService.Companion)
        every { SkeddyAccessibilityService.getInstance() } returns mockAccessibilityService

        // When
        val result = AutoAcceptManager.create(
            mockServerClient, mockBlacklistRepository, mockPendingQueue
        )

        // Then
        assertNotNull("create() should return AutoAcceptManager", result)

        unmockkObject(SkeddyAccessibilityService.Companion)
    }

    // ==================== Navigation Back Tests ====================

    @Test
    fun `autoAcceptRide always calls safeNavigateBack on success`() = runTest {
        // Given
        setupSuccessfulFlow()

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        coVerify(exactly = 1) { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide calls safeNavigateBack when RideNotFound`() = runTest {
        // Given
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns false

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        coVerify(exactly = 1) { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide calls safeNavigateBack when AcceptButtonNotFound`() = runTest {
        // Given
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns null

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        coVerify(exactly = 1) { mockNavigator.safeNavigateBack() }
    }

    @Test
    fun `autoAcceptRide calls safeNavigateBack when Accept click fails`() = runTest {
        // Given
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns false

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        coVerify(exactly = 1) { mockNavigator.safeNavigateBack() }
    }

    // ==================== Blacklist Integration Tests (Task 15.2) ====================

    @Test
    fun `autoAcceptRide adds ride to blacklist on Success`() = runTest {
        // Given
        setupSuccessfulFlow()

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(result is AutoAcceptResult.Success)
        coVerify(exactly = 1) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `autoAcceptRide does NOT blacklist on ClickFailed (card click)`() = runTest {
        // Given
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(result is AutoAcceptResult.ClickFailed)
        coVerify(exactly = 0) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `autoAcceptRide does NOT blacklist on RideNotFound`() = runTest {
        // Given
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(result is AutoAcceptResult.RideNotFound)
        coVerify(exactly = 0) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `autoAcceptRide does NOT blacklist on AcceptButtonNotFound`() = runTest {
        // Given
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns null

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(result is AutoAcceptResult.AcceptButtonNotFound)
        coVerify(exactly = 0) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `autoAcceptRide does NOT blacklist on ClickFailed (accept button)`() = runTest {
        // Given
        val mockAcceptButton = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery { mockNavigator.waitForScreen(any(), any()) } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptButton
        every { mockAccessibilityService.performClickOnNode(mockAcceptButton) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(result is AutoAcceptResult.ClickFailed)
        coVerify(exactly = 0) { mockBlacklistRepository.addToBlacklist(any()) }
    }

    @Test
    fun `autoAcceptRide returns Success even when blacklist insert throws`() = runTest {
        // Given
        setupSuccessfulFlow()
        coEvery { mockBlacklistRepository.addToBlacklist(any()) } throws RuntimeException("DB error")

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: result is still Success — blacklist failure must not break accept flow
        assertTrue(result is AutoAcceptResult.Success)
        assertEquals(testRide, (result as AutoAcceptResult.Success).ride)
    }

    // ==================== Server Reporting Tests (Task 15.3) ====================

    @Test
    fun `autoAcceptRide removes report from queue on successful server report`() = runTest {
        // Given
        setupSuccessfulFlow()
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "server-ride-id")
        )

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: pessimistic — enqueue first, remove on success
        assertTrue(result is AutoAcceptResult.Success)
        verify(exactly = 1) { mockPendingQueue.enqueue(any()) }
        verify(exactly = 1) { mockPendingQueue.remove(any()) }
    }

    @Test
    fun `autoAcceptRide keeps report in queue on network error`() = runTest {
        // Given
        setupSuccessfulFlow()
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.NetworkError

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: enqueued but NOT removed
        assertTrue(result is AutoAcceptResult.Success)
        verify(exactly = 1) { mockPendingQueue.enqueue(any()) }
        verify(exactly = 0) { mockPendingQueue.remove(any()) }
    }

    @Test
    fun `autoAcceptRide keeps report in queue on server error`() = runTest {
        // Given
        setupSuccessfulFlow()
        coEvery { mockServerClient.reportRide(any()) } returns ApiResult.ServerError

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: enqueued but NOT removed
        assertTrue(result is AutoAcceptResult.Success)
        verify(exactly = 1) { mockPendingQueue.enqueue(any()) }
        verify(exactly = 0) { mockPendingQueue.remove(any()) }
    }

    @Test
    fun `autoAcceptRide generates unique idempotency key for each call`() = runTest {
        // Given
        setupSuccessfulFlow()
        val capturedRequests = mutableListOf<RideReportRequest>()
        coEvery { mockServerClient.reportRide(capture(capturedRequests)) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "id")
        )

        // When: two separate accept calls
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: each call generates a different idempotency key
        assertEquals(2, capturedRequests.size)
        assertNotEquals(capturedRequests[0].idempotencyKey, capturedRequests[1].idempotencyKey)
    }

    @Test
    fun `autoAcceptRide enqueues BEFORE calling reportRide`() = runTest {
        // Given
        setupSuccessfulFlow()

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: blacklist → enqueue → reportRide (in order)
        coVerifyOrder {
            mockBlacklistRepository.addToBlacklist(any())
            mockPendingQueue.enqueue(any())
            mockServerClient.reportRide(any())
        }
    }

    @Test
    fun `autoAcceptRide sends report with ACCEPTED event type and correct ride data`() = runTest {
        // Given
        setupSuccessfulFlow()
        val capturedRequest = slot<RideReportRequest>()
        coEvery { mockServerClient.reportRide(capture(capturedRequest)) } returns ApiResult.Success(
            RideReportResponse(ok = true, rideId = "id")
        )

        // When
        autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then
        assertTrue(capturedRequest.isCaptured)
        assertEquals("ACCEPTED", capturedRequest.captured.eventType)
        assertEquals(testRide.price, capturedRequest.captured.rideData.price, 0.01)
        assertEquals(testRide.pickupTime, capturedRequest.captured.rideData.pickupTime)
        assertEquals(testRide.pickupLocation, capturedRequest.captured.rideData.pickupLocation)
        assertEquals(testRide.dropoffLocation, capturedRequest.captured.rideData.dropoffLocation)
        assertEquals(testRide.duration, capturedRequest.captured.rideData.duration)
        assertEquals(testRide.distance, capturedRequest.captured.rideData.distance)
        assertEquals(testRide.riderName, capturedRequest.captured.rideData.riderName)
    }

    @Test
    fun `autoAcceptRide does NOT report or enqueue on failure`() = runTest {
        // Given: card click fails → early return
        every { mockNavigator.clickOnRideCard(mockRideCard) } returns false

        // When
        val result = autoAcceptManager.autoAcceptRide(mockRideCard, testRide)

        // Then: no server reporting, no queue interaction
        assertTrue(result is AutoAcceptResult.ClickFailed)
        coVerify(exactly = 0) { mockServerClient.reportRide(any()) }
        verify(exactly = 0) { mockPendingQueue.enqueue(any()) }
    }

    // ==================== Helper Methods ====================

    private fun setupSuccessfulFlow(acceptButton: AccessibilityNodeInfo? = null) {
        val mockAcceptBtn = acceptButton ?: mockk<AccessibilityNodeInfo>(relaxed = true)

        every { mockNavigator.clickOnRideCard(mockRideCard) } returns true
        coEvery {
            mockNavigator.waitForScreen(LyftScreen.RIDE_DETAILS, any())
        } returns true
        every { mockNavigator.detectAcceptButton() } returns mockAcceptBtn
        every { mockAccessibilityService.performClickOnNode(mockAcceptBtn) } returns true

        // Default: no confirmation dialog, verification returns null
        every { mockAccessibilityService.findLyftNodeByText(any(), exactMatch = any()) } returns null
    }
}
