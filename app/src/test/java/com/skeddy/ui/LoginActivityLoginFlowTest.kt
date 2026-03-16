package com.skeddy.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.skeddy.R
import com.skeddy.data.BlacklistRepository
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.LoginErrorReason
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.SearchLoginRequest
import com.skeddy.network.models.SearchLoginResponse
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
 * Tests for LoginActivity login flow:
 * - searchLogin called with correct parameters
 * - Token saved on success
 * - Re-login: blacklist + pending queue cleared on success
 * - First login: clearAll NOT called
 * - Error mapping to localized strings
 * - Re-login error: no cleanup occurs
 * - Integration tests via onLoginClicked (success + error paths)
 */
@RunWith(RobolectricTestRunner::class)
class LoginActivityLoginFlowTest {

    private lateinit var activity: LoginActivity
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

        activity = Robolectric.buildActivity(LoginActivity::class.java)
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

    // ==================== handleLoginResult — Success: Token Saved ====================

    @Test
    fun `success saves device token`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "test-token-abc", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("test-token-abc") }
    }

    @Test
    fun `success navigates to MainActivity`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "test-token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `success sets clear task flags on navigation intent`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "test-token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        val nextIntent = shadowOf(activity).nextStartedActivity
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }

    @Test
    fun `success finishes LoginActivity`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "test-token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        assertTrue(activity.isFinishing)
    }

    // ==================== handleLoginResult — Re-login Cleanup ====================

    @Test
    fun `re-login clears blacklist on success`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "new-token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = true)

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-login clears pending ride queue on success`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "new-token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = true)

        verify(exactly = 1) { mockPendingRideQueue.clear() }
    }

    @Test
    fun `re-login saves new token after cleanup`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "new-token-xyz", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = true)

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token-xyz") }
    }

    // ==================== handleLoginResult — First Login (No Cleanup) ====================

    @Test
    fun `first login does NOT clear blacklist`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `first login does NOT clear pending ride queue`() = runBlocking {
        val response = SearchLoginResponse(deviceToken = "token", userId = "user-123")

        activity.handleLoginResult(ApiResult.Success(response), wasLoggedIn = false)

        verify(exactly = 0) { mockPendingRideQueue.clear() }
    }

    // ==================== handleLoginResult — Login Errors ====================

    @Test
    fun `INVALID_CREDENTIALS shows login_error_invalid_credentials`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS),
            wasLoggedIn = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_invalid_credentials),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    // ==================== handleLoginResult — Network Error ====================

    @Test
    fun `NetworkError shows login_error_network`() = runBlocking {
        activity.handleLoginResult(ApiResult.NetworkError, wasLoggedIn = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_network),
            errorText.text.toString()
        )
    }

    // ==================== handleLoginResult — Fallback Errors ====================

    @Test
    fun `ServerError shows login_error_unknown`() = runBlocking {
        activity.handleLoginResult(ApiResult.ServerError, wasLoggedIn = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `ServiceUnavailable shows login_error_unknown`() = runBlocking {
        activity.handleLoginResult(ApiResult.ServiceUnavailable, wasLoggedIn = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `ValidationError shows login_error_unknown`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.ValidationError("Invalid timezone"),
            wasLoggedIn = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `RateLimited shows login_error_unknown`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.RateLimited(60),
            wasLoggedIn = false
        )

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_unknown),
            errorText.text.toString()
        )
    }

    @Test
    fun `Unauthorized shows login_error_unknown`() = runBlocking {
        activity.handleLoginResult(ApiResult.Unauthorized, wasLoggedIn = false)

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_unknown),
            errorText.text.toString()
        )
    }

    // ==================== handleLoginResult — No Token Saved on Error ====================

    @Test
    fun `login error does NOT save token`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS),
            wasLoggedIn = false
        )

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    @Test
    fun `network error does NOT save token`() = runBlocking {
        activity.handleLoginResult(ApiResult.NetworkError, wasLoggedIn = false)

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onLoginClicked — Request Parameters ====================

    @Test
    fun `onLoginClicked sends correct email and password to searchLogin`() {
        val capturedRequest = slot<SearchLoginRequest>()
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id"
        coEvery { mockServerClient.searchLogin(capture(capturedRequest)) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("test@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("mypassword")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(capturedRequest.isCaptured)
        assertEquals("test@example.com", capturedRequest.captured.email)
        assertEquals("mypassword", capturedRequest.captured.password)
    }

    @Test
    fun `onLoginClicked sends deviceId from DeviceTokenManager`() {
        val capturedRequest = slot<SearchLoginRequest>()
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "my-android-device-id"
        coEvery { mockServerClient.searchLogin(capture(capturedRequest)) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        assertEquals("my-android-device-id", capturedRequest.captured.deviceId)
    }

    @Test
    fun `onLoginClicked sends device model`() {
        val capturedRequest = slot<SearchLoginRequest>()
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(capture(capturedRequest)) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        val expected = "${Build.MANUFACTURER} ${Build.MODEL}"
        assertEquals(expected, capturedRequest.captured.deviceModel)
    }

    @Test
    fun `onLoginClicked sends non-empty timezone`() {
        val capturedRequest = slot<SearchLoginRequest>()
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(capture(capturedRequest)) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(capturedRequest.captured.timezone.isNotEmpty())
    }

    // ==================== onLoginClicked — Checks isLoggedIn ====================

    @Test
    fun `onLoginClicked checks isLoggedIn before API call`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        verify(exactly = 1) { mockDeviceTokenManager.isLoggedIn() }
    }

    // ==================== onLoginClicked — Shows Loading ====================

    @Test
    fun `onLoginClicked shows loading state before API call`() {
        coEvery { mockServerClient.searchLogin(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        every { mockDeviceTokenManager.isLoggedIn() } returns false

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()

        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertFalse(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.passwordInput).isEnabled)

        activity.loginJob?.cancel()
    }

    // ==================== onLoginClicked — Does Nothing with Invalid Form ====================

    @Test
    fun `onLoginClicked does nothing when form is invalid`() {
        activity.findViewById<EditText>(R.id.emailInput).setText("not-email")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockServerClient.searchLogin(any()) }
    }

    @Test
    fun `onLoginClicked does nothing when email input is empty`() {
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockServerClient.searchLogin(any()) }
    }

    // ==================== handleLoginResult — Re-login Error (No Cleanup) ====================

    @Test
    fun `re-login error does NOT clear blacklist`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS),
            wasLoggedIn = true
        )

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-login error does NOT clear pending ride queue`() = runBlocking {
        activity.handleLoginResult(
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS),
            wasLoggedIn = true
        )

        verify(exactly = 0) { mockPendingRideQueue.clear() }
    }

    @Test
    fun `re-login network error does NOT clear blacklist`() = runBlocking {
        activity.handleLoginResult(ApiResult.NetworkError, wasLoggedIn = true)

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
    }

    @Test
    fun `re-login network error does NOT save token`() = runBlocking {
        activity.handleLoginResult(ApiResult.NetworkError, wasLoggedIn = true)

        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onLoginClicked — Error Flow Integration ====================

    @Test
    fun `onLoginClicked shows error when API returns LoginError`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS)

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("wrongpass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_invalid_credentials),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `onLoginClicked shows error when API returns NetworkError`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns ApiResult.NetworkError

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_network),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `onLoginClicked restores UI after error`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS)

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("wrongpass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        assertTrue(activity.findViewById<View>(R.id.loginButton).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertTrue(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.passwordInput).isEnabled)
    }

    @Test
    fun `onLoginClicked with re-login error does NOT clear data`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.LoginError(LoginErrorReason.INVALID_CREDENTIALS)

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("wrongpass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== onLoginClicked — Full Re-login Flow ====================

    @Test
    fun `onLoginClicked with re-login clears data and saves new token`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "new-token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
        verify(exactly = 1) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token") }
    }

    // ==================== onLoginClicked — Full First Login Flow ====================

    @Test
    fun `onLoginClicked with first login saves token without clearing data`() {
        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"
        coEvery { mockServerClient.searchLogin(any()) } returns
            ApiResult.Success(SearchLoginResponse(deviceToken = "first-token", userId = "user"))

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()
        shadowOf(activity.mainLooper).idle()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("first-token") }
    }
}
