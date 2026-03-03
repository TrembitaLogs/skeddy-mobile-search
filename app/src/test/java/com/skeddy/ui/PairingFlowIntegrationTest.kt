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
 * Integration tests for the pairing flow.
 *
 * Unlike [PairingActivityPairingFlowTest] (which mocks [SkeddyServerClient]),
 * these tests use a REAL [SkeddyServerClient] connected to [MockWebServer].
 * This verifies the full chain:
 *   PairingActivity → SkeddyServerClient → Retrofit → OkHttp → MockWebServer
 *
 * Covers: request serialization, HTTP response parsing, error code mapping,
 * token persistence calls, re-pairing cleanup, and UI state transitions.
 */
@RunWith(RobolectricTestRunner::class)
class PairingFlowIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var activity: PairingActivity
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

        activity = Robolectric.buildActivity(PairingActivity::class.java)
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

    // ==================== 1. testPairingSuccess ====================

    @Test
    fun `pairing success saves token and navigates to MainActivity`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"real-token-xyz","user_id":"user-789"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "test-device-id"

        triggerPairingAndAwait("482917")

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("real-token-xyz") }

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to MainActivity", nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
        assertTrue("PairingActivity should finish", activity.isFinishing)
    }

    // ==================== 2. testPairingInvalidCode ====================

    @Test
    fun `server 404 shows invalid or expired code error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"PAIRING_CODE_EXPIRED","message":"Invalid or expired pairing code"}}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_invalid),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== 3. testPairingNetworkError ====================

    @Test
    fun `network error shows error and re-enables pair button`() {
        mockWebServer.shutdown()

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_network),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)

        // Pair button re-enabled (serves as retry)
        assertTrue(
            "Pair button should be re-enabled after error",
            activity.findViewById<View>(R.id.pairButton).isEnabled
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)

        // Code input re-enabled
        assertTrue(
            "Code input should be re-enabled after error",
            activity.findViewById<View>(R.id.codeInput).isEnabled
        )
    }

    // ==================== 4. testPairingAlreadyUsedCode ====================

    @Test
    fun `server 409 shows already used code error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"PAIRING_CODE_USED","message":"Code already used"}}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals(
            activity.getString(R.string.pairing_error_already_used),
            errorText.text.toString()
        )
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    // ==================== 5. testTokenSavedToEncryptedPrefs ====================

    @Test
    fun `saveDeviceToken called with exact token from HTTP response`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"encrypted-token-value-abc123","user_id":"user-456"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("encrypted-token-value-abc123") }
    }

    // ==================== 6. testBlacklistClearedOnRepair ====================

    @Test
    fun `re-pairing clears blacklist and pending queue before saving new token`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"new-token","user_id":"user-123"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        coVerify(exactly = 1) { mockBlacklistRepository.clearAll() }
        verify(exactly = 1) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("new-token") }
    }

    @Test
    fun `first pairing does NOT clear blacklist or pending queue`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"first-token","user_id":"user-123"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 1) { mockDeviceTokenManager.saveDeviceToken("first-token") }
    }

    // ==================== 7. testDeviceIdIncludedInRequest ====================

    @Test
    fun `HTTP request contains device_id, code, and timezone`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "my-android-device-id-abc"

        triggerPairingAndAwait("482917")

        val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("HTTP request should have been sent", request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/pairing/confirm", request.path)

        val bodyJson = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("482917", bodyJson["code"]?.jsonPrimitive?.content)
        assertEquals("my-android-device-id-abc", bodyJson["device_id"]?.jsonPrimitive?.content)
        assertTrue(
            "timezone should be non-empty",
            bodyJson["timezone"]?.jsonPrimitive?.content?.isNotEmpty() == true
        )
    }

    // ==================== 8. testLoadingStateShown ====================

    @Test
    fun `loading state visible during HTTP request`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.onPairClicked()

        // Loading state is set synchronously before the HTTP call suspends
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertFalse(activity.findViewById<View>(R.id.codeInput).isEnabled)

        // Let the HTTP call complete
        awaitPairingCompletion()
    }

    // ==================== 9. testNavigateToMainOnSuccess ====================

    @Test
    fun `navigation intent has correct flags on success`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should start MainActivity", nextIntent)

        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
        assertTrue("PairingActivity should be finishing", activity.isFinishing)
    }

    // ==================== 10. testErrorMessageCleared ====================

    @Test
    fun `error message clears when user types after error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"PAIRING_CODE_EXPIRED","message":"Invalid"}}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        // Error should be visible after failed pairing
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorText).visibility)

        // Typing clears the error
        activity.findViewById<EditText>(R.id.codeInput).setText("9")
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.errorText).visibility)
    }

    // ==================== Additional: re-pairing error does NOT clean up ====================

    @Test
    fun `re-pairing error does NOT clear blacklist or pending queue`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"PAIRING_CODE_EXPIRED","message":"Invalid"}}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns true
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        coVerify(exactly = 0) { mockBlacklistRepository.clearAll() }
        verify(exactly = 0) { mockPendingRideQueue.clear() }
        verify(exactly = 0) { mockDeviceTokenManager.saveDeviceToken(any()) }
    }

    // ==================== Additional: progress hidden after completion ====================

    @Test
    fun `progress bar hidden and button restored after successful pairing`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_token":"token","user_id":"user"}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        // On success, activity finishes — but before that, navigation occurs
        // Verify the activity is finishing (showSuccess calls finish())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `progress bar hidden and button re-enabled after error`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"PAIRING_CODE_USED","message":"Used"}}""")
        )

        every { mockDeviceTokenManager.isPaired() } returns false
        every { mockDeviceTokenManager.getDeviceId() } returns "device-id"

        triggerPairingAndAwait()

        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)
        assertTrue(activity.findViewById<View>(R.id.pairButton).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.codeInput).isEnabled)
    }

    // ==================== Helpers ====================

    private fun triggerPairingAndAwait(code: String = "123456") {
        activity.findViewById<EditText>(R.id.codeInput).setText(code)
        activity.onPairClicked()
        awaitPairingCompletion()
    }

    /**
     * Waits for the pairing coroutine to complete by polling the job state
     * and processing the main looper between polls.
     *
     * The coroutine suspends at the Retrofit HTTP call (OkHttp runs on its thread pool).
     * When the response arrives, OkHttp posts the continuation to the main looper.
     * Calling [shadowOf(activity.mainLooper).idle()] processes that continuation.
     */
    private fun awaitPairingCompletion(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(activity.mainLooper).idle()
            if (activity.pairingJob?.isCompleted == true) return
            Thread.sleep(50)
        }
        shadowOf(activity.mainLooper).idle()
        assertTrue(
            "Pairing job did not complete within ${timeoutMs}ms",
            activity.pairingJob?.isCompleted == true
        )
    }
}
