package com.skeddy.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.skeddy.R
import com.skeddy.data.BlacklistRepository
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.PairingErrorReason
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.PairingRequest
import com.skeddy.network.models.PairingResponse
import com.skeddy.service.PendingRideQueue
import android.os.Build
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf

/**
 * Tests for PairingActivity pairing flow (subtasks 11.3, 11.4):
 * - confirmPairing called with correct parameters
 * - Token saved on success
 * - Re-pairing: blacklist + pending queue cleared on success
 * - First pairing: clearAll NOT called
 * - Error mapping to localized strings
 * - Re-pairing error: no cleanup occurs
 * - Integration tests via onPairClicked (success + error paths)
 */
@RunWith(RobolectricTestRunner::class)
class PairingActivityPairingFlowTest {

    private lateinit var activity: PairingActivity
    private lateinit var mockServerClient: SkeddyServerClient
    private lateinit var mockDeviceTokenManager: DeviceTokenManager
    private lateinit var mockBlacklistRepository: BlacklistRepository
    private lateinit var mockPendingRideQueue: PendingRideQueue

    @Before
    fun setUp() {
        mockServerClient = mockk(relaxed = true)
        mockDeviceTokenManager = mockk(relaxed = true)
        mockBlacklistRepository = mockk(relaxed = true)
        mockPendingRideQueue = mockk(relaxed = true)

        activity = Robolectric.buildActivity(PairingActivity::class.java)
            .create().resume().get()

        // Inject mock dependencies (overrides initDependencies)
        activity.serverClient = mockServerClient
        activity.deviceTokenManager = mockDeviceTokenManager
        activity.blacklistRepository = mockBlacklistRepository
        activity.pendingRideQueue = mockPendingRideQueue
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== handlePairingResult — Success: Token Saved ====================

    @Test
    fun `success saves device token`() = runBlocking {
        val response = PairingResponse(deviceToken = "test-token-abc", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("test-token-abc") }
    }

    @Test
    fun `success navigates to MainActivity`() = runBlocking {
        val response = PairingResponse(deviceToken = "test-token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `success sets clear task flags on navigation intent`() = runBlocking {
        val response = PairingResponse(deviceToken = "test-token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        val nextIntent = shadowOf(activity).nextStartedActivity
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }

    @Test
    fun `success finishes PairingActivity`() = runBlocking {
        val response = PairingResponse(deviceToken = "test-token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        assertTrue(activity.isFinishing)
    }

    // ==================== handlePairingResult — Re-pairing Cleanup ====================

    @Test
    fun `re-pairing clears blacklist on success`() = runBlocking {
        val response = PairingResponse(deviceToken = "new-token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = true)

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-pairing clears pending ride queue on success`() = runBlocking {
        val response = PairingResponse(deviceToken = "new-token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = true)

        verify(exactly = 1) { mockPendingRideQueue.clear() }
    }

    @Test
    fun `re-pairing saves new token after cleanup`() = runBlocking {
        val response = PairingResponse(deviceToken = "new-token-xyz", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = true)

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token-xyz") }
    }

    // ==================== handlePairingResult — First Pairing (No Cleanup) ====================

    @Test
    fun `first pairing does NOT clear blacklist`() = runBlocking {
        val response = PairingResponse(deviceToken = "token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `first pairing does NOT clear pending ride queue`() = runBlocking {
        val response = PairingResponse(deviceToken = "token", userId = "user-123")

        activity.handlePairingResult(ApiResult.Success(response), wasPaired = false)

        verify(exactly = 0) { mockPendingRideQueue.clear() }
    }

    // ==================== handlePairingResult — Pairing Errors ====================

    @Test
    fun `INVALID_OR_EXPIRED shows pairing_error_invalid`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED),
            wasPaired = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_invalid),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `ALREADY_USED shows pairing_error_already_used`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.PairingError(PairingErrorReason.ALREADY_USED),
            wasPaired = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_already_used),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    // ==================== handlePairingResult — Network Error ====================

    @Test
    fun `NetworkError shows pairing_error_network`() = runBlocking {
        activity.handlePairingResult(ApiResult.NetworkError, wasPaired = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_network),
            errorText.text.toString()
        )
    }

    // ==================== handlePairingResult — Fallback Errors ====================

    @Test
    fun `ServerError shows pairing_error_unknown`() = runBlocking {
        activity.handlePairingResult(ApiResult.ServerError, wasPaired = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `ServiceUnavailable shows pairing_error_unknown`() = runBlocking {
        activity.handlePairingResult(ApiResult.ServiceUnavailable, wasPaired = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `ValidationError shows pairing_error_unknown`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.ValidationError("Invalid timezone"),
            wasPaired = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `RateLimited shows pairing_error_unknown`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.RateLimited(60),
            wasPaired = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `Unauthorized shows pairing_error_unknown`() = runBlocking {
        activity.handlePairingResult(ApiResult.Unauthorized, wasPaired = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_unknown),
            errorText.text.toString()
        )
    }

    // ==================== handlePairingResult — No Token Saved on Error ====================

    @Test
    fun `pairing error does NOT save token`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED),
            wasPaired = false
        )

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    @Test
    fun `network error does NOT save token`() = runBlocking {
        activity.handlePairingResult(ApiResult.NetworkError, wasPaired = false)

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onPairClicked — Request Parameters ====================

    @Test
    fun `onPairClicked sends correct code to confirmPairing`() {
        val capturedRequest = slot<PairingRequest>()
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id"
        coEvery { mockServerClient.confirmPairing(capture(capturedRequest)) } returns
            ApiResult.Success(PairingResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("482917")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(capturedRequest.isCaptured)
        assertEquals("482917", capturedRequest.captured.code)
    }

    @Test
    fun `onPairClicked sends deviceId from DeviceTokenManager`() {
        val capturedRequest = slot<PairingRequest>()
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "my-android-device-id"
        coEvery { mockServerClient.confirmPairing(capture(capturedRequest)) } returns
            ApiResult.Success(PairingResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        assertEquals("my-android-device-id", capturedRequest.captured.deviceId)
    }

    @Test
    fun `onPairClicked sends device model`() {
        val capturedRequest = slot<PairingRequest>()
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(capture(capturedRequest)) } returns
            ApiResult.Success(PairingResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        val expected = "${Build.MANUFACTURER} ${Build.MODEL}"
        assertEquals(expected, capturedRequest.captured.deviceModel)
    }

    @Test
    fun `onPairClicked sends non-empty timezone`() {
        val capturedRequest = slot<PairingRequest>()
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(capture(capturedRequest)) } returns
            ApiResult.Success(PairingResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(capturedRequest.captured.timezone.isNotEmpty())
    }

    // ==================== onPairClicked — Checks isPaired ====================

    @Test
    fun `onPairClicked checks isPaired before API call`() {
        every { mockDeviceTokenManager.isPaired() } returns false
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.Success(PairingResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        verify(exactly = 1) { mockDeviceTokenManager.isPaired() }
    }

    // ==================== onPairClicked — Shows Loading ====================

    @Test
    fun `onPairClicked shows loading state before API call`() {
        // Suspend the coroutine indefinitely so loading state remains observable
        coEvery { mockServerClient.confirmPairing(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        every { mockDeviceTokenManager.isPaired() } returns false

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()

        // Loading state is set synchronously before the coroutine suspends
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertFalse(activity.findViewById<View>(R.id.codeInput).isEnabled)

        // Cancel the suspended coroutine to avoid leak
        activity.pairingJob?.cancel()
    }

    // ==================== onPairClicked — Does Nothing with Invalid Code ====================

    @Test
    fun `onPairClicked does nothing when code is invalid`() {
        activity.findViewById<EditText>(R.id.codeInput).setText("123")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockServerClient.confirmPairing(any()) }
    }

    @Test
    fun `onPairClicked does nothing when code input is empty`() {
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockServerClient.confirmPairing(any()) }
    }

    // ==================== handlePairingResult — Re-pairing Error (No Cleanup) ====================

    @Test
    fun `re-pairing error does NOT clear blacklist`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED),
            wasPaired = true
        )

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-pairing error does NOT clear pending ride queue`() = runBlocking {
        activity.handlePairingResult(
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED),
            wasPaired = true
        )

        verify(exactly = 0) { mockPendingRideQueue.clear() }
    }

    @Test
    fun `re-pairing network error does NOT clear blacklist`() = runBlocking {
        activity.handlePairingResult(ApiResult.NetworkError, wasPaired = true)

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-pairing network error does NOT save token`() = runBlocking {
        activity.handlePairingResult(ApiResult.NetworkError, wasPaired = true)

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onPairClicked — Error Flow Integration ====================

    @Test
    fun `onPairClicked shows error when API returns PairingError`() {
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_invalid),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `onPairClicked shows error when API returns NetworkError`() {
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns ApiResult.NetworkError

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_network),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `onPairClicked restores UI after error`() {
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.PairingError(PairingErrorReason.ALREADY_USED)

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(activity.findViewById<View>(R.id.pairButton).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertTrue(activity.findViewById<View>(R.id.codeInput).isEnabled)
    }

    @Test
    fun `onPairClicked with re-pairing error does NOT clear data`() {
        every { mockDeviceTokenManager.isPaired() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onPairClicked — Full Re-pairing Flow ====================

    @Test
    fun `onPairClicked with re-pairing clears data and saves new token`() {
        every { mockDeviceTokenManager.isPaired() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.Success(PairingResponse(deviceToken = "new-token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
        verify(exactly = 1) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token") }
    }

    // ==================== onPairClicked — Full First Pairing Flow ====================

    @Test
    fun `onPairClicked with first pairing saves token without clearing data`() {
        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.confirmPairing(any()) } returns
            ApiResult.Success(PairingResponse(deviceToken = "first-token", userId = "user"))

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("first-token") }
    }
}
