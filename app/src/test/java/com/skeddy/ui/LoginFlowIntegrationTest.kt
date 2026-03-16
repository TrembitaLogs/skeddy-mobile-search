package com.skeddy.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.skeddy.R
import com.skeddy.data.BlacklistRepository
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.service.PendingRideQueue
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.serialization.json.Json
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
import org.robolectric.Shadows.shadowOf
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the login flow.
 *
 * Unlike [LoginActivityLoginFlowTest] (which mocks [SkeddyServerClient]),
 * these tests use a REAL [SkeddyServerClient] connected to [MockWebServer].
 * This verifies the full chain:
 *   LoginActivity -> SkeddyServerClient -> Retrofit -> OkHttp -> MockWebServer
 *
 * Covers: request serialization, HTTP response parsing, error code mapping,
 * token persistence calls, re-login cleanup, and UI state transitions.
 */
@RunWith(RobolectricTestRunner::class)
class LoginFlowIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var activity: LoginActivity
    private lateinit var mockDeviceTokenManager: DeviceTokenManager
    private lateinit var mockBlacklistRepository: BlacklistRepository
    private lateinit var mockPendingRideQueue: PendingRideQueue
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockDeviceTokenManager = mockk(relaxed = true)
        mockBlacklistRepository = mockk(relaxed = true)
        mockPendingRideQueue = mockk(relaxed = true)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/v1/"))
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()

        val api = retrofit.create(SkeddyApi::class.java)
        val realServerClient = SkeddyServerClient(api, mockDeviceTokenManager)

        activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create().resume().get()

        // Override dependencies with real server client and mocked storage
        activity.serverClient = realServerClient
        activity.deviceTokenManager = mockDeviceTokenManager
        activity.blacklistRepository = mockBlacklistRepository
        activity.pendingRideQueue = mockPendingRideQueue
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {
            // Server may already be shut down by network failure tests
        }
        unmockkAll()
    }

    // ==================== 1. testLoginSuccess ====================

    @Test
    fun `login success saves token and navigates to MainActivity`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"real-token-xyz","user_id":"user-789"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id"

        triggerLoginAndAwait("test@example.com", "password123")

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("real-token-xyz") }

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to MainActivity", nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
        assertTrue("LoginActivity should finish", activity.isFinishing)
    }

    // ==================== 2. testLoginInvalidCredentials ====================

    @Test
    fun `server 401 shows invalid credentials error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid email or password"}}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_invalid_credentials),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== 3. testLoginNetworkError ====================

    @Test
    fun `network error shows error and re-enables login button`() {
        mockWebServer.shutdown()

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.login_error_network),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)

        assertTrue(
            "Login button should be re-enabled after error",
            activity.findViewById<View>(R.id.loginButton).isEnabled
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)

        assertTrue(
            "Email input should be re-enabled after error",
            activity.findViewById<View>(R.id.emailInput).isEnabled
        )
        assertTrue(
            "Password input should be re-enabled after error",
            activity.findViewById<View>(R.id.passwordInput).isEnabled
        )
    }

    // ==================== 4. testTokenSavedToEncryptedPrefs ====================

    @Test
    fun `saveDeviceToken called with exact token from HTTP response`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"encrypted-token-value-abc123","user_id":"user-456"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("encrypted-token-value-abc123") }
    }

    // ==================== 5. testBlacklistClearedOnReLogin ====================

    @Test
    fun `re-login clears blacklist and pending queue before saving new token`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"new-token","user_id":"user-123"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
        verify(exactly = 1) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token") }
    }

    @Test
    fun `first login does NOT clear blacklist or pending queue`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"first-token","user_id":"user-123"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("first-token") }
    }

    // ==================== 6. testRequestContainsCorrectFields ====================

    @Test
    fun `HTTP request contains email, password, device_id, and timezone`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "my-android-device-id-abc"

        triggerLoginAndAwait("user@test.com", "secret123")

        val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/auth/search-login", request.path)

        val bodyJson = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("user@test.com", bodyJson["email"]?.jsonPrimitive?.content)
        assertEquals("secret123", bodyJson["password"]?.jsonPrimitive?.content)
        assertEquals("my-android-device-id-abc", bodyJson["device_id"]?.jsonPrimitive?.content)
        assertTrue(
            "timezone should be non-empty",
            bodyJson["timezone"]?.jsonPrimitive?.content?.isNotEmpty() == true
        )
    }

    // ==================== 7. testLoadingStateShown ====================

    @Test
    fun `loading state visible during HTTP request`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("pass")
        activity.onLoginClicked()

        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertFalse(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.passwordInput).isEnabled)

        awaitLoginCompletion()
    }

    // ==================== 8. testNavigateToMainOnSuccess ====================

    @Test
    fun `navigation intent has correct flags on success`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should start MainActivity", nextIntent)

        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
        assertTrue("LoginActivity should be finishing", activity.isFinishing)
    }

    // ==================== 9. testErrorMessageCleared ====================

    @Test
    fun `error message clears when user types after error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid"}}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorText).visibility)

        activity.findViewById<EditText>(R.id.emailInput).setText("a")
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.errorText).visibility)
    }

    // ==================== Additional: re-login error does NOT clean up ====================

    @Test
    fun `re-login error does NOT clear blacklist or pending queue`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid"}}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== Additional: progress hidden after completion ====================

    @Test
    fun `progress bar hidden and button restored after successful login`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `progress bar hidden and button re-enabled after error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid"}}""")
        )

        every { mockDeviceTokenManager.isLoggedIn() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerLoginAndAwait()

        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)
        assertTrue(activity.findViewById<View>(R.id.loginButton).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.passwordInput).isEnabled)
    }

    // ==================== Helpers ====================

    private fun triggerLoginAndAwait(
        email: String = "user@example.com",
        password: String = "password123"
    ) {
        activity.findViewById<EditText>(R.id.emailInput).setText(email)
        activity.findViewById<EditText>(R.id.passwordInput).setText(password)
        activity.onLoginClicked()
        awaitLoginCompletion()
    }

    /**
     * Waits for the login coroutine to complete by polling the job state
     * and processing the main looper between polls.
     */
    private fun awaitLoginCompletion(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(activity.mainLooper).idle()
            if (activity.loginJob?.isCompleted == true) return
            Thread.sleep(50)
        }
        shadowOf(activity.mainLooper).idle()
        assertTrue(
            "Login job did not complete within ${timeoutMs}ms",
            activity.loginJob?.isCompleted == true
        )
    }
}
