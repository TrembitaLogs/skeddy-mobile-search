package com.skeddy.network

import android.content.Context
import android.provider.Settings
import com.skeddy.logging.SkeddyLogger
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeviceTokenManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var manager: DeviceTokenManager

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        context = RuntimeEnvironment.application
        val prefs = context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        manager = DeviceTokenManager(prefs, context.contentResolver)

        SkeddyLogger.reset()
        SkeddyLogger.logToLogcat = false
        SkeddyLogger.initWithFile(tempFolder.newFile("test_logs.txt"))
    }

    @After
    fun tearDown() {
        SkeddyLogger.reset()
    }

    // ==================== getDeviceToken Tests ====================

    @Test
    fun `getDeviceToken returns null for new instance`() {
        assertNull(manager.getDeviceToken())
    }

    @Test
    fun `getDeviceToken returns saved token`() {
        manager.saveDeviceToken("test-token-abc123")
        assertEquals("test-token-abc123", manager.getDeviceToken())
    }

    // ==================== saveDeviceToken Tests ====================

    @Test
    fun `saveDeviceToken persists across manager instances`() {
        manager.saveDeviceToken("persistent-token")

        val prefs = context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE)
        val newManager = DeviceTokenManager(prefs, context.contentResolver)
        assertEquals("persistent-token", newManager.getDeviceToken())
    }

    @Test
    fun `saveDeviceToken overwrites existing token`() {
        manager.saveDeviceToken("first-token")
        manager.saveDeviceToken("second-token")
        assertEquals("second-token", manager.getDeviceToken())
    }

    // ==================== clearDeviceToken Tests ====================

    @Test
    fun `clearDeviceToken removes stored token`() {
        manager.saveDeviceToken("token-to-clear")
        manager.clearDeviceToken()
        assertNull(manager.getDeviceToken())
    }

    @Test
    fun `clearDeviceToken is safe when no token stored`() {
        manager.clearDeviceToken()
        assertNull(manager.getDeviceToken())
    }

    // ==================== isLoggedIn Tests ====================

    @Test
    fun `isLoggedIn returns false for new instance`() {
        assertFalse(manager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns true after saveDeviceToken`() {
        manager.saveDeviceToken("some-token")
        assertTrue(manager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns false after clearDeviceToken`() {
        manager.saveDeviceToken("some-token")
        manager.clearDeviceToken()
        assertFalse(manager.isLoggedIn())
    }

    // ==================== getDeviceId Tests ====================

    @Test
    fun `getDeviceId returns configured ANDROID_ID`() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            "test_device_id_abc"
        )
        assertEquals("test_device_id_abc", manager.getDeviceId())
    }

    @Test
    fun `getDeviceId returns non-empty string`() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            "robolectric_id"
        )
        assertTrue(manager.getDeviceId().isNotEmpty())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `saveDeviceToken with empty string is stored`() {
        manager.saveDeviceToken("")
        assertEquals("", manager.getDeviceToken())
    }

    @Test
    fun `saveDeviceToken with special characters stores correctly`() {
        val specialToken = "tok/en+with=special&chars!@#\$%^*()"
        manager.saveDeviceToken(specialToken)
        assertEquals(specialToken, manager.getDeviceToken())
    }

    @Test
    fun `getDeviceId returns consistent value across calls`() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            "consistent_id"
        )
        val id1 = manager.getDeviceId()
        val id2 = manager.getDeviceId()
        assertEquals(id1, id2)
    }

    // ==================== Logging Tests ====================

    @Test
    fun `token value is never logged`() {
        val secretToken = "super-secret-token-xyz789"

        manager.saveDeviceToken(secretToken)
        manager.getDeviceToken()
        manager.clearDeviceToken()

        val allLogMessages = SkeddyLogger.getEntries().map { it.message }
        for (message in allLogMessages) {
            assertFalse(
                "Token value should not appear in logs: $message",
                message.contains(secretToken)
            )
        }
    }

    @Test
    fun `save operation is logged`() {
        manager.saveDeviceToken("any-token")

        val entries = SkeddyLogger.getEntriesByTag("DeviceTokenManager")
        assertTrue(entries.any { it.message == "DeviceToken: saved" })
    }

    @Test
    fun `clear operation is logged`() {
        manager.clearDeviceToken()

        val entries = SkeddyLogger.getEntriesByTag("DeviceTokenManager")
        assertTrue(entries.any { it.message == "DeviceToken: cleared" })
    }

    @Test
    fun `get operation logs exists false when no token`() {
        manager.getDeviceToken()

        val entries = SkeddyLogger.getEntriesByTag("DeviceTokenManager")
        assertTrue(entries.any { it.message == "DeviceToken: retrieved (exists=false)" })
    }

    @Test
    fun `get operation logs exists true when token present`() {
        manager.saveDeviceToken("token")
        SkeddyLogger.clear()

        manager.getDeviceToken()

        val entries = SkeddyLogger.getEntriesByTag("DeviceTokenManager")
        assertTrue(entries.any { it.message == "DeviceToken: retrieved (exists=true)" })
    }
}
